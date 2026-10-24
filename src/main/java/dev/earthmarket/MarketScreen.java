package dev.earthmarket;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** All controls are client-side; viewing a quote never performs a shop transaction. */
public final class MarketScreen extends Screen {
    private static final int PAGE_SIZE = 8;
    private EditBox search;
    private String filter = "ALL";
    private boolean ascending = true;
    private int page = 0;
    private List<EarthMarketClient.DisplayQuote> visible = List.of();
    private Button previous;
    private Button next;
    private Button direction;
    private Button type;

    public MarketScreen() {
        super(Component.literal("EarthMC Market"));
    }

    @Override
    protected void init() {
        int left = Math.max(12, (this.width - 350) / 2);
        int top = Math.max(12, (this.height - 245) / 2);
        search = new EditBox(this.font, left + 4, top + 24, 210, 20, Component.literal("Search item, town, nation, owner"));
        search.setHint(Component.literal("Search item / town / nation"));
        search.setResponder(s -> { page = 0; refresh(); });
        addRenderableWidget(search);
        type = addRenderableWidget(Button.builder(Component.literal("ALL"), b -> {
            filter = filter.equals("ALL") ? "SELL" : filter.equals("SELL") ? "BUY" : "ALL";
            type.setMessage(Component.literal(filter));
            page = 0; refresh();
        }).bounds(left + 218, top + 24, 58, 20).build());
        direction = addRenderableWidget(Button.builder(Component.literal("Price ^"), b -> {
            ascending = !ascending;
            direction.setMessage(Component.literal(ascending ? "Price ^" : "Price v"));
            page = 0; refresh();
        }).bounds(left + 280, top + 24, 70, 20).build());
        previous = addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> { page--; refresh(); })
            .bounds(left + 4, top + 221, 72, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("Next >"), b -> { page++; refresh(); })
            .bounds(left + 274, top + 221, 72, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(left + 139, top + 221, 72, 20).build());
        refresh();
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
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String shortText(String s, int max) {
        s = safe(s);
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        refresh();
        int left = Math.max(12, (this.width - 350) / 2);
        int top = Math.max(12, (this.height - 245) / 2);
        graphics.fill(left, top, left + 354, top + 247, 0xDE101821);
        graphics.outline(left, top, 354, 247, 0xFF658AA0);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(this.font, "EarthMC Market - local observations", left + 6, top + 8, 0xFFFFFFFF, true);
        graphics.text(this.font, "Item / owner                      Unit G      Town / Nation", left + 6, top + 54, 0xFF9EC7E2, false);
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < visible.size(); i++) {
            var q = visible.get(start + i);
            int y = top + 70 + i * 18;
            graphics.fill(left + 4, y - 2, left + 349, y + 15, i % 2 == 0 ? 0x66334455 : 0x44243341);
            String item = shortText(q.item(), 16);
            String typeText = "BUY".equalsIgnoreCase(q.type()) ? "B" : "S";
            int fg = "B".equals(typeText) ? 0xFF78DEA0 : 0xFFFFD17D;
            graphics.text(this.font, typeText + " " + item, left + 7, y, fg, false);
            graphics.text(this.font, String.format(Locale.ROOT, "%.4f", q.unitPrice()), left + 139, y, 0xFFFFFFFF, false);
            String place = !safe(q.town()).isBlank() ? q.town() : "(unknown)";
            graphics.text(this.font, shortText(place, 16), left + 217, y, 0xFFB8D9F8, false);
            // Second line of the row: owner and nation; status shown when stock is blocked.
            String stock = q.available() != null && q.available() <= 0 ? " [NO STOCK]" : "";
            graphics.text(this.font, shortText(q.shop(), 16) + stock, left + 21, y + 9, 0xFFB8BEC7, false);
            graphics.text(this.font, shortText(q.nation(), 15), left + 217, y + 9, 0xFF95A8B5, false);
        }
        int pages = Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        graphics.text(this.font, "Records: " + visible.size() + "   Page " + (page + 1) + "/" + pages,
            left + 6, top + 208, 0xFFE0E4EA, false);
        graphics.text(this.font, "Snapshots only: check stock, price and location before trading.",
            left + 6, top + 198, 0xFFFFBD7A, false);
    }

    @Override public void onClose() { this.minecraft.gui.setScreen(null); }
}
