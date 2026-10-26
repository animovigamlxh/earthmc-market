package dev.earthmarket;

import com.google.gson.*;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

public final class EarthMarketClient implements ClientModInitializer {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIRECTORY = Path.of("config", "earthmc-market");
    private static final Path DATABASE = DIRECTORY.resolve("prices.json");
    private static final Path IMPORT = DIRECTORY.resolve("import.json");
    private static final Path BACKUP = DIRECTORY.resolve("prices-backup.json");
    private static final Path SETTINGS = DIRECTORY.resolve("settings.json");
    private static final Path DELETED_SIGNS = DIRECTORY.resolve("deleted-signs.json");
    private static final Set<String> IGNORED_SIGNS = new HashSet<>();
    // Quiet by default: only an explicit /emprice scan reports its completion.
    private static boolean showAutoScanMessages = false;
    private static final List<Quote> QUOTES = new ArrayList<>();
    private static final NearbySignScanner SIGNS = new NearbySignScanner(EarthMarketClient::foundSign);
    
    private static long lastSignSaveAt = 0;
    private static boolean signDirty = false;
    private static final Deque<Quote> SIGN_LOCATION_QUEUE = new ArrayDeque<>();
    private static final StringBuilder CHAT_SCAN = new StringBuilder();
    private static long scanStartedAt;
    private static int scanX, scanY, scanZ;
    private static boolean scanTargetExact;
    private static String scanFingerprint = "";
    private static Quote lastInspectedShop;
    private static final EnchantChatParser ENCHANT_CHAT = new EnchantChatParser();
    private static long enchantCaptureDeadline = 0L;
    private static Quote enchantCaptureShop;
    // Commands are submitted from ChatScreen; delay opening until it has closed.
    private static boolean openArbitrageGui = false;
    private static int openGuiInTicks = -1;
    // Intentionally strict: no quote is created when type, price or quantity is ambiguous.
    private static final Pattern SHOP_LINE = Pattern.compile("(?i)(?:quickshop|shop)\\s*[:|]\\s*([a-z0-9_:.-]+)\\s+(BUY|SELL)\\s+(\\d+)\\s*(?:items?\\s*)?(?:for|@)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:g|gold)\\b");

