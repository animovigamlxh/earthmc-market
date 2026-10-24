package dev.earthmarket;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Resolves whether a shop's actual town is the capital of its nation.
 * No teleport command is sent by this class; it only opens the chat draft. */
public final class CapitalTravelResolver {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final URI URL = URI.create("https://api.earthmc.net/v4/towns");
    private static final long TTL = TimeUnit.HOURS.toMillis(2);
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Boolean>> PENDING = new ConcurrentHashMap<>();
    private record Cached(boolean capital, long until) {}
    private CapitalTravelResolver() {}

    private static boolean valid(String name) {
        return name != null && !name.isBlank() && !"unknown".equalsIgnoreCase(name)
            && !name.startsWith("(") && name.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c));
    }
    /** Prepare an appropriate command by checking the official town capital flag.
     * When the status cannot be verified, refrain from guessing. */
    public static void prepare(Minecraft mc, String town, String nation) {
        if (!valid(town)) { notify(mc, "Shop town is unknown; cannot choose a teleport command."); return; }
        String key = town.toLowerCase(Locale.ROOT);
        Cached cached = CACHE.get(key);
        if (cached != null && cached.until > System.currentTimeMillis()) {
            openDraft(mc, town, nation, cached.capital); return;
        }
        notify(mc, "Checking whether " + town + " is a nation capital...");
        PENDING.computeIfAbsent(key, k -> {
            JsonObject body = new JsonObject();
            JsonArray towns = new JsonArray(); towns.add(town); body.add("query", towns);
            HttpRequest req = HttpRequest.newBuilder(URL)
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            CompletableFuture<Boolean> request = HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
                    JsonArray arr = JsonParser.parseString(response.body()).getAsJsonArray();
                    for (JsonElement entry : arr) {
                        if (!entry.isJsonObject()) continue;
                        JsonObject obj = entry.getAsJsonObject();
                        if (!obj.has("name") || !town.equalsIgnoreCase(obj.get("name").getAsString())) continue;
                        JsonObject status = obj.getAsJsonObject("status");
                        if (status == null || !status.has("isCapital") || status.get("isCapital").isJsonNull())
                            throw new IllegalStateException("Missing status.isCapital");
                        return status.get("isCapital").getAsBoolean();
                    }
                    throw new IllegalStateException("Town not found in API response");
                });
            request.whenComplete((capital, err) -> {
                PENDING.remove(k);
                if (err == null) CACHE.put(k, new Cached(capital, System.currentTimeMillis() + TTL));
            });
            return request;
        }).whenComplete((capital, error) -> mc.execute(() -> {
            if (error != null) {
                notify(mc, "Cannot verify capital status (network/API). Use T/N manually; no destination was guessed.");
            } else {
                openDraft(mc, town, nation, capital);
            }
        }));
    }
    private static void openDraft(Minecraft mc, String town, String nation, boolean capital) {
        if (capital && !valid(nation)) {
            notify(mc, "This is a capital but its nation is unknown; cannot prepare /n spawn.");
            return;
        }
        String command = capital ? "/n spawn " + nation : "/t spawn " + town;
        mc.gui.setScreen(new ChatScreen(command, false));
    }
    private static void notify(Minecraft mc, String message) {
        mc.execute(() -> { if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[EM Market] " + message)); });
    }
}
