package dev.earthmarket;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** All controls are client-side; viewing a quote never performs a shop transaction. */
public final class MarketScreen extends Screen {
    private static final int PAGE_SIZE = 8;
    private static final int PANEL_W = 555;
    private static final int PANEL_H = 326;
    private EditBox search;
    private String filter = "ALL";
    private boolean ascending = true;
    private int page = 0;
    private List<EarthMarketClient.DisplayQuote> visible = List.of();
    private Button previous;
    private Button next;
    private Button direction;
    private Button type;
    private final EarthMarketClient.DisplayQuote[] rowQuotes = new EarthMarketClient.DisplayQuote[PAGE_SIZE];
    private final Button[] townButtons = new Button[PAGE_SIZE];
    private final Button[] nationButtons = new Button[PAGE_SIZE];

    public MarketScreen() {
        super(Component.literal("EarthMC Market"));
    }

    @Override
    protected void init() {
        int left = Math.max(6, (this.width - PANEL_W) / 2);
        int top = Math.max(6, (this.height - PANEL_H) / 2);
        search = new EditBox(this.font, left + 6, top + 25, 340, 20, Component.literal("Search item, town, nation, owner"));
        search.setHint(Component.literal("Search item / town / nation"));
        search.setResponder(s -> { page = 0; refresh(); });
        addRenderableWidget(search);
        type = addRenderableWidget(Button.builder(Component.literal("ALL"), b -> {
            filter = filter.equals("ALL") ? "SELL" : filter.equals("SELL") ? "BUY" : "ALL";
            type.setMessage(Component.literal(filter));
            page = 0; refresh();
        }).bounds(left + 353, top + 25, 65, 20).build());
        direction = addRenderableWidget(Button.builder(Component.literal("Price ^"), b -> {
            ascending = !ascending;
            direction.setMessage(Component.literal(ascending ? "Price ^" : "Price v"));
            page = 0; refresh();
        }).bounds(left + 426, top + 25, 122, 20).build());
        previous = addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> { page--; refresh(); })
            .bounds(left + 6, top + 297, 80, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("Next >"), b -> { page++; refresh(); })
            .bounds(left + 467, top + 297, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(left + 240, top + 297, 75, 20).build());
        for (int i = 0; i < PAGE_SIZE; i++) {
            final int row = i;
            int y = top + 70 + i * 25;
            townButtons[i] = addRenderableWidget(Button.builder(Component.literal("T"), b -> openTravel(row, true))
                .bounds(left + 508, y, 18, 20).build());
            nationButtons[i] = addRenderableWidget(Button.builder(Component.literal("N"), b -> openTravel(row, false))
                .bounds(left + 529, y, 18, 20).build());
        }
        refresh();
    }

    /** Only prefills the command. The player presses Enter to send it. */
    private void openTravel(int row, boolean townMode) {
        EarthMarketClient.DisplayQuote q = rowQuotes[row];
        if (q == null) return;
        String destination = townMode ? safe(q.town()).strip() : safe(q.nation()).strip();
        if (destination.isBlank() || destination.equalsIgnoreCase("unknown") || destination.startsWith("(")) return;
        // Disallow control characters and chat-injection whitespace in external API names.
        if (destination.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))) return;
        String command = (townMode ? "/t spawn " : "/n spawn ") + destination;
        this.minecraft.gui.setScreen(new ChatScreen(command, false));
    }

    private void refresh() {
        if (search == null) return;
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        List<EarthMarketClient.DisplayQuote> data = new ArrayList<>();
        for (var q : EarthMarketClient.displayQuotes()) {
            if (!filter.equals("ALL") && !filter.equalsIgnoreCase(q.type())) continue;
            String haystack = (safe(q.item()) + " " + safe(q.shop()) + " " + safe(q.town()) + " " + safe(q.nation()))
                .toLowerCase(Locale.ROOT);
            if (!haystack.contains(query)) continue;
            data.add(q);
        }
        Comparator<EarthMarketClient.DisplayQuote> cmp = Comparator.comparingDouble(EarthMarketClient.DisplayQuote::unitPrice);
        if (!ascending) cmp = cmp.reversed();
        data.sort(cmp.thenComparing(q -> safe(q.item())));
        this.visible = data;
        int pages = Math.max(1, (data.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        if (previous != null) previous.active = page > 0;
        if (next != null) next.active = page + 1 < pages;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int rowIndex = page * PAGE_SIZE + i;
            EarthMarketClient.DisplayQuote q = rowIndex < visible.size() ? visible.get(rowIndex) : null;
            rowQuotes[i] = q;
            if (townButtons[i] != null) {
                townButtons[i].visible = q != null;
                townButtons[i].active = q != null && validDestination(q.town());
            }
            if (nationButtons[i] != null) {
                nationButtons[i].visible = q != null;
                nationButtons[i].active = q != null && validDestination(q.nation());
            }
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static boolean validDestination(String name) {
        String value = safe(name).strip();
        return !value.isEmpty() && !value.equalsIgnoreCase("unknown") && !value.startsWith("(")
            && value.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c));
    }
    private static String shortText(String s, int max) {
        s = safe(s);
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String originalPrice(EarthMarketClient.DisplayQuote q) {
        String price = String.format(Locale.ROOT, "%.4f", q.bundlePrice()).replaceAll("0+$", "").replaceAll("\\.$", "");
        // 1G / 64 items = 64 items per G; retains original batch information.
        return price + "G / " + q.amount() + "x";
    }

    private static String stockLabel(EarthMarketClient.DisplayQuote q) {
        if (q.available() == null) return "stock ? (sign)";
        if ("out-of-stock".equals(q.status())) return "OUT OF STOCK";
        if ("full".equals(q.status())) return "BUY FULL";
        return ("BUY".equalsIgnoreCase(q.type()) ? "space " : "stock ") + q.available();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        refresh();
        int left = Math.max(6, (this.width - PANEL_W) / 2);
        int top = Math.max(6, (this.height - PANEL_H) / 2);
        graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xEA101821);
        graphics.outline(left, top, PANEL_W, PANEL_H, 0xFF658AA0);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(this.font, "EarthMC Market - prices and stock (observed snapshots)", left + 6, top + 8, 0xFFFFFFFF, true);
        graphics.text(this.font, "Item / owner", left + 7, top + 54, 0xFF9EC7E2, false);
        graphics.text(this.font, "Original price", left + 164, top + 54, 0xFF9EC7E2, false);
        graphics.text(this.font, "Unit G", left + 286, top + 54, 0xFF9EC7E2, false);
        graphics.text(this.font, "Town / Nation | T/N", left + 390, top + 54, 0xFF9EC7E2, false);
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < visible.size(); i++) {
            var q = visible.get(start + i);
            int y = top + 72 + i * 25;
            graphics.fill(left + 4, y - 2, left + PANEL_W - 4, y + 21, i % 2 == 0 ? 0x66334455 : 0x44243341);
            boolean buying = "BUY".equalsIgnoreCase(q.type());
            int fg = buying ? 0xFF78DEA0 : 0xFFFFD17D;
            graphics.text(this.font, (buying ? "B " : "S ") + shortText(q.item(), 20), left + 7, y, fg, false);
            // The original per-trade amount/price is displayed separately from the normalized unit price.
            graphics.text(this.font, originalPrice(q), left + 164, y, 0xFFFFFFFF, false);
            graphics.text(this.font, String.format(Locale.ROOT, "%.4f", q.unitPrice()), left + 286, y, 0xFFFFFFFF, false);
            graphics.text(this.font, shortText(safe(q.town()).isBlank() ? "(unknown)" : q.town(), 13), left + 390, y, 0xFFB8D9F8, false);
            // A sign alone never reveals real stock: show unknown, not zero.
            graphics.text(this.font, shortText(q.shop(), 15) + "  " + stockLabel(q), left + 17, y + 11, 0xFFB8BEC7, false);
            graphics.text(this.font, shortText(q.nation(), 13), left + 390, y + 11, 0xFF95A8B5, false);
        }
        int pages = Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        graphics.text(this.font, "Records: " + visible.size() + "   Page " + (page + 1) + "/" + pages,
            left + 6, top + 278, 0xFFE0E4EA, false);
        graphics.text(this.font, "Stock ? = unverified | T: town spawn | N: nation spawn (Enter to confirm)",
            left + 6, top + 288, 0xFFFFBD7A, false);
    }

    @Override public void onClose() { this.minecraft.gui.setScreen(null); }
}