    /** Only process server sessions using EarthMC's official hostname. A LAN or
     *  singleplayer world must not activate the mod, even if its chat resembles QuickShop. */
    private static long serverCheckAt = 0L;
    private static boolean serverCheckValue = false;
    public static boolean isEarthMc(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) { serverCheckAt = 0; serverCheckValue = false; return false; }
        long now = System.currentTimeMillis();
        if (serverCheckAt != 0 && now - serverCheckAt < 1000L) return serverCheckValue;
        serverCheckAt = now;
        serverCheckValue = false;
        try {
            // Access reflectively to tolerate method mapping changes across patch releases.
            Object server = mc.getClass().getMethod("getCurrentServer").invoke(mc);
            if (server == null) return false;
            Object address = server.getClass().getField("ip").get(server);
            if (!(address instanceof String ip)) return false;
            String host = ip.strip().toLowerCase(Locale.ROOT);
            int port = host.lastIndexOf(':');
            if (port >= 0) host = host.substring(0, port);
            while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            serverCheckValue = host.equals("earthmc.net") || host.endsWith(".earthmc.net");
            return serverCheckValue;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return false;
        }
    }

    private static void loadSettings() {
        try {
            if (!Files.isRegularFile(SETTINGS)) return;
            try (Reader reader = Files.newBufferedReader(SETTINGS)) {
                JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();
                if (config.has("showAutoScanMessages"))
                    showAutoScanMessages = config.get("showAutoScanMessages").getAsBoolean();
            }
        } catch (Exception ignored) { showAutoScanMessages = false; }
    }
    private static void setAutoScanMessages(boolean enabled) {
        showAutoScanMessages = enabled;
        SIGNS.setShowAutomaticMessages(enabled);
        try {
            Files.createDirectories(DIRECTORY);
            JsonObject settings = new JsonObject();
            settings.addProperty("showAutoScanMessages", enabled);
            try (Writer writer = Files.newBufferedWriter(SETTINGS)) { JSON.toJson(settings, writer); }
        } catch (Exception exception) { say("Could not save scan message preference: " + exception.getMessage()); }
    }

    @Override public void onInitializeClient() {
        load();
        loadIgnoredSigns();
        loadSettings();
        SIGNS.setShowAutomaticMessages(showAutoScanMessages);
        ShopEspRenderer.register();
        PendingShopTravel.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!isEarthMc(client)) {
                SIGNS.pause();
                PendingShopTravel.cancel();
                ShopLocator.clear();
                SIGN_LOCATION_QUEUE.clear();
                CHAT_SCAN.setLength(0);
                enchantCaptureShop = null;
                lastInspectedShop = null;
                openGuiInTicks = -1;
                return;
            }
            SIGNS.tick(client);
            ShopLocator.tick(client);
            PendingShopTravel.tick(client);
            if (enchantCaptureShop != null && System.currentTimeMillis() > enchantCaptureDeadline) finishEnchantCapture();
            // Drain into resolver's deduplicated batch queue; HTTP requests are paced there.
            if (client.level != null) {
                for (int i = 0; i < 20 && !SIGN_LOCATION_QUEUE.isEmpty(); i++) {
                    Quote queued = SIGN_LOCATION_QUEUE.pollFirst();
                    if (QUOTES.contains(queued)) resolveSignLocation(queued);
                }
            }
            if (signDirty && System.currentTimeMillis() - lastSignSaveAt > 5000L) {
                signDirty = false; lastSignSaveAt = System.currentTimeMillis(); save();
            }
            if (openGuiInTicks < 0) return;
            if (openGuiInTicks-- > 0) return;
            openGuiInTicks = -1;
            if (client.player != null) client.gui.setScreen((openArbitrageGui ? new ArbitrageScreen() : new MarketScreen()));
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            literal("emprice").requires(source -> isEarthMc(Minecraft.getInstance()))
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
                    .then(argument("item", StringArgumentType.greedyString()).executes(ctx -> {
                        find(normalized(StringArgumentType.getString(ctx, "item"))); return 1;
                    })))
                .then(literal("enchant")
                    .then(argument("details", StringArgumentType.greedyString()).executes(ctx -> {
                        setInspectedEnchants(StringArgumentType.getString(ctx, "details")); return 1;
                    })))
                .then(literal("scan").executes(ctx -> {
                    say(SIGNS.startManual(Minecraft.getInstance()) ? "Scanning nearby loaded signs (32 blocks horizontal, +/-12 vertical)..." : "Join an EarthMC world first.");
                    return 1;
                }))
                .then(literal("autoscan")
                    .then(literal("on").executes(ctx -> { SIGNS.setAuto(true); SIGNS.start(Minecraft.getInstance()); say("Auto sign scan ON (every 30s or after moving to a new area)."); return 1; }))
                    .then(literal("off").executes(ctx -> { SIGNS.setAuto(false); say("Auto sign scan OFF."); return 1; })))
                .then(literal("scanmsg")
                    .then(literal("on").executes(ctx -> { setAutoScanMessages(true); say("Automatic scan chat messages ON."); return 1; }))
                    .then(literal("off").executes(ctx -> { setAutoScanMessages(false); say("Automatic scan chat messages OFF (scans still run). "); return 1; }))
                    .executes(ctx -> { say("Automatic scan chat messages: " + (showAutoScanMessages ? "ON" : "OFF") + ". Use /emprice scanmsg on|off."); return 1; }))
                .then(literal("gui").executes(ctx -> { openArbitrageGui = false; openGuiInTicks = 2; say("Opening Market GUI..."); return 1; }))
                .then(literal("arbitrage").executes(ctx -> { openArbitrageGui = true; openGuiInTicks = 2; say("Opening arbitrage opportunities..."); return 1; }))
                .then(literal("import").executes(ctx -> { importFile(); return 1; }))
                .then(literal("recent").executes(ctx -> { recent(); return 1; }))
                .then(literal("stats").executes(ctx -> { stats(); return 1; }))
                .then(literal("locate").executes(ctx -> { locateUnknown(); return 1; }))
                .then(literal("locdebug").executes(ctx -> { locationDebug(); return 1; }))
                .then(literal("backup").executes(ctx -> { backup(); return 1; }))
                .then(literal("restoredeleted").executes(ctx -> { say("Restored " + restoreDeletedSigns() + " ignored sign locations; rescan nearby to rediscover them."); return 1; }))
                .then(literal("reload").executes(ctx -> { QUOTES.clear(); load(); say("Reloaded " + QUOTES.size() + " records from disk."); return 1; }))
                .then(literal("clear").executes(ctx -> { QUOTES.clear(); save(); say("Local price records cleared."); return 1; }))
                .executes(ctx -> { say("/emprice add <item> <buy|sell> <gold> <amount> <shop> | find <item> | gui | arbitrage | scan | autoscan <on|off> | scanmsg <on|off> | recent | enchant <details> | stats | locate | locdebug | backup | reload | import | clear"); return 1; })
        ));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || emittingLocalMessage || !isEarthMc(Minecraft.getInstance())) return;
            String received = message.getString();
            // sendSystemMessage also fires this receive event. Never parse our own output.
            if (received.startsWith("[EM Market] ")) return;
            inspectShopMessage(received);
            captureEnchantMessage(received);
            Matcher match = SHOP_LINE.matcher(received);
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

    private static void inspectShopMessage(String raw) {
        String clean = raw.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        // Only parse a clearly identified QuickShop info block, never arbitrary chat.
        boolean heading = clean.contains("商店信息");
        boolean active = CHAT_SCAN.length() > 0 && System.currentTimeMillis() - scanStartedAt < 2500;
        if (!heading && !active) return;
        if (heading) {
            finishEnchantCapture();
            CHAT_SCAN.setLength(0);
            scanFingerprint = "";
            scanStartedAt = System.currentTimeMillis();
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            scanX = player.getBlockX(); scanY = player.getBlockY(); scanZ = player.getBlockZ();
            scanTargetExact = false;
            // Server sends the block info as chat shortly after the left-click. A nearby current
            // block hit can pin the shop to its real claim chunk, rather than the player's chunk.
            var hit = Minecraft.getInstance().hitResult;
            if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                var pos = blockHit.getBlockPos();
                double dx = player.getX() - (pos.getX() + 0.5);
                double dy = player.getY() - (pos.getY() + 0.5);
                double dz = player.getZ() - (pos.getZ() + 0.5);
                if (dx * dx + dy * dy + dz * dz <= 49.0) {
                    scanX = pos.getX(); scanY = pos.getY(); scanZ = pos.getZ();
                    scanTargetExact = true;
                }
            }
        }
        if (CHAT_SCAN.length() > 5000) { CHAT_SCAN.setLength(0); return; }
        CHAT_SCAN.append('\n').append(clean);
        // Finalize only after both direction and full priced item appear in the block.
        var result = QuickShopParser.parse(CHAT_SCAN.toString());
        if (!result.complete()) return;
        if (!Double.isFinite(result.gold) || result.amount > 1000000 || result.gold > 1e9) return;
        String fingerprint = result.owner + '|' + result.item + '|' + result.type + '|' + result.amount
            + '|' + result.gold + '|' + result.available + '|' + result.state;
        if (fingerprint.equals(scanFingerprint)) return;
        scanFingerprint = fingerprint;
        Quote q = new Quote();
        q.item = normalized(result.item); q.type = result.type;
        q.amount = result.amount; q.price = result.gold;
        q.shop = result.owner; q.x = scanX; q.y = scanY; q.z = scanZ;
        q.available = result.available; q.status = result.state;
        q.source = "earthmc-quickshop"; q.updated = Instant.now().toString();
        q.locationAccuracy = scanTargetExact ? "target-block" : "player-approx";
        // Reinspection of the same QuickShop should retain manually captured metadata.
        for (Quote old : QUOTES) {
            if (!"earthmc-quickshop".equals(old.source) || !Objects.equals(old.shop, q.shop)
                || !Objects.equals(old.type, q.type) || !Objects.equals(old.item, q.item)) continue;
            if (old.x == q.x && old.y == q.y && old.z == q.z) { q.enchantments = old.enchantments; break; }
        }
        // If this inspection refers to a nearby physical sign, refresh that sign entry
        // instead of storing a competing QuickShop quote with stale sign stock.
        Quote joined = reconcileInspectionWithSign(q);
        if (joined != null) q = joined;
        else upsert(q);
        lastInspectedShop = q; save();
        ENCHANT_CHAT.reset();
        enchantCaptureShop = q;
        enchantCaptureDeadline = System.currentTimeMillis() + 8000L;
        enrichLocation(q);
        say("Auto scanned: " + summary(q));
    }

    private static void captureEnchantMessage(String message) {
        if (enchantCaptureShop == null) return;
        if (System.currentTimeMillis() > enchantCaptureDeadline || !QUOTES.contains(enchantCaptureShop)) {
            finishEnchantCapture(); return;
        }
        // Reject unrelated chatter until the explicit Enchantments header arrives.
        if (!ENCHANT_CHAT.started() && !message.toLowerCase(Locale.ROOT).contains("enchantments")
            && !message.contains("附魔列表") && !message.contains("附魔属性")) return;
        if (ENCHANT_CHAT.accept(message) && ENCHANT_CHAT.hasEntries()) {
            String details = ENCHANT_CHAT.details();
            if (details.length() <= 600) {
                enchantCaptureShop.enchantments = details;
                enchantCaptureShop.updated = Instant.now().toString();
                save();
            }
        }
    }

    private static void finishEnchantCapture() {
        // Clear state BEFORE publishing any client-side message. Publishing messages
        // can synchronously enter the same receive listener again.
        String completion = enchantCaptureShop != null && ENCHANT_CHAT.hasEntries()
            ? "Captured " + ENCHANT_CHAT.details() + " for " + enchantCaptureShop.item : null;
        enchantCaptureShop = null;
        enchantCaptureDeadline = 0L;
        ENCHANT_CHAT.reset();
        if (completion != null) say(completion);
    }

    private static void setInspectedEnchants(String raw) {
        if (lastInspectedShop == null || !QUOTES.contains(lastInspectedShop)) {
            say("First left-click a QuickShop and wait for 'Auto scanned'."); return;
        }
        String details = raw.strip();
        if (details.equalsIgnoreCase("clear")) details = "";
        if (details.length() > 240 || details.codePoints().anyMatch(c -> Character.isISOControl(c))) {
            say("Invalid enchantment text (max 240 characters)."); return;
        }
        lastInspectedShop.enchantments = details;
        lastInspectedShop.updated = Instant.now().toString();
        save();
        say("Saved item details for " + lastInspectedShop.item + ": " + (details.isBlank() ? "(cleared)" : details));
    }

    /** Link a left-click inspection to an existing sign only if its identity is unambiguous. */
    private static Quote reconcileInspectionWithSign(Quote inspected) {
        var level = Minecraft.getInstance().level;
        String dim = level == null ? null : level.dimension().toString();
        Quote candidate = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean ambiguous = false;
        for (Quote old : QUOTES) {
            if (!"earthmc-sign".equals(old.source) || !Objects.equals(old.dimension, dim)
                || !Objects.equals(old.shop, inspected.shop) || !Objects.equals(old.type, inspected.type)
                || !Objects.equals(old.item, inspected.item)) continue;
            // Prefer exact sign block, then nearest signs within 4 blocks of the targeted block.
            int dx = old.x - inspected.x, dy = old.y - inspected.y, dz = old.z - inspected.z;
            int dist = dx * dx + dy * dy + dz * dz;
            if (dist > 16) continue;
            if (dist < bestDistance) { candidate = old; bestDistance = dist; ambiguous = false; }
            else if (dist == bestDistance) ambiguous = true;
        }
        if (candidate == null || ambiguous) return null;
        // A sign may display stale price while QuickShop provides current price and inventory.
        candidate.price = inspected.price;
        candidate.amount = inspected.amount;
        candidate.available = inspected.available;
        candidate.status = inspected.status;
        candidate.stockVerifiedAt = inspected.updated;
        candidate.updated = inspected.updated;
        if (candidate.enchantments == null || candidate.enchantments.isBlank())
            candidate.enchantments = inspected.enchantments;
        return candidate;
    }

    private static void foundSign(BlockPos pos, NearbySignScanner.Found found) {
        if (pos == null) return;
        var clientLevel = Minecraft.getInstance().level;
        if (clientLevel != null && IGNORED_SIGNS.contains(signKey(clientLevel.dimension().toString(), pos.getX(), pos.getY(), pos.getZ(), found.side()))) return;
        var p = found.quote();
        String item = normalized(p.item());
        var level = Minecraft.getInstance().level;
        String dimension = level == null ? null : level.dimension().toString();
        Quote matched = null;
        for (Quote old : QUOTES) {
            if (!"earthmc-sign".equals(old.source)
                || old.x != pos.getX() || old.y != pos.getY() || old.z != pos.getZ()
                || !Objects.equals(old.signSide, found.side())
                || !Objects.equals(old.dimension, dimension)) continue;
            matched = old;
            break;
        }
        // On EarthMC, the numeric value on the trading/status line is the displayed
        // remaining stock (sell) or buying capacity (buy). This is distinct
        // from the fourth line trade bundle size.
        String signStatus = p.state();
        boolean unavailable = "out-of-stock".equals(signStatus) || "full".equals(signStatus);
        // The sign's second-line number counts trade LOTS, not individual items.
        // Keep the lot count for the GUI and multiply by the fourth-line bundle size.
        Integer liveLots = unavailable ? 0 : p.signStock();
        Integer liveStock = null;
        if (liveLots != null) {
            long total = (long) liveLots * p.amount();
            // Do not silently overflow the integer database quantity.
            if (total <= Integer.MAX_VALUE) liveStock = (int) total;
        }
        if (matched != null) {
            boolean sameShop = Objects.equals(matched.item, item)
                && Objects.equals(matched.type, p.type()) && Objects.equals(matched.shop, p.owner());
            boolean samePrice = matched.price == p.gold() && matched.amount == p.amount();
            boolean wasUnavailable = "out-of-stock".equals(matched.status) || "full".equals(matched.status);
            boolean changed = !sameShop || !samePrice || !Objects.equals(matched.status, signStatus)
                || unavailable != wasUnavailable || !Objects.equals(matched.available, liveStock)
                || !Objects.equals(matched.signLots, liveLots);
            // Sign state is live; numerical inventory from chat is only a historical observation.
            if (!sameShop || !samePrice) {
                matched.available = null;
                matched.stockVerifiedAt = null;
                matched.enchantments = sameShop ? matched.enchantments : null;
            }
            matched.available = liveStock;
            matched.signLots = liveLots;
            matched.stockVerifiedAt = liveStock == null ? null : Instant.now().toString();
            matched.status = signStatus;
            matched.item = item; matched.type = p.type(); matched.shop = p.owner();
            matched.price = p.gold(); matched.amount = p.amount();
            // Mark the record dirty only when observable fields change.
            if (changed) { matched.updated = Instant.now().toString(); signDirty = true; }
            return;
        }
        Quote q = new Quote();
        q.item = item; q.type = p.type(); q.shop = p.owner(); q.amount = p.amount(); q.price = p.gold();
        q.x = pos.getX(); q.y = pos.getY(); q.z = pos.getZ(); q.signSide = found.side();
        q.dimension = dimension; q.status = signStatus;
        q.available = liveStock;
        q.signLots = liveLots;
        if (liveStock != null) q.stockVerifiedAt = q.updated;
        q.source = "earthmc-sign"; q.locationAccuracy = "sign-block";
        q.updated = Instant.now().toString();
        // Merge an earlier approximate QuickShop inspection when it unambiguously belongs here.
        Quote prior = null;
        for (Quote old : QUOTES) {
            if (!"earthmc-quickshop".equals(old.source)
                || !Objects.equals(old.shop, q.shop) || !Objects.equals(old.type, q.type)
                || !Objects.equals(old.item, q.item)) continue;
            int dx = old.x-q.x, dy=old.y-q.y, dz=old.z-q.z;
            if (dx*dx+dy*dy+dz*dz > 16) continue;
            if (prior != null) { prior = null; break; } // Don't merge ambiguous neighbors.
            prior = old;
        }
        if (prior != null) {
            q.enchantments = prior.enchantments;
            if (!unavailable && prior.price == q.price && prior.amount == q.amount) {
                q.available = liveStock;
                q.status = signStatus;
                q.stockVerifiedAt = liveStock == null ? null : q.updated;
            }
            QUOTES.remove(prior);
        }
        upsert(q); signDirty = true;
        if (!"confirmed".equals(q.locationStatus) && !"wilderness".equals(q.locationStatus)) {
            SIGN_LOCATION_QUEUE.addLast(q);
        }
    }

    private static void resolveSignLocation(Quote q) {
        EarthLocationResolver.resolve(q.x, q.z, result -> Minecraft.getInstance().execute(() -> {
            if (!QUOTES.contains(q)) return;
            if (result.ok()) {
                q.town = result.town(); q.nation = result.nation();
                q.locationStatus = result.wilderness() ? "wilderness" : "confirmed";
                signDirty = true;
            } else { q.locationStatus = "api-failed"; signDirty = true; }
        }));
    }

    private static void enrichLocation(Quote q) {
        if (!"earthmc-quickshop".equals(q.source)) return;
        EarthLocationResolver.resolve(q.x, q.z, result -> Minecraft.getInstance().execute(() -> {
            if (!result.ok()) {
                q.locationStatus = "api-failed";
                // Keep the quote even when the external service is unavailable.
            } else {
                q.locationStatus = result.wilderness() ? "wilderness" : "confirmed";
                q.town = result.town(); q.nation = result.nation();
            }
            if (QUOTES.contains(q)) {
                signDirty = true;
                if (result.ok()) say("Shop location: " + place(q) + " (" + q.locationAccuracy + ")");
            }
        }));
    }

    private static void locationDebug() {
        long total = QUOTES.stream().filter(q -> "earthmc-sign".equals(q.source)).count();
        long confirmed = QUOTES.stream().filter(q -> "earthmc-sign".equals(q.source) && "confirmed".equals(q.locationStatus)).count();
        long wild = QUOTES.stream().filter(q -> "earthmc-sign".equals(q.source) && "wilderness".equals(q.locationStatus)).count();
        long failed = QUOTES.stream().filter(q -> "earthmc-sign".equals(q.source) && "api-failed".equals(q.locationStatus)).count();
        say("Sign locations: total=" + total + " confirmed=" + confirmed + " wilderness=" + wild + " failed=" + failed + " waiting=" + (total - confirmed - wild - failed));
        say(EarthLocationResolver.diagnostics());
        QUOTES.stream().filter(q -> "earthmc-sign".equals(q.source) && !"confirmed".equals(q.locationStatus))
            .limit(3).forEach(q -> say(q.item + " @ " + q.x + "," + q.z + " => " + (q.locationStatus == null ? "pending" : q.locationStatus)));
    }

    private static void locateUnknown() {
        int count = 0;
        for (Quote q : QUOTES) {
            if (!"earthmc-quickshop".equals(q.source) && !"earthmc-sign".equals(q.source)) continue;
            if ("confirmed".equals(q.locationStatus) || "wilderness".equals(q.locationStatus)) continue;
            if (count >= 10) break;
            if ("earthmc-sign".equals(q.source)) resolveSignLocation(q); else enrichLocation(q); count++;
        }
        say("Requested location lookups for " + count + " saved shops (max 10 per command).");
    }

    private static String place(Quote q) {
        if ("wilderness".equals(q.locationStatus)) return "Wilderness";
        if (q.town == null && q.nation == null) return "Town/Nation pending";
        return "Town=" + (q.town == null ? "?" : q.town) + " Nation=" + (q.nation == null ? "?" : q.nation);
    }

    private static String normalized(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace('-', '_');
    }

    private static void find(String item) {
        // Exact match first; otherwise allow partial queries such as 风爆 or 珍珠.
        var entries = QUOTES.stream().filter(q -> item.equals(q.item)).toList();
        if (entries.isEmpty()) {
            entries = QUOTES.stream().filter(q -> q.item != null && q.item.contains(item)).toList();
        }
        if (entries.isEmpty()) { say("No records for " + item + ". Try /emprice recent for the recorded name."); return; }
        say("Results for " + item + " — " + entries.size() + " stored offers (not live stock):");
        entries.stream().sorted(Comparator.comparingDouble(q -> q.price / q.amount)).limit(12)
            .forEach(q -> say(summary(q)));
        var cheapest = entries.stream().filter(q -> q.type.equals("sell") && tradeable(q))
            .min(Comparator.comparingDouble(q -> q.price / q.amount));
        var highest = entries.stream().filter(q -> q.type.equals("buy") && tradeable(q))
            .max(Comparator.comparingDouble(q -> q.price / q.amount));
        if (entries.stream().map(q -> q.item).distinct().count() == 1 && cheapest.isPresent() && highest.isPresent()) {
            double spread = highest.get().price / highest.get().amount - cheapest.get().price / cheapest.get().amount;
            if (spread > 0) {
                say(String.format(Locale.ROOT, "Potential spread: %.4fg/item (%s → %s). Check stock, tax, timestamps and travel first.", spread, cheapest.get().shop, highest.get().shop));
            } else {
                say("No positive buy-low / sell-high spread among currently tradeable recorded quotes.");
            }
        }
    }

    private static boolean tradeable(Quote q) {
        return (q.available == null || q.available > 0)
            && !"full".equals(q.status) && !"out-of-stock".equals(q.status)
            && !"unavailable".equals(q.status)
            && !"sign-red-unverified".equals(q.status);
    }

    private static void recent() {
        say("Recent " + Math.min(8, QUOTES.size()) + " of " + QUOTES.size() + " saved observations:");
        QUOTES.stream().sorted(Comparator.comparing(q -> q.updated == null ? "" : q.updated, Comparator.reverseOrder()))
            .limit(8).forEach(q -> say(summary(q)));
    }

    private static void stats() {
        long sell = QUOTES.stream().filter(q -> "sell".equals(q.type)).count();
        long buy = QUOTES.stream().filter(q -> "buy".equals(q.type)).count();
        long blocked = QUOTES.stream().filter(q -> !tradeable(q)).count();
        long fresh = QUOTES.stream().filter(q -> recentlyObserved(q, 24)).count();
        long scanned = QUOTES.stream().filter(q -> "earthmc-quickshop".equals(q.source)).count();
        long items = QUOTES.stream().map(q -> q.item).distinct().count();
        say(String.format(Locale.ROOT,
            "Database: %d records, %d items, %d sell, %d buy, %d unavailable, %d scanned, %d updated in last 24h.",
            QUOTES.size(), items, sell, buy, blocked, scanned, fresh));
        long located = QUOTES.stream().filter(q -> "confirmed".equals(q.locationStatus)).count();
        say("Locations resolved: " + located + "/" + scanned + " scanned shops. Use /emprice locate to resolve older records.");
        say("Storage: " + DATABASE.toAbsolutePath());
    }

    private static boolean recentlyObserved(Quote q, int hours) {
        if (q.updated == null) return false;
        try {
            Instant when = Instant.parse(q.updated);
            return !when.isAfter(Instant.now()) && when.isAfter(Instant.now().minusSeconds(hours * 3600L));
        } catch (Exception ignored) { return false; }
    }

    private static void backup() {
        try {
            Files.createDirectories(DIRECTORY);
            if (!Files.isRegularFile(DATABASE)) { save(); }
            // Copy is intentionally one-way; never overwrite the live database with an older snapshot.
            Files.copy(DATABASE, BACKUP, StandardCopyOption.REPLACE_EXISTING);
            say("Backed up " + QUOTES.size() + " records to " + BACKUP.toAbsolutePath());
        } catch (Exception e) { say("Backup failed: " + e.getMessage()); }
    }

    private static String summary(Quote q) {
        return String.format(Locale.ROOT, "%s %s %.4fg/item [%.2fg/%d] %s {%s} (%d,%d,%d) available=%s status=%s %s %s",
            q.item, q.type, q.price / q.amount, q.price, q.amount, q.shop, place(q), q.x, q.y, q.z,
            q.available == null ? "?" : q.available, q.status == null ? "unknown" : q.status, q.source, q.updated);
    }

    private static String signKey(String dim, int x, int y, int z, String side) {
        return String.valueOf(dim) + "|" + x + "," + y + "," + z + "|" + String.valueOf(side);
    }
    private static void loadIgnoredSigns() {
        IGNORED_SIGNS.clear();
        try {
            if (!Files.isRegularFile(DELETED_SIGNS)) return;
            try (Reader r = Files.newBufferedReader(DELETED_SIGNS)) {
                String[] keys = JSON.fromJson(r, String[].class);
                if (keys != null) IGNORED_SIGNS.addAll(Arrays.asList(keys));
            }
        } catch (Exception e) { System.err.println("[EarthMarket] Failed to load deleted signs: " + e); }
    }
    private static void saveIgnoredSigns() {
        try {
            Files.createDirectories(DIRECTORY);
            try (Writer w = Files.newBufferedWriter(DELETED_SIGNS)) { JSON.toJson(IGNORED_SIGNS, w); }
        } catch (Exception e) { System.err.println("[EarthMarket] Failed to save deleted signs: " + e); }
    }
    /** Delete exactly one record selected in the GUI. Rescans won't restore deleted signs. */
    public static boolean deleteQuote(String id) {
        if (id == null || id.isBlank()) return false;
        for (Iterator<Quote> it = QUOTES.iterator(); it.hasNext();) {
            Quote q = it.next();
            if (!id.equals(q.id)) continue;
            if ("earthmc-sign".equals(q.source)) {
                IGNORED_SIGNS.add(signKey(q.dimension, q.x, q.y, q.z, q.signSide));
                saveIgnoredSigns();
            }
            it.remove();
            SIGN_LOCATION_QUEUE.remove(q);
            if (lastInspectedShop == q) lastInspectedShop = null;
            if (enchantCaptureShop == q) enchantCaptureShop = null;
            signDirty = false;
            save();
            return true;
        }
        return false;
    }
    public static int restoreDeletedSigns() {
        int count = IGNORED_SIGNS.size();
        IGNORED_SIGNS.clear();
        saveIgnoredSigns();
        return count;
    }

    private static void upsert(Quote q) {
        if (q.id == null || q.id.isBlank()) q.id = UUID.randomUUID().toString();
        QUOTES.removeIf(old -> {
            if ("earthmc-sign".equals(q.source)) {
                // Same sign identity requires dimension + exact block + sign face.
                // Items and price can change without becoming a new physical shop.
                return "earthmc-sign".equals(old.source)
                    && old.x == q.x && old.y == q.y && old.z == q.z
                    && Objects.equals(old.dimension, q.dimension)
                    && Objects.equals(old.signSide, q.signSide);
            }
            if ("earthmc-sign".equals(old.source)) return false;
            return Objects.equals(old.source, q.source)
                && Objects.equals(old.item, q.item)
                && Objects.equals(old.type, q.type)
                && old.x == q.x && old.y == q.y && old.z == q.z
                && Objects.equals(old.dimension, q.dimension)
                && Objects.equals(old.shop, q.shop);
        });
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
        return q.item != null && q.item.length() <= 100 && !q.item.isBlank()
            && q.type != null && (q.type.equalsIgnoreCase("sell") || q.type.equalsIgnoreCase("buy"))
            && q.shop != null && !q.shop.isBlank() && q.shop.length() <= 150
            && Double.isFinite(q.price) && q.price > 0 && q.amount > 0 && q.amount <= 1000000;
    }

    private static void load() {
        try {
            if (!Files.exists(DATABASE)) return;
            try (Reader reader = Files.newBufferedReader(DATABASE)) {
                Quote[] parsed = JSON.fromJson(reader, Quote[].class);
                if (parsed != null) for (Quote q : parsed) if (valid(q)) {
                    if (q.id == null || q.id.isBlank()) q.id = UUID.randomUUID().toString();
                    QUOTES.add(q);
                }
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

    public static void locateShop(DisplayQuote q) {
        say(ShopLocator.start(q));
    }

    private static boolean emittingLocalMessage = false;

    private static void say(String message) {
        // Protect the game-message listener from synchronous re-entry.
        if (emittingLocalMessage) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        emittingLocalMessage = true;
        try {
            mc.player.sendSystemMessage(Component.literal("[EM Market] " + message));
        } finally {
            emittingLocalMessage = false;
        }
    }

    // Immutable read-only snapshots for the UI; never expose mutable database objects.
    public record DisplayQuote(String id, String item, String type, String shop, double unitPrice,
                               double bundlePrice, int amount, Integer available, Integer signLots, String status,
                               String town, String nation, String updated, String accuracy, String source, String enchantments,
                               int x, int y, int z, String dimension) {}

    public static String variantLabel(DisplayQuote q) {
        String suffix = q.enchantments() == null ? "" : q.enchantments().strip();
        return suffix.isEmpty() ? q.item() : q.item() + " [" + suffix + "]";
    }

    public static List<DisplayQuote> displayQuotes() {
        List<DisplayQuote> result = new ArrayList<>();
        for (Quote q : QUOTES) {
            if (!valid(q)) continue;
            result.add(new DisplayQuote(q.id, q.item, q.type, q.shop, q.price / q.amount,
                q.price, q.amount, q.available, q.signLots, q.status, q.town, q.nation,
                q.updated, q.locationAccuracy, q.source, q.enchantments,
                q.x, q.y, q.z, q.dimension));
        }
        return result;
    }

    private static class Quote {
        String id, item, type, shop, source, updated;
        double price;
        int amount, x, y, z;
        Integer available;
        Integer signLots; // Number of trade lots shown on the sign; available is item units.
        String status;
        String town, nation, locationStatus, locationAccuracy, signSide, dimension, enchantments, stockVerifiedAt;
    }
}
