package dev.earthmarket;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Read-only: inspect item frames already synchronized to the client. No interaction packets. */
public final class HeadFrameScanner {
    private HeadFrameScanner() {}
    public record Head(BlockPos position, String displayName, String fingerprint, boolean specific) {}

    public static List<Head> nearby(Minecraft mc) {
        List<Head> result = new ArrayList<>();
        if (mc == null || mc.level == null || mc.player == null) return result;
        // Only entities currently loaded by the client can be inspected.
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemFrame frame)) continue;
            if (frame.distanceToSqr(mc.player) > 48.0 * 48.0) continue;
            ItemStack stack = frame.getItem();
            if (stack == null || stack.isEmpty() || !stack.is(Items.PLAYER_HEAD)) continue;
            BlockPos position = frame.blockPosition();
            String visibleName = stack.getHoverName().getString().strip();
            boolean hasName = stack.get(DataComponents.CUSTOM_NAME) != null;
            Object profile = stack.get(DataComponents.PROFILE);
            String profileName = profileName(profile);
            if ((!hasName || visibleName.isBlank() || visibleName.equalsIgnoreCase("Player Head") || visibleName.equals("玩家头颅"))
                && profileName != null) visibleName = profileName + "'s Head";
            if (visibleName.isBlank()) visibleName = "Player Head";
            // Component serialization is a fallback discriminator; not claimed to be a stable Minecraft ID.
            String profileIdentity = profile == null ? "" : profile.toString();
            String stableName = profileName == null ? "" : profileName;
            boolean specific = hasName || profile != null;
            String fingerprint = specific ? "head:" + Integer.toHexString((stableName + "|" + profileIdentity + "|" + visibleName).hashCode()) : "";
            result.add(new Head(position, visibleName, fingerprint, specific));
        }
        return result;
    }

    private static String profileName(Object profile) {
        if (profile == null) return null;
        for (String method : new String[] {"name", "getName"}) {
            try {
                Method m = profile.getClass().getMethod(method);
                Object value = m.invoke(profile);
                if (value instanceof java.util.Optional<?> optional) value = optional.orElse(null);
                if (value instanceof String name && !name.isBlank()) return name;
            } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }
}
