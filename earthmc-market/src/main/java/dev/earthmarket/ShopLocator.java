package dev.earthmarket;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Client-only flashing outline made of short-lived particles, never edits the world. */
public final class ShopLocator {
    private static BlockPos target;
    private static String dimension;
    private static int ticksLeft;
    private static int age;
    private static boolean markerIsSign;

    private ShopLocator() {}

    public static String start(EarthMarketClient.DisplayQuote quote) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return "Join a world first.";
        if (!"earthmc-sign".equals(quote.source()) && !"sign-block".equals(quote.accuracy())) {
            return "No exact sign/block position saved. Rescan this shop nearby.";
        }
        if (quote.dimension() != null && !quote.dimension().equals(mc.level.dimension().toString())) {
            return "Shop is in a different dimension.";
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
        // An exact sign is a valid fallback marker. Do not guess among multiple chests.
        markerIsSign = found == null || tied != 1;
        target = markerIsSign ? sign : found;
        dimension = mc.level.dimension().toString();
        ticksLeft = 120;
        age = 0;
        NavigationGuide.clear();
        return markerIsSign
            ? "Multiple/no matching containers; outlining SHOP SIGN at " + sign.toShortString() + " for 6 seconds."
            : "Highlighting container at " + found.toShortString() + " for 6 seconds.";
    }

    /** Always report the saved location; it is a sign/block position, not necessarily a chest. */
    public static String coordinates(EarthMarketClient.DisplayQuote quote) {
        if (quote == null) return "No shop selected.";
        if (!"earthmc-sign".equals(quote.source()) && !"sign-block".equals(quote.accuracy())
            && !"target-block".equals(quote.accuracy())) {
            return "This historical record has no confirmed shop coordinates; rescan a sign nearby.";
        }
        String dim = quote.dimension() == null ? "dimension unknown" : quote.dimension();
        BlockPos sign = new BlockPos(quote.x(), quote.y(), quote.z());
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null
            && (quote.dimension() == null || quote.dimension().equals(mc.level.dimension().toString()))
            && mc.level.hasChunkAt(sign)) {
            BlockPos closest = null;
            int best = Integer.MAX_VALUE, ties = 0;
            for (int dx=-2; dx<=2; dx++) for (int dy=-1; dy<=1; dy++) for (int dz=-2; dz<=2; dz++) {
                BlockPos candidate = sign.offset(dx,dy,dz);
                if (!mc.level.hasChunkAt(candidate)) continue;
                BlockState state = mc.level.getBlockState(candidate);
                if (!state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST) && !state.is(Blocks.BARREL)) continue;
                int distance = dx*dx + dy*dy + dz*dz;
                if (distance < best) { best=distance; closest=candidate; ties=1; }
                else if (distance == best) ties++;
            }
            if (closest != null && ties == 1)
                return "Nearby container (estimated): " + closest.toShortString()
                    + " | shop sign: " + sign.toShortString() + " | " + dim;
        }
        return "Shop sign: " + sign.toShortString() + " | " + dim
            + " (chest coordinates not verified; move closer to load the area).";
    }

    public static void clear() { target = null; ticksLeft = 0; }

    public static void tick(Minecraft mc) {
        if (target == null) return;
        if (mc.level == null || mc.player == null || !mc.level.dimension().toString().equals(dimension)
            || !mc.level.hasChunkAt(target) || ticksLeft-- <= 0) {
            target = null;
            return;
        }
        age++;
    }

    /** Used exclusively during the render extraction phase. */
    public static BlockPos renderTarget() {
        return ticksLeft > 0 ? target : null;
    }
}
