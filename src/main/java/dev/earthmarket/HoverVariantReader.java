package dev.earthmarket;

import net.minecraft.network.chat.Component;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Best-effort decoder of QuickShop's SHOW_ITEM hover preview.
 *  Reflection keeps the reader isolated from name changes in Minecraft's
 *  versioned chat event classes. Failures never interrupt chat processing. */
public final class HoverVariantReader {
    private HoverVariantReader() {}
    public record Identity(String key, String label) {}

    public static Identity fromMessage(Component message) {
        if (message == null) return null;
        return inspect(message, 0);
    }
    private static Identity inspect(Component part, int depth) {
        if (depth > 24) return null;
        try {
            Object style = part.getStyle();
            Object hover = call(style, "getHoverEvent");
            Identity found = fromHover(hover);
            if (found != null) return found;
            for (Component child : part.getSiblings()) {
                found = inspect(child, depth + 1);
                if (found != null) return found;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }
    private static Identity fromHover(Object hover) {
        if (hover == null) return null;
        try {
            String kind = hover.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            // Do not treat SHOW_TEXT as an exact item identity.
            if (!kind.contains("item")) {
                Object action = call(hover, "getAction");
                if (action == null || !action.toString().toLowerCase(Locale.ROOT).contains("show_item")) return null;
            }
            Object stack = first(hover, "item", "getItemStack", "asItemStack", "value");
            if (stack == null) {
                // Legacy HoverEvent#getValue(Action.SHOW_ITEM) is intentionally not
                // parsed as a fabricated identity.
                return null;
            }
            if (stack instanceof Optional<?> optional) stack = optional.orElse(null);
            if (stack == null || !stack.getClass().getSimpleName().contains("ItemStack")) return null;
            Object item = call(stack, "getItem");
            if (item == null) return null;
            String itemId = itemId(item);
            if (itemId == null) return null;
            Object patch = call(stack, "getComponentsPatch");
            if (patch == null) patch = call(stack, "getComponents");
            String data = patch == null ? "" : String.valueOf(patch);
            // Ignore number of items in preview; only identity/components matter.
            String key = itemId + (data.isBlank() || data.equals("{}") ? "" : "#" + digest(data));
            Object name = call(stack, "getHoverName");
            String label = name instanceof Component c ? c.getString() : itemId.substring(itemId.indexOf(':')+1);
            if (label.length() > 80) label = label.substring(0, 80);
            return new Identity(key, label);
        } catch (RuntimeException ignored) { return null; }
    }
    private static String itemId(Object item) {
        try {
            Class<?> cls = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Object registry = cls.getField("ITEM").get(null);
            for (Method method : registry.getClass().getMethods()) {
                if (method.getName().equals("getKey") && method.getParameterCount() == 1) {
                    try {
                        Object id = method.invoke(registry, item);
                        if (id != null && id.toString().contains(":")) return id.toString();
                    } catch (IllegalArgumentException ignored) { }
                }
            }
        } catch (ReflectiveOperationException | SecurityException ignored) { }
        return null;
    }
    private static Object first(Object target, String... methods) {
        for (String m : methods) {
            Object value = call(target, m);
            if (value != null) return value;
        }
        return null;
    }
    private static Object call(Object target, String method) {
        if (target == null) return null;
        try { return target.getClass().getMethod(method).invoke(target); }
        catch (ReflectiveOperationException | SecurityException | IllegalArgumentException ignored) { return null; }
    }
    private static String digest(String raw) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) { return Integer.toHexString(raw.hashCode()); }
    }
}
