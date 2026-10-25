package dev.earthmarket;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Confirmed Go-command tracking: highlight once the player reaches the loaded shop vicinity. */
public final class PendingShopTravel {
    private static final long MAX_WAIT_MS = 180_000L;
    private static final double ARRIVAL_RADIUS_SQUARED = 48.0 * 48.0;
    private static EarthMarketClient.DisplayQuote pending;
    private static String pendingCommand;
    private static boolean awaitingSubmit;
    private static long armedAt, sentAt;
    private static String originDimension;
    private static double originX, originY, originZ;
    private static boolean sawTravel;
    private static boolean sawWorldUnload;
    private static int tickCounter;

    private PendingShopTravel() {}

    public static void register() {
        ClientSendMessageEvents.COMMAND.register(PendingShopTravel::onCommand);
    }

    public static void arm(EarthMarketClient.DisplayQuote quote, String command) {
        clear();
        pending = quote;
        pendingCommand = normalize(command);
        armedAt = System.currentTimeMillis();
        awaitingSubmit = true;
    }

    private static String normalize(String command) {
        return command == null ? "" : command.strip().replaceFirst("^/", "");
    }

    private static void onCommand(String command) {
        if (pending == null || !awaitingSubmit) return;
        if (!pendingCommand.equalsIgnoreCase(normalize(command))) {
            clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        sentAt = System.currentTimeMillis();
        awaitingSubmit = false;
        sawTravel = false;
        sawWorldUnload = false;
        tickCounter = 0;
        if (mc.level != null && mc.player != null) {
            originDimension = mc.level.dimension().toString();
            originX = mc.player.getX(); originY = mc.player.getY(); originZ = mc.player.getZ();
        }
    }

    public static void tick(Minecraft mc) {
        if (pending == null) return;
        long now = System.currentTimeMillis();
        if (awaitingSubmit) {
            if (now - armedAt > 90_000L) clear();
            return;
        }
        if (now - sentAt > MAX_WAIT_MS) {
            notify(mc, "Shop tracking expired; use H in GUI to highlight manually.");
            clear(); return;
        }
        if (mc.level == null || mc.player == null) {
            sawWorldUnload = true;
            return;
        }
        if (++tickCounter % 5 != 0) return; // cheap location checks, ~4 per second
        String dimension = mc.level.dimension().toString();
        if (originDimension != null && !originDimension.equals(dimension)) sawTravel = true;
        double dx = mc.player.getX() - originX;
        double dy = mc.player.getY() - originY;
        double dz = mc.player.getZ() - originZ;
        if (dx * dx + dy * dy + dz * dz > 12.0 * 12.0) sawTravel = true;
        if (sawWorldUnload) sawTravel = true;
        if (!sawTravel) return; // don't highlight at the departure location
        EarthMarketClient.DisplayQuote q = pending;
        if (q.dimension() != null && !q.dimension().equals(dimension)) return;
        BlockPos pos = new BlockPos(q.x(), q.y(), q.z());
        if (!mc.level.hasChunkAt(pos)) return;
        if (mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > ARRIVAL_RADIUS_SQUARED) return;
        String result = ShopLocator.start(q);
        if (result.startsWith("Highlighting") || result.startsWith("Multiple/no")) {
            notify(mc, "Target shop nearby: " + result);
            clear();
        } else if (result.contains("No exact")) {
            notify(mc, result);
            clear();
        }
    }

    private static void notify(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[EM Market] " + msg));
    }

    public static void cancel() { clear(); }

    private static void clear() {
        pending = null; pendingCommand = null; awaitingSubmit = false;
        armedAt = 0; sentAt = 0; originDimension = null; sawTravel = false;
        sawWorldUnload = false; tickCounter = 0;
    }
}
