package dev.earthmarket;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** Toggle a wishlist entry for the actual aimed frame, with position fallback. */
public final class QuickHeadFavorite {
    private QuickHeadFavorite() {}

    public static void toggleAimed(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null || !EarthMarketClient.isEarthMc(mc)) return;
        ItemFrame frame = aimedFrame(mc);
        if (frame == null) {
            message(mc,"Aim at a head item frame within 6 blocks, then use /emprice favhead.");
            return;
        }
        HeadFrameScanner.Head selected = null;
        for (HeadFrameScanner.Head head : HeadFrameScanner.nearby(mc)) {
            if (head.position().equals(frame.blockPosition())) {
                selected = head;
                break;
            }
        }
        if (selected == null) {
            message(mc,"The targeted item frame does not contain a detectable player head.");
            return;
        }
        String dimension=mc.level.dimension().toString();
        String identity=HeadFavorites.identityFingerprint(selected.fingerprint(),dimension,selected.position());
        HeadCatalogue.SavedHead stored=HeadCatalogue.get(dimension,selected.position());
        String town=stored==null?null:stored.town;
        String nation=stored==null?null:stored.nation;
        boolean wasFavorite = HeadFavorites.contains(selected.displayName(),identity);
        HeadFavorites.toggle(selected.displayName(),identity,dimension,selected.position(),town,nation);
        message(mc,(wasFavorite?"Removed favorite: ":"Favorited: ")+selected.displayName()
            +" @ "+selected.position().toShortString());
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
