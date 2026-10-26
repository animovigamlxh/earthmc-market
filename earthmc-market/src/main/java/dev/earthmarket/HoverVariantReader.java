package dev.earthmarket;

import net.minecraft.network.chat.Component;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;

/** Best-effort decoder of QuickShop's SHOW_ITEM hover preview.
 *  Reflection keeps the reader isolated from name changes in Minecraft's
 *  versioned chat event classes. Failures never interrupt chat processing. */
public final class HoverVariantReader {
    private HoverVariantReader() {}
    private static final Pattern ITEM_PATH = Pattern.compile("(?:minecraft:item\\s*/\\s*)?((?:[a-z0-9_.-]+:)?[a-z0-9_./-]+)");
    private static final Pattern RESOURCE_KEY_ITEM = Pattern.compile("(?:[a-z0-9_.-]+:)?item\\s*/\\s*((?:[a-z0-9_.-]+:)?[a-z0-9_./-]+)");
    private static final Pattern NAMED_ITEM = Pattern.compile("(?<![a-z0-9_])((?:[a-z0-9_.-]+:)?(?:music_disc_[a-z0-9_]+|enchanted_book|potion|splash_potion|lingering_potion|tipped_arrow))");

    /** Extract a namespaced registry ID instead of persisting Java Holder/Reference.toString(). */
    static String canonicalItemId(Object reference) {
        if (reference == null) return null;
        Object key = first(reference, "unwrapKey", "getKey", "key", "location");
        if (key instanceof Optional<?> opt) key = opt.orElse(null);
        Object location = key == null ? null : first(key, "location", "getLocation");
        if (location != null) {
            String id = normalizeId(location.toString());
            if (id != null) return id;
        }
        String raw = String.valueOf(reference).toLowerCase(Locale.ROOT);
        Matcher m = RESOURCE_KEY_ITEM.matcher(raw);
        if (m.find()) return normalizeId(m.group(1));
        m = NAMED_ITEM.matcher(raw);
        if (m.find()) return normalizeId(m.group(1));
        // A ResourceLocation can stringify directly to namespace:path.
        if (raw.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return raw;
        return null;
    }
    private static String normalizeId(String raw) {
        if (raw == null) return null;
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (!value.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) return null;
        return value.contains(":") ? value : "minecraft:" + value;
    }
    public static String idFromLegacyDescription(String legacy) {
        return canonicalItemId(legacy);
    }
    public static String friendlyLabel(String itemId) {
        if (itemId == null) return "";
        String name = itemId.substring(itemId.indexOf(':') + 1);
        if (name.startsWith("music_disc_")) {
            String title = name.substring("music_disc_".length());
            return "音乐唱片 — " + title.substring(0,1).toUpperCase(Locale.ROOT) + title.substring(1).replace('_',' ');
        }
        return name.replace('_', ' ');
    }
    public static Identity upgradeLegacy(String key, String label) {
        if (key == null || !key.startsWith("template#")) return null;
        String id = idFromLegacyDescription(label);
        if (id == null) return null;
        // Legacy template hash was computed over JVM string dumps. Preserve that suffix
        // for variants where components may matter; vanilla discs have unique IDs.
        boolean disc = id.contains(":music_disc_");
        return new Identity(disc ? id : id + "#legacy:" + key.substring(9), friendlyLabel(id));
    }
    public record Identity(String key, String label) {}

    public static Identity fromMessage(Component message) {
        if (message == null) return null;
        return inspect(message, 0);
    }
    public static String describe(Component message) {
        if (message == null) return "none";
        StringBuilder out = new StringBuilder();
        describePart(message, 0, out);
        return out.length() == 0 ? "none" : out.substring(0, Math.min(out.length(), 180));
    }
    private static void describePart(Component part, int depth, StringBuilder out) {
        if (depth > 24) return;
        Object hover = part.getStyle().getHoverEvent();
        if (hover != null) {
            if (out.length() > 0) out.append("; ");
            out.append(hover.getClass().getSimpleName());
            Object val = call(hover, "item");
            if (val != null) out.append(" payload=").append(val.getClass().getSimpleName());
        }
        for (Component child : part.getSiblings()) describePart(child, depth+1, out);
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
            if (stack == null) return null;
            // 26.2 SHOW_ITEM uses ItemStackTemplate, not ItemStack.
            if (stack.getClass().getSimpleName().contains("ItemStackTemplate")) {
                Object identity = first(stack, "item", "getItem");
                String id = canonicalItemId(identity);
                if (id == null) return null; // Unknown ID is NOT an exact match.
                Object components = first(stack, "components", "getComponents", "getComponentsPatch");
                String payload = components == null ? "" : String.valueOf(components);
                // Never hash the entire template dump: it includes Java Holder representations.
                // For complex items retain a digest of their data components, excluding count.
                String normalized = payload.strip();
                boolean plain = normalized.isEmpty() || normalized.equals("{}") || normalized.equals("[]") || normalized.equals("DataComponentPatch{}");
                String key = id + (plain ? "" : "#components:" + digest(normalized));
                return new Identity(key, friendlyLabel(id));
            }
            if (!stack.getClass().getSimpleName().contains("ItemStack")) return null;
            Object item = call(stack, "getItem");
            if (item == null) return null;
            String itemId = canonicalItemId(item);
            if (itemId == null) itemId = itemId(item);
            if (itemId == null) return null;
            Object patch = call(stack, "getComponentsPatch");
            if (patch == null) patch = call(stack, "getComponents");
            String data = patch == null ? "" : String.valueOf(patch);
            // Ignore number of items in preview; only identity/components matter.
            String key = itemId + (data.isBlank() || data.equals("{}") ? "" : "#" + digest(data));
            Object name = call(stack, "getHoverName");
            String label = name instanceof Component c ? c.getString() : friendlyLabel(itemId);
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
