package dev.earthmarket;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Official EarthMC v4/location API. World coordinates, NOT chunk coordinates. */
public final class EarthLocationResolver {
    private static final URI URL = URI.create("https://api.earthmc.net/v4/location");
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6)).build();
    private static final ConcurrentMap<String, Result> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, CompletableFuture<Result>> PENDING = new ConcurrentHashMap<>();
    private static final long TTL = TimeUnit.HOURS.toMillis(6);
    private static final long FAILURE_TTL = TimeUnit.MINUTES.toMillis(2);
    private EarthLocationResolver() {}

    public record Result(String town, String nation, boolean wilderness, boolean ok, long at) {}

    public static void resolve(int x, int z, Consumer<Result> done) {
        // Towny claim membership is defined at the chunk level; nearby chunks can belong to different towns.
        String key = Math.floorDiv(x, 16) + ":" + Math.floorDiv(z, 16);
        Result cached = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < (cached.ok() ? TTL : FAILURE_TTL)) {
            done.accept(cached); return;
        }
        CompletableFuture<Result> future = PENDING.computeIfAbsent(key, ignored -> {
            String requestJson = "{\"query\":[[" + x + "," + z + "]]}";
            HttpRequest request = HttpRequest.newBuilder(URL)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson)).build();
            CompletableFuture<Result> f = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
                    JsonArray arr = JsonParser.parseString(response.body()).getAsJsonArray();
                    if (arr.isEmpty()) throw new IllegalStateException("Empty location response");
                    JsonObject value = arr.get(0).getAsJsonObject();
                    if (!value.has("isWilderness")) throw new IllegalStateException("No wilderness flag");
                    boolean wilderness = value.get("isWilderness").getAsBoolean();
                    String town = name(value, "town");
                    String nation = name(value, "nation");
                    return new Result(town, nation, wilderness, true, System.currentTimeMillis());
                }).exceptionally(error -> new Result(null, null, false, false, System.currentTimeMillis()));
            f.thenAccept(result -> CACHE.put(key, result));
            f.whenComplete((result, error) -> PENDING.remove(key, f));
            return f;
        });
        future.thenAccept(done);
    }

    private static String name(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) return null;
        JsonObject nested = obj.getAsJsonObject(key);
        return nested.has("name") && !nested.get("name").isJsonNull() ? nested.get("name").getAsString() : null;
    }
}
