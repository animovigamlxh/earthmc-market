package dev.earthmarket;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Toggle the product wishlist by aiming at the actual frame, never by matching a name. */
public final class QuickHeadFavorite {
    private QuickHeadFavorite() {}

    public static void toggleAimed(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null || !EarthMarketClient.isEarthMc(mc)) return;
        BlockPos selectedPos = null;
        ItemFrame frame = mc.hitResult instanceof BlockHitResult ? null : aimedFrame(mc);
        if (frame != null) selectedPos = frame.blockPosition();
        if (selectedPos == null && mc.hitResult instanceof BlockHitResult blockHit) {
            BlockPos p = blockHit.getBlockPos();
            var block = mc.level.getBlockState(p).getBlock();
            if (block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD
                    || block == Blocks.PIGLIN_HEAD || block == Blocks.PIGLIN_WALL_HEAD)
                selectedPos = p;
        }
        if (selectedPos == null) {
            message(mc,"Aim at a player head block or head item frame, then use /emprice favhead.");
            return;
        }
        String dimension = mc.level.dimension().toString();
        HeadCatalogue.SavedHead stored = HeadCatalogue.get(dimension, selectedPos);
        HeadFrameScanner.Head selected = stored == null ? null : stored.asHead();
        if (selected == null) for (HeadFrameScanner.Head head : HeadFrameScanner.nearby(mc)) {
            if (head.position().equals(selectedPos)) { selected = head; break; }
        }
        if (selected == null) {
            message(mc,"No recorded head at target: run /emprice headscan and retry after scan finishes.");
            return;
        }
        boolean wasFavorite = HeadFavorites.contains(selected.displayName(),selected.fingerprint(),dimension,selectedPos);
        HeadFavorites.toggle(selected.displayName(),selected.fingerprint(),dimension,selectedPos);
        message(mc,(wasFavorite?"Removed favorite: ":"Favorited: ")+selected.displayName()
            +" @ "+selectedPos.toShortString());
    }

    private static ItemFrame aimedFrame(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof ItemFrame frame
            && frame.distanceToSqr(mc.player) <= 6.5 * 6.5) return frame;
        Vec3 start = mc.player.getEyePosition();
        Vec3 end = start.add(mc.player.getLookAngle().scale(6.0));
        ItemFrame best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ItemFrame frame)) continue;
            AABB bounds=frame.getBoundingBox().inflate(0.08);
            var intersection=bounds.clip(start,end);
            if(intersection.isEmpty()) continue;
            double distance=start.distanceToSqr(intersection.get());
            if(distance < bestDistance) { bestDistance=distance; best=frame; }
        }
        return best;
    }

    private static void message(Minecraft mc,String msg) {
        // Not fed through the QuickShop chat parser (avoids chat-listener recursion).
        mc.player.sendSystemMessage(Component.literal("[EM Market] "+msg));
    }
}
