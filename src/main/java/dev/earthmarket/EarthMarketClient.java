package dev.earthmarket;

import com.google.gson.*;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

public final class EarthMarketClient implements ClientModInitializer {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIRECTORY = Path.of("config", "earthmc-market");
    private static final Path DATABASE = DIRECTORY.resolve("prices.json");
    private static final Path IMPORT = DIRECTORY.resolve("import.json");
    private static final List<Quote> QUOTES = new ArrayList<>();
    // Intentionally strict: no quote is created when type, price or quantity is ambiguous.
    private static final Pattern SHOP_LINE = Pattern.compile("(?i)(?:quickshop|shop)\\s*[:|]\\s*([a-z0-9_:.-]+)\\s+(BUY|SELL)\\s+(\\d+)\\s*(?:items?\\s*)?(?:for|@)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:g|gold)\\b");

    @Override public void onInitializeClient() {
        load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            literal("emprice")
                .then(literal("add")
                    .then(argument("item", StringArgumentType.word())
                        .then(argument("type", StringArgumentType.word())
                            .then(argument("gold", DoubleArgumentType.doubleArg(0.000001))
                                .then(argument("amount", IntegerArgumentType.integer(1))
                                    .then(argument("shop", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String kind = StringArgumentType.getString(ctx, "type").toLowerCase(Locale.ROOT);
                                            if (!kind.equals("buy") && !kind.equals("sell")) { say("Trade type must be buy or sell."); return 0; }
                                            var player = Minecraft.getInstance().player;
                                            if (player == null) return 0;
                                            Quote q = new Quote();
                                            q.item = normalized(StringArgumentType.getString(ctx, "item"));
                                            q.type = kind;
                                            q.price = DoubleArgumentType.getDouble(ctx, "gold");
                                            q.amount = IntegerArgumentType.getInteger(ctx, "amount");
                                            q.shop = StringArgumentType.getString(ctx, "shop");
                                            q.x = player.getBlockX(); q.y = player.getBlockY(); q.z = player.getBlockZ();
                                            q.source = "manual"; q.updated = Instant.now().toString();
                                            upsert(q); save(); say("Recorded " + summary(q)); return 1;
                                        })))))))
                .then(literal("find")
                    .then(argument("item", StringArgumentType.word()).executes(ctx -> {
                        find(normalized(StringArgumentType.getString(ctx, "item"))); return 1;
                    })))
                .then(literal("import").executes(ctx -> { importFile(); return 1; }))
                .then(literal("clear").executes(ctx -> { QUOTES.clear(); save(); say("Local price records cleared."); return 1; }))
                .executes(ctx -> { say("/emprice add <item> <buy|sell> <gold> <amount> <shop> | find <item> | import | clear"); return 1; })
        ));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            Matcher match = SHOP_LINE.matcher(message.getString());
            if (!match.find()) return;
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            Quote q = new Quote();
            q.item = normalized(match.group(1)); q.type = match.group(2).toLowerCase(Locale.ROOT);
            q.amount = Integer.parseInt(match.group(3)); q.price = Double.parseDouble(match.group(4));
            q.shop = "Observed shop"; q.x = player.getBlockX(); q.y = player.getBlockY(); q.z = player.getBlockZ();
            q.updated = Instant.now().toString(); q.source = "game-chat";
            upsert(q); save(); say("Scanned quote: " + summary(q));
        });
    }

    private static String normalized(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace('-', '_');
    }

    private static void find(String item) {
        var entries = QUOTES.stream().filter(q -> item.equals(q.item)).toList();
        if (entries.isEmpty()) { say("No records for " + item + "."); return; }
        say(item + " — " + entries.size() + " stored offers (not live stock):");
        entries.stream().sorted(Comparator.comparingDouble(q -> q.price / q.amount)).limit(12)
            .forEach(q -> say(summary(q)));
        var cheapest = entries.stream().filter(q -> q.type.equals("sell"))
            .min(Comparator.comparingDouble(q -> q.price / q.amount));
        var highest = entries.stream().filter(q -> q.type.equals("buy"))
            .max(Comparator.comparingDouble(q -> q.price / q.amount));
        if (cheapest.isPresent() && highest.isPresent()) {
            double spread = highest.get().price / highest.get().amount - cheapest.get().price / cheapest.get().amount;
            say(String.format(Locale.ROOT, "Potential spread: %.4fg/item (%s → %s). Check stock, tax and travel first.", spread, cheapest.get().shop, highest.get().shop));
        }
    }

    private static String summary(Quote q) {
        return String.format(Locale.ROOT, "%s %s %.4fg/item [%.2fg/%d] %s (%d,%d,%d), %s %s",
            q.item, q.type, q.price / q.amount, q.price, q.amount, q.shop, q.x, q.y, q.z, q.source, q.updated);
    }

    private static void upsert(Quote q) {
        QUOTES.removeIf(old -> old.item.equals(q.item) && old.type.equals(q.type)
            && old.x == q.x && old.y == q.y && old.z == q.z && old.shop.equals(q.shop));
        QUOTES.add(q);
    }

    private static void importFile() {
        if (!Files.isRegularFile(IMPORT)) { say("Place a JSON array at " + IMPORT); return; }
        int accepted = 0;
        try (Reader reader = Files.newBufferedReader(IMPORT)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) throw new IllegalArgumentException("Expected JSON array");
            // Cap input size to protect client performance and accidental huge exports.
            if (root.getAsJsonArray().size() > 10000) throw new IllegalArgumentException("Max 10000 quotes per file");
            for (JsonElement element : root.getAsJsonArray()) {
                try {
                    Quote q = JSON.fromJson(element, Quote.class);
                    if (q == null || !valid(q)) continue;
                    q.item = normalized(q.item); q.type = q.type.toLowerCase(Locale.ROOT);
                    q.source = q.source == null ? "json-import" : q.source;
                    q.updated = Instant.now().toString(); upsert(q); accepted++;
                } catch (RuntimeException ignored) { /* skip malformed entry */ }
            }
            save(); say("Imported " + accepted + " valid quotes.");
        } catch (Exception e) { say("Import failed: " + e.getMessage()); }
    }

    private static boolean valid(Quote q) {
        return q.item != null && q.item.matches("[a-zA-Z0-9_:.-]{1,100}")
            && q.type != null && (q.type.equalsIgnoreCase("sell") || q.type.equalsIgnoreCase("buy"))
            && q.shop != null && !q.shop.isBlank() && q.shop.length() <= 150
            && Double.isFinite(q.price) && q.price > 0 && q.amount > 0 && q.amount <= 1000000;
    }

    private static void load() {
        try {
            if (!Files.exists(DATABASE)) return;
            try (Reader reader = Files.newBufferedReader(DATABASE)) {
                Quote[] parsed = JSON.fromJson(reader, Quote[].class);
                if (parsed != null) for (Quote q : parsed) if (valid(q)) QUOTES.add(q);
            }
        } catch (Exception e) { System.err.println("[EarthMarket] Could not read prices: " + e); }
    }

    private static void save() {
        try {
            Files.createDirectories(DIRECTORY);
            Path temp = DIRECTORY.resolve("prices.json.tmp");
            try (Writer writer = Files.newBufferedWriter(temp)) { JSON.toJson(QUOTES, writer); }
            try { Files.move(temp, DATABASE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, DATABASE, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { say("Failed to save prices: " + e.getMessage()); }
    }

    private static void say(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[EM Market] " + message), false);
    }

    private static class Quote {
        String item, type, shop, source, updated;
        double price;
        int amount, x, y, z;
    }
}
