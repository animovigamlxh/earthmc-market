package dev.earthmarket;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.MemoryBankAccess;
import red.jackf.chesttracker.api.memory.MemoryKey;

import java.time.Instant;
import java.util.*;

/** Read-only snapshot adapter. Never mutates Chest Tracker's memory bank. */
public final class SellModeData {
    private SellModeData() {}

    public record Box(String dimension, BlockPos position, int quantity, Instant observed) {}
    public record Offer(String itemId, String name, int count, double unitGold, int possible,
                        boolean capacityUnknown, EarthMarketClient.DisplayQuote buyer,
                        List<Box> boxes) {
        public double expectedGold() { return possible * unitGold; }
    }
    public record Snapshot(List<Offer> offers, int boxes, int items, String warning) {}

    /** Called ONLY when the Sell Mode screen is opened or Refresh clicked. */
    public static Snapshot read() {
        if (!FabricLoader.getInstance().isModLoaded("chesttracker"))
            return new Snapshot(List.of(), 0, 0, "Chest Tracker not installed. Install the 26.2 JAR alongside this mod.");
        try {
            return readInstalled();
        } catch (LinkageError | RuntimeException error) {
            return new Snapshot(List.of(), 0, 0, "Chest Tracker read failed: " + error.getClass().getSimpleName());
        }
    }

    private record Stock(String id, String name, int count, List<Box> boxes) {}
    private static Snapshot readInstalled() {
        var optional = MemoryBankAccess.INSTANCE.getLoaded();
        if (optional.isEmpty()) return new Snapshot(List.of(), 0, 0, "Chest Tracker has no loaded memory bank on this server.");
        var bank = optional.get();
        Map<String, MutableStock> stock = new HashMap<>();
        int totalBoxes = 0;
        Set<String> countedContainers = new HashSet<>();
        int totalItems = 0;
        // Snapshot the index, not the world. All recorded boxes are included regardless of ownership.
        for (Map.Entry<?, MemoryKey> entry : new ArrayList<>(bank.getAllMemories().entrySet())) {
            String dimension = String.valueOf(entry.getKey());
            for (Map.Entry<BlockPos, Memory> boxEntry : new ArrayList<>(entry.getValue().getMemories().entrySet())) {
                Memory memory = boxEntry.getValue();
                if (memory == null) continue;
                BlockPos pos = boxEntry.getKey();
                // Two sides of one double chest may both be indexed; count the shared memory once.
                List<BlockPos> linked = new ArrayList<>();
                linked.add(pos);
                if (memory.otherPositions() != null) linked.addAll(memory.otherPositions());
                linked.sort(SellModeData::comparePos);
                String containerKey = dimension + "|" + linked.toString();
                if (!countedContainers.add(containerKey)) continue;
                totalBoxes++;
                Map<String, Integer> perBox = new HashMap<>();
                for (ItemStack stack : memory.items()) {
                    if (stack == null || stack.isEmpty() || stack.getCount() <= 0) continue;
                    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
                    if (id.equals("minecraft:air")) continue;
                    String display = stack.getHoverName().getString();
                    // Variant items must not silently match plain ID-only QuickShop offers.
                    boolean special = !stack.getComponents().equals(new ItemStack(stack.getItem()).getComponents());
                    String key = id + (special ? "|special:" + stack.getComponents().hashCode() : "");
                    MutableStock item = stock.computeIfAbsent(key, k -> new MutableStock(id, display, special));
                    item.count += stack.getCount();
                    totalItems += stack.getCount();
                    perBox.merge(key, stack.getCount(), Integer::sum);
                }
                Instant observed = memory.realTimestamp();
                for (var line : perBox.entrySet()) stock.get(line.getKey()).boxes.add(new Box(dimension, pos, line.getValue(), observed));
            }
        }
        // Index buyer offers once, avoiding nested full-database scans on each keystroke/frame.
        Map<String, List<EarthMarketClient.DisplayQuote>> buyers = new HashMap<>();
        for (var q : EarthMarketClient.displayQuotes()) {
            if (!"buy".equalsIgnoreCase(q.type()) || q.unitPrice() <= 0 || q.item() == null) continue;
            if (MarketScreen.stockClass(q) == 3) continue;
            // Never treat a special enchant/potion/disc variant as an ordinary item.
            if (q.variantKey() != null && !q.variantKey().isBlank()) continue;
            if (q.enchantments() != null && !q.enchantments().isBlank()) continue;
            buyers.computeIfAbsent(normalize(q.item()), k -> new ArrayList<>()).add(q);
        }
        List<Offer> result = new ArrayList<>();
        for (MutableStock s : stock.values()) {
            if (s.special) continue; // uncertain NBT/component variants deliberately excluded
            String simpleId = normalize(s.id);
            // Exact item ID matching; never substring-match unrelated goods.
            var quotes = buyers.getOrDefault(simpleId, List.of());
            if (quotes.isEmpty()) continue;
            EarthMarketClient.DisplayQuote best = null;
            int possible = 0;
            for (var q : quotes) {
                int candidate = Math.min(s.count, q.available() == null ? s.count : Math.max(0, q.available()));
                // QuickShop is sold in complete lots (e.g. 64 items for 1G).
                int lot = Math.max(1, q.amount());
                candidate -= candidate % lot;
                if (candidate <= 0) continue;
                if (best == null || candidate * q.unitPrice() > possible * best.unitPrice()) {
                    best = q;
                    possible = candidate;
                }
            }
            if (best != null) result.add(new Offer(s.id, s.name, s.count, best.unitPrice(), possible,
                best.available() == null, best, List.copyOf(s.boxes)));
        }
        result.sort(Comparator.comparingDouble(Offer::expectedGold).reversed());
        return new Snapshot(List.copyOf(result), totalBoxes, totalItems,
            "All Chest Tracker memories are historical snapshots; locations and quantities may be stale.");
    }
    private static final class MutableStock {
        final String id, name;
        final boolean special;
        int count;
        final List<Box> boxes = new ArrayList<>();
        MutableStock(String id, String name, boolean special) { this.id = id; this.name = name; this.special = special; }
    }
    private static String normalize(String id) {
        return id.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace('-', '_').trim();
    }
    private static int comparePos(BlockPos a, BlockPos b) {
        int c = Integer.compare(a.getX(), b.getX());
        if (c != 0) return c;
        c = Integer.compare(a.getY(), b.getY());
        return c != 0 ? c : Integer.compare(a.getZ(), b.getZ());
    }
}
