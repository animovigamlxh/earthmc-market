package dev.earthmarket;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Client-only flashing outline made of short-lived particles, never edits the world. */
public final class ShopLocator {
    private static BlockPos target;
    private static String dimension;
    private static int ticksLeft;
    private static int age;

    private ShopLocator() {}

    public static String start(EarthMarketClient.DisplayQuote quote) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return "Join a world first.";
        if (!"earthmc-sign".equals(quote.source()) && !"target-block".equals(quote.accuracy())) {
            return "No exact sign/block position saved. Rescan this shop nearby.";
        }
        if (quote.dimension() == null || !quote.dimension().equals(mc.level.dimension().toString())) {
            return "Shop is in a different or unknown dimension.";
        }
        BlockPos sign = new BlockPos(quote.x(), quote.y(), quote.z());
        if (!mc.level.hasChunkAt(sign)) return "Shop chunk is not loaded. Get closer first.";
        if (mc.player.distanceToSqr(sign.getX() + .5, sign.getY() + .5, sign.getZ() + .5) > 48 * 48) {
            return "Shop is farther than 48 blocks. Travel closer first.";
        }
        BlockPos found = null;
        int best = Integer.MAX_VALUE, tied = 0;
        for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos p = sign.offset(dx, dy, dz);
            if (!mc.level.hasChunkAt(p)) continue;
            BlockState state = mc.level.getBlockState(p);
            if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST) && !state.is(Blocks.BARREL)) continue;
            int d = dx * dx + dy * dy + dz * dz;
            if (d < best) { best = d; found = p; tied = 1; }
            else if (d == best) tied++;
        }
        if (found == null) return "No loaded chest/barrel found near the saved shop sign.";
        if (tied != 1) return "Multiple equally close containers: cannot reliably identify this shop.";
        target = found;
        dimension = mc.level.dimension().toString();
        ticksLeft = 120;
        age = 0;
        return "Highlighting container at " + found.toShortString() + " for 6 seconds.";
    }

    public static void tick(Minecraft mc) {
        if (target == null) return;
        if (mc.level == null || mc.player == null || !mc.level.dimension().toString().equals(dimension)
            || !mc.level.hasChunkAt(target) || ticksLeft-- <= 0) {
            target = null;
            return;
        }
        if ((age++ % 8) >= 3) return; // blinking, not a continuous glow
        if (age % 2 != 0) return;
        // 12 points on box edges; client-side particles only, no server messages.
        for (int i = 0; i <= 4; i++) {
            double t = i / 4.0;
            particle(mc, target.getX() + t, target.getY() + .02, target.getZ() + .02);
            particle(mc, target.getX() + t, target.getY() + 1.02, target.getZ() + 1.02);
            particle(mc, target.getX() + .02, target.getY() + t, target.getZ() + .02);
            particle(mc, target.getX() + 1.02, target.getY() + t, target.getZ() + 1.02);
        }
    }

    private static void particle(Minecraft mc, double x, double y, double z) {
        mc.level.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0, 0);
    }
}
