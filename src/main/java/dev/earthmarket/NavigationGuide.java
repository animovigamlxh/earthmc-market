package dev.earthmarket;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;

/** Lightweight on-screen direction guide for a selected EarthMC shop. */
public final class NavigationGuide {
    private static EarthMarketClient.DisplayQuote target;
    private static long expiresAt;
    private static final long MAX_LIFETIME_MS = 180_000L;
    private NavigationGuide() {}

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("earthmcmarket", "shop_navigation"), NavigationGuide::render);
    }

    public static void setTarget(EarthMarketClient.DisplayQuote quote) {
        if (quote == null) return;
        if (!"earthmc-sign".equals(quote.source()) && !"sign-block".equals(quote.accuracy())
                && !"target-block".equals(quote.accuracy())) return;
        target = quote;
        expiresAt = System.currentTimeMillis() + MAX_LIFETIME_MS;
    }

    public static void clear() { target = null; expiresAt = 0L; }
    public static boolean active() { return target != null && System.currentTimeMillis() < expiresAt; }

    public static void tick(Minecraft mc) {
        if (target == null) return;
        if (System.currentTimeMillis() >= expiresAt || mc == null || mc.player == null || mc.level == null
                || !EarthMarketClient.isEarthMc(mc)) {
            clear(); return;
        }
        String dimension = mc.level.dimension().toString();
        if (target.dimension() != null && !target.dimension().equals(dimension)) return;
        BlockPos sign = new BlockPos(target.x(), target.y(), target.z());
        double dx = mc.player.getX() - (sign.getX() + .5);
        double dy = mc.player.getY() - (sign.getY() + .5);
        double dz = mc.player.getZ() - (sign.getZ() + .5);
        if (dx * dx + dy * dy + dz * dz <= 48.0 * 48.0) {
            String result = ShopLocator.start(target);
            if (result.startsWith("Highlighting") || result.startsWith("Multiple/no")) {
                mc.player.sendSystemMessage(Component.literal("[EM Market] " + result));
                clear();
            }
        }
    }

    private static void render(net.minecraft.client.gui.GuiGraphicsExtractor g,
                               net.minecraft.client.DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        EarthMarketClient.DisplayQuote q = target;
        if (q == null || System.currentTimeMillis() >= expiresAt || mc.player == null || mc.level == null
                || !EarthMarketClient.isEarthMc(mc) || mc.screen != null) return;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int cx = sw / 2;
        if (q.dimension() != null && !q.dimension().equals(mc.level.dimension().toString())) {
            drawPanel(g, cx - 126, 12, 252, 42);
            g.text(mc.font, "Shop target is in another dimension", cx - 118, 20, 0xFFFFD27A, true);
            g.text(mc.font, coords(q), cx - 118, 34, 0xFFE7EDF4, false);
            return;
        }
        double tx = q.x() + .5 - mc.player.getX();
        double tz = q.z() + .5 - mc.player.getZ();
        double distance = Math.sqrt(tx * tx + tz * tz);
        double bearing = Math.toDegrees(Math.atan2(-tx, tz));
        double relative = wrapDegrees(bearing - mc.player.getYRot());
        String arrow = arrowFor(relative);
        String distanceText = distance >= 1000 ? String.format(java.util.Locale.ROOT, "%.1f km", distance / 1000.0)
                : String.format(java.util.Locale.ROOT, "%.0f m", distance);
        String title = arrow + "  " + itemName(q) + "  " + distanceText;
        int boxW = Math.min(360, Math.max(220, mc.font.width(title) + 24));
        int left = Math.max(4, Math.min(sw - boxW - 4, cx - boxW / 2));
        drawPanel(g, left, 12, boxW, 43);
        g.text(mc.font, title, left + 9, 19, 0xFF6FFFD0, true);
        g.text(mc.font, coords(q) + "  |  move toward arrow", left + 9, 33, 0xFFE7EDF4, false);
        // Secondary compass cue placed near the crosshair for quick orientation.
        int cueX = cx - mc.font.width(arrow) / 2;
        int cueY = Math.max(35, sh / 2 - 34);
        g.text(mc.font, arrow, cueX, cueY, 0xFF62FFD0, true);
    }

    private static void drawPanel(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xCC101820);
        g.outline(x, y, w, h, 0xFF54DDB8);
    }
    private static String coords(EarthMarketClient.DisplayQuote q) { return "Sign: " + q.x() + ", " + q.y() + ", " + q.z(); }
    private static String itemName(EarthMarketClient.DisplayQuote q) {
        String s = EarthMarketClient.variantLabel(q);
        if (s == null || s.isBlank()) s = q.item() == null ? "Shop" : q.item();
        return s.length() > 34 ? s.substring(0, 31) + "..." : s;
    }
    private static double wrapDegrees(double angle) {
        angle %= 360.0;
        if (angle >= 180.0) angle -= 360.0;
        if (angle < -180.0) angle += 360.0;
        return angle;
    }
    private static String arrowFor(double angle) {
        String[] arrows = {"↑", "↖", "←", "↙", "↓", "↘", "→", "↗"};
        int sector = (int) Math.round(angle / 45.0);
        return arrows[Math.floorMod(sector, 8)];
    }
}
