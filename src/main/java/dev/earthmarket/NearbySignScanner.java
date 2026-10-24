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
    private static final int RADIUS = 32, VERTICAL = 12, POSITIONS_PER_TICK = 2400;
    private static final long AUTO_INTERVAL_MS = 30_000;
    private final BiConsumer<BlockPos, Found> onFound;
    private boolean auto = false, active = false;
    private int centerX, centerY, centerZ, cursor, candidates, recognized;
    private long lastAutomaticAt;
    private String dimensionKey = "";

    public record Found(SignShopParser.Result quote, boolean redOwner, String side) {}
    public NearbySignScanner(BiConsumer<BlockPos, Found> onFound) { this.onFound = onFound; }
    public boolean autoEnabled() { return auto; }
    public void setAuto(boolean enabled) { this.auto = enabled; }
    public boolean active() { return active; }

    public boolean start(Minecraft mc) {
        if (mc.player == null || mc.level == null) return false;
        centerX = mc.player.getBlockX(); centerY = mc.player.getBlockY(); centerZ = mc.player.getBlockZ();
        dimensionKey = mc.level.dimension().toString();
        cursor = 0; candidates = 0; recognized = 0; active = true;
        lastAutomaticAt = System.currentTimeMillis();
        return true;
    }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) { active = false; return; }
        if (active && !dimensionKey.equals(mc.level.dimension().toString())) active = false;
        if (!active) {
            if (auto && System.currentTimeMillis() - lastAutomaticAt >= AUTO_INTERVAL_MS) start(mc);
            return;
        }
        int width = RADIUS * 2 + 1, height = VERTICAL * 2 + 1;
        int total = width * width * height;
        int processed = 0;
        while (cursor < total && processed++ < POSITIONS_PER_TICK) {
            int at = cursor++;
            int x = centerX + at % width - RADIUS;
            int z = centerZ + (at / width) % width - RADIUS;
            int y = centerY + at / (width * width) - VERTICAL;
            BlockPos pos = new BlockPos(x, y, z);
            if (!mc.level.hasChunkAt(pos)) continue;
            BlockEntity entity = mc.level.getBlockEntity(pos);
            if (!(entity instanceof SignBlockEntity sign)) continue;
            candidates++;
            scanSide(pos, sign.getFrontText(), "front");
            scanSide(pos, sign.getBackText(), "back");
        }
        if (cursor >= total) {
            active = false;
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[EM Market] Sign scan finished: " + candidates + " signs, " + recognized + " recognized. Price/stock may be stale."));
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
