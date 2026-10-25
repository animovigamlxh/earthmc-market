package dev.earthmarket;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

import java.util.function.BiConsumer;

/** Incremental, read-only scanner. It does not interact with shop blocks or send packets. */
public final class NearbySignScanner {
    private static final int RADIUS = 32, VERTICAL = 12;
    private static final int CHUNKS_PER_TICK = 1;
    private static final int SIGNS_PER_TICK = 48;
    private static final long AUTO_INTERVAL_MS = 30_000;
    private final BiConsumer<BlockPos, Found> onFound;
    private boolean auto = true, active = false;
    private static final int MOVEMENT_RESCAN_BLOCKS = 24;
    private static final long MOVEMENT_RESCAN_COOLDOWN_MS = 8_000L;
    private int lastScanX, lastScanZ;
    private String lastScanDimension = "";
    private int centerX, centerY, centerZ, chunkCursor, signCursor, candidates, recognized;
    private int minChunkX, maxChunkX, minChunkZ, maxChunkZ;
    private long lastAutomaticAt;
    private String dimensionKey = "";
    private final java.util.ArrayList<java.util.Map.Entry<BlockPos, BlockEntity>> pending = new java.util.ArrayList<>();

    public record Found(SignShopParser.Result quote, boolean redOwner, String side) {}
    public NearbySignScanner(BiConsumer<BlockPos, Found> onFound) { this.onFound = onFound; }
    public boolean autoEnabled() { return auto; }
    public void setAuto(boolean enabled) { this.auto = enabled; }
    public boolean active() { return active; }

    public boolean start(Minecraft mc) {
        if (mc.player == null || mc.level == null) return false;
        centerX = mc.player.getBlockX(); centerY = mc.player.getBlockY(); centerZ = mc.player.getBlockZ();
        dimensionKey = mc.level.dimension().toString();
        lastScanX = centerX; lastScanZ = centerZ; lastScanDimension = dimensionKey;
        minChunkX = Math.floorDiv(centerX - RADIUS, 16);
        maxChunkX = Math.floorDiv(centerX + RADIUS, 16);
        minChunkZ = Math.floorDiv(centerZ - RADIUS, 16);
        maxChunkZ = Math.floorDiv(centerZ + RADIUS, 16);
        chunkCursor = 0; signCursor = 0; pending.clear();
        candidates = 0; recognized = 0; active = true;
        lastAutomaticAt = System.currentTimeMillis();
        return true;
    }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) { active = false; pending.clear(); return; }
        if (active && !dimensionKey.equals(mc.level.dimension().toString())) { active = false; pending.clear(); }
        if (!active) {
            long elapsed = System.currentTimeMillis() - lastAutomaticAt;
            int dx = mc.player.getBlockX() - lastScanX;
            int dz = mc.player.getBlockZ() - lastScanZ;
            boolean newArea = !mc.level.dimension().toString().equals(lastScanDimension)
                || (long) dx * dx + (long) dz * dz >= MOVEMENT_RESCAN_BLOCKS * MOVEMENT_RESCAN_BLOCKS;
            if (auto && (elapsed >= AUTO_INTERVAL_MS
                || (newArea && elapsed >= MOVEMENT_RESCAN_COOLDOWN_MS))) start(mc);
            return;
        }
        int chunkWidth = maxChunkX - minChunkX + 1;
        int totalChunks = chunkWidth * (maxChunkZ - minChunkZ + 1);
        int signsDone = 0, chunksDone = 0;
        while (signsDone < SIGNS_PER_TICK) {
            if (signCursor >= pending.size()) {
                pending.clear(); signCursor = 0;
                if (chunkCursor >= totalChunks || chunksDone >= CHUNKS_PER_TICK) break;
                int index = chunkCursor++;
                int cx = minChunkX + index % chunkWidth;
                int cz = minChunkZ + index / chunkWidth;
                chunksDone++;
                BlockPos check = new BlockPos(cx * 16, centerY, cz * 16);
                if (!mc.level.hasChunkAt(check)) continue;
                // Copy the map entries before processing. Never mutate chunk internals.
                for (var entry : mc.level.getChunk(cx, cz).getBlockEntities().entrySet()) {
                    // Chunk block-entity maps may contain stale/null positions during chunk updates.
                    // Snapshot values rather than retaining live Map.Entry references.
                    BlockEntity entity = entry.getValue();
                    if (!(entity instanceof SignBlockEntity)) continue;
                    BlockPos position = entry.getKey();
                    if (position == null) position = entity.getBlockPos();
                    if (position == null) continue;
                    pending.add(new java.util.AbstractMap.SimpleImmutableEntry<>(position.immutable(), entity));
                }
                continue;
            }
            var entry = pending.get(signCursor++);
            if (entry == null) continue;
            BlockPos pos = entry.getKey();
            if (pos == null) continue; // Never let malformed/stale entries crash the client.
            if (Math.abs(pos.getX() - centerX) > RADIUS || Math.abs(pos.getZ() - centerZ) > RADIUS
                || Math.abs(pos.getY() - centerY) > VERTICAL) continue;
            if (!(entry.getValue() instanceof SignBlockEntity sign)) continue;
            candidates++; signsDone++;
            scanSide(pos, sign.getFrontText(), "front");
            scanSide(pos, sign.getBackText(), "back");
        }
        if (chunkCursor >= totalChunks && signCursor >= pending.size()) {
            active = false; pending.clear();
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal(
                "[EM Market] Sign scan finished: " + candidates + " signs, " + recognized + " recognized."));
        }
    }
    private void scanSide(BlockPos pos, SignText text, String side) {
        if (text == null) return;
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) lines[i] = text.getMessage(i, false).getString();
        SignShopParser.Result parsed = SignShopParser.parse(lines);
        if (parsed == null) return;
        recognized++;
        Component owner = text.getMessage(0, false);
        String color = owner.getStyle().getColor() == null ? "" : owner.getStyle().getColor().toString();
        // Styling can live in siblings; red is advisory, not a reliable stock count.
        boolean red = color.equalsIgnoreCase("red") || color.equalsIgnoreCase("#ff5555") || color.equalsIgnoreCase("#ff0000");
        onFound.accept(pos, new Found(parsed, red, side));
    }
}
