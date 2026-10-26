package dev.earthmarket;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Batches EarthMC /v4/location requests and caches by 16x16 Towny chunk. */
public final class EarthLocationResolver {
    private static final URI URL = URI.create("https://api.earthmc.net/v4/location");
    private static final Path DISK = Path.of("config", "earthmc-market", "location-cache.json");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "em-market-locations"); t.setDaemon(true); return t;
    });
    private static final int BATCH_SIZE = 40;
    private static final long TTL = TimeUnit.HOURS.toMillis(6);
    private static final long FAILURE_TTL = TimeUnit.SECONDS.toMillis(30);
    private static final Object LOCK = new Object();
    private static final Map<String, Result> CACHE = new HashMap<>();
    private static final LinkedHashMap<String, Pending> QUEUE = new LinkedHashMap<>();
    private static boolean inFlight = false, dirty = false;
    private static long nextRequestAt = 0, lastSaveAt = 0;
    private static int rateLimitStreak = 0;
    private static volatile String lastError = "none";
    private static final AtomicLong successCount = new AtomicLong(), failureCount = new AtomicLong(), requests = new AtomicLong();

    public record Result(String town, String nation, boolean wilderness, boolean ok, long at, String error) {}
    private static final class Pending {
        final int x, z;
        final List<Consumer<Result>> listeners = new ArrayList<>();
        Pending(int x, int z) { this.x = x; this.z = z; }
    }
    private EarthLocationResolver() {}
    static {
        readCache();
        WORKER.scheduleWithFixedDelay(EarthLocationResolver::pumpSafely, 2, 2, TimeUnit.SECONDS);
    }
    public static String diagnostics() {
        synchronized (LOCK) {
            return "API OK=" + successCount.get() + " failed=" + failureCount.get()
                + " requests=" + requests.get() + " queuedChunks=" + QUEUE.size()
                + " active=" + inFlight + " cache=" + CACHE.size()
                + " backoffSeconds=" + Math.max(0, (nextRequestAt - System.currentTimeMillis() + 999) / 1000)
                + " lastError=" + lastError;
        }
    }
    public static void resolve(int x, int z, Consumer<Result> done) {
        String key = key(x, z);
        Result hit;
        synchronized (LOCK) {
            hit = CACHE.get(key);
            if (hit != null && System.currentTimeMillis() - hit.at() < (hit.ok() ? TTL : FAILURE_TTL)) {
                // Dispatch outside lock to avoid re-entrant callbacks mutating state.
            } else {
                hit = null;
                QUEUE.computeIfAbsent(key, k -> new Pending(x, z)).listeners.add(done);
            }
        }
        if (hit != null) dispatch(done, hit);
    }
    private static String key(int x, int z) { return Math.floorDiv(x, 16) + ":" + Math.floorDiv(z, 16); }
    private static void dispatch(Consumer<Result> callback, Result result) {
        try { callback.accept(result); } catch (Exception ignored) { }
    }
    private static void pumpSafely() {
        try { pump(); } catch (Exception ex) { lastError = "worker: " + ex; }
    }
    private static void pump() {
        List<Map.Entry<String, Pending>> batch = new ArrayList<>();
        synchronized (LOCK) {
            saveCacheIfDue();
            if (inFlight || System.currentTimeMillis() < nextRequestAt || QUEUE.isEmpty()) return;
            for (var entry : QUEUE.entrySet()) {
                batch.add(Map.entry(entry.getKey(), entry.getValue()));
                if (batch.size() == BATCH_SIZE) break;
            }
            inFlight = true;
        }
        JsonObject payload = new JsonObject();
        JsonArray arr = new JsonArray();
        for (var entry : batch) {
            JsonArray coord = new JsonArray(); coord.add(entry.getValue().x); coord.add(entry.getValue().z); arr.add(coord);
        }
        payload.add("query", arr);
        HttpRequest req = HttpRequest.newBuilder(URL).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "EarthMC-Market-Fabric/0.4.2")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
        requests.incrementAndGet();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .whenComplete((response, failure) -> WORKER.execute(() -> finish(batch, response, failure)));
    }
    private static void finish(List<Map.Entry<String, Pending>> batch, HttpResponse<String> resp, Throwable failure) {
        long now = System.currentTimeMillis();
        if (resp != null && resp.statusCode() == 429) {
            long waitMs = retryAfterMs(resp);
            synchronized (LOCK) {
                rateLimitStreak++;
                if (waitMs <= 0) waitMs = Math.min(120000L, 5000L * (1L << Math.min(rateLimitStreak, 4)));
                nextRequestAt = now + waitMs;
                inFlight = false;
                lastError = "HTTP 429 (retry in " + (waitMs / 1000) + "s)";
            }
            // Leave pending requests queued, so no scan data is lost.
            return;
        }
        List<Result> results = new ArrayList<>();
        try {
            if (failure != null) throw new IllegalStateException(failure.toString());
            if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode() + " " + shorten(resp.body()));
            JsonElement parsed = JsonParser.parseString(resp.body());
            if (!parsed.isJsonArray() || parsed.getAsJsonArray().size() != batch.size())
                throw new IllegalStateException("Location response length mismatch " + shorten(resp.body()));
            for (JsonElement part : parsed.getAsJsonArray()) {
                if (!part.isJsonObject()) throw new IllegalStateException("Invalid location entry");
                JsonObject obj = part.getAsJsonObject();
                boolean wilderness = obj.has("isWilderness") && !obj.get("isWilderness").isJsonNull()
                    && obj.get("isWilderness").getAsBoolean();
                String town = name(obj, "town"), nation = name(obj, "nation");
                if (!obj.has("isWilderness") && town == null && nation == null)
                    throw new IllegalStateException("Missing location fields");
                results.add(new Result(town, nation, wilderness, true, now, null));
            }
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            for (int i = 0; i < batch.size(); i++) results.add(new Result(null, null, false, false, now, lastError));
        }
        List<Runnable> callbacks = new ArrayList<>();
        synchronized (LOCK) {
            inFlight = false;
            rateLimitStreak = 0;
            nextRequestAt = now + 2000; // Conservative global request pacing.
            for (int i = 0; i < batch.size(); i++) {
                var entry = batch.get(i);
                Pending current = QUEUE.remove(entry.getKey());
                Result result = results.get(i);
                CACHE.put(entry.getKey(), result);
                dirty = true;
                if (result.ok()) successCount.incrementAndGet(); else failureCount.incrementAndGet();
                if (current != null) for (Consumer<Result> c : current.listeners)
                    callbacks.add(() -> dispatch(c, result));
            }
        }
        for (Runnable cb : callbacks) cb.run();
    }
    private static long retryAfterMs(HttpResponse<String> response) {
        String h = response.headers().firstValue("Retry-After").orElse("").trim();
        try { return Math.min(300000L, Math.max(1000L, Long.parseLong(h) * 1000L)); }
        catch (Exception ignored) { return -1; }
    }
    private static String name(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement item = obj.get(key);
        if (item.isJsonPrimitive()) return item.getAsString();
        if (!item.isJsonObject()) return null;
        JsonObject nested = item.getAsJsonObject();
        return nested.has("name") && !nested.get("name").isJsonNull() ? nested.get("name").getAsString() : null;
    }
    private static String shorten(String text) { return text == null ? "" : text.substring(0, Math.min(140, text.length())); }
    private static void readCache() {
        try {
            if (!Files.isRegularFile(DISK)) return;
            JsonObject obj = JsonParser.parseString(Files.readString(DISK)).getAsJsonObject();
            for (var kv : obj.entrySet()) {
                JsonObject p = kv.getValue().getAsJsonObject();
                long at = p.get("at").getAsLong();
                if (System.currentTimeMillis() - at >= TTL) continue;
                CACHE.put(kv.getKey(), new Result(name(p, "town"), name(p, "nation"), p.get("wilderness").getAsBoolean(), true, at, null));
            }
        } catch (Exception e) { lastError = "cache load: " + e.getMessage(); }
    }
    private static void saveCacheIfDue() {
        if (!dirty || System.currentTimeMillis() - lastSaveAt < 10000) return;
        JsonObject out = new JsonObject();
        synchronized (LOCK) {
            for (var entry : CACHE.entrySet()) {
                Result r = entry.getValue();
                if (!r.ok() || System.currentTimeMillis() - r.at() >= TTL) continue;
                JsonObject o = new JsonObject();
                if (r.town() != null) o.addProperty("town", r.town());
                if (r.nation() != null) o.addProperty("nation", r.nation());
                o.addProperty("wilderness", r.wilderness()); o.addProperty("at", r.at());
                out.add(entry.getKey(), o);
            }
            dirty = false; lastSaveAt = System.currentTimeMillis();
        }
        try { Files.createDirectories(DISK.getParent()); Files.writeString(DISK, new GsonBuilder().setPrettyPrinting().create().toJson(out)); }
        catch (Exception e) { dirty = true; lastError = "cache save: " + e.getMessage(); }
    }
}
