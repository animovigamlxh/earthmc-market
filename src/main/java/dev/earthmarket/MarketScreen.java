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
    private static final int PANEL_W = 585;
    private static final int PANEL_H = 326;
    private EditBox search;
    private String filter = "ALL";
    private boolean ascending = true;
    private int availabilityFilter = 0; // 0 all, 1 confirmed, 2 unknown, 3 unavailable
    private Button availabilityButton;
    private int page = 0;
    private List<EarthMarketClient.DisplayQuote> visible = List.of();
    private Button previous;
    private Button next;
    private Button direction;
    private Button type;
    private final EarthMarketClient.DisplayQuote[] rowQuotes = new EarthMarketClient.DisplayQuote[PAGE_SIZE];
    private final Button[] locateButtons = new Button[PAGE_SIZE];
    private final Button[] townButtons = new Button[PAGE_SIZE];
    private final Button[] nationButtons = new Button[PAGE_SIZE];
    private final Button[] deleteButtons = new Button[PAGE_SIZE];
    private final Button[] detailsButtons = new Button[PAGE_SIZE];

    public MarketScreen() {
        super(Component.literal("EarthMC Market"));
    }

    @Override
    protected void init() {
        int left = Math.max(6, (this.width - PANEL_W) / 2);
        int top = Math.max(6, (this.height - PANEL_H) / 2);
        search = new EditBox(this.font, left + 6, top + 25, 340, 20, Component.literal("Search item, town, nation, owner"));
        search.setHint(Component.literal("Search item / town / nation"));
        GuiPreferences.State prefs = GuiPreferences.get();
        search.setValue(prefs.marketQuery);
        filter = prefs.marketFilter;
        ascending = prefs.marketAscending;
        availabilityFilter = prefs.marketStockFilter;
        
        search.setResponder(s -> { page = 0; refresh(); });
        addRenderableWidget(search);
        type = addRenderableWidget(Button.builder(Component.literal(filter), b -> {
            filter = filter.equals("ALL") ? "SELL" : filter.equals("SELL") ? "BUY" : "ALL";
            type.setMessage(Component.literal(filter));
            page = 0; refresh();
        }).bounds(left + 353, top + 25, 65, 20).build());
        direction = addRenderableWidget(Button.builder(Component.literal(ascending ? "Price ^" : "Price v"), b -> {
            ascending = !ascending;
            direction.setMessage(Component.literal(ascending ? "Price ^" : "Price v"));
            page = 0; refresh();
        }).bounds(left + 426, top + 25, 122, 20).build());
        availabilityButton = addRenderableWidget(Button.builder(Component.literal(stockFilterLabel()), b -> {
            availabilityFilter = (availabilityFilter + 1) % 4;
            availabilityButton.setMessage(Component.literal(stockFilterLabel()));
            page = 0; refresh();
        }).bounds(left + 315, top + 297, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Arbitrage"), b -> { remember(); this.minecraft.gui.setScreen(new ArbitrageScreen()); })
            .bounds(left + 158, top + 297, 78, 20).build());
        previous = addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> { page--; refresh(); })
            .bounds(left + 6, top + 297, 80, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("Next >"), b -> { page++; refresh(); })
            .bounds(left + 467, top + 297, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> this.onClose())
            .bounds(left + 240, top + 297, 75, 20).build());
        for (int i = 0; i < PAGE_SIZE; i++) {
            final int row = i;
            int y = top + 70 + i * 25;
            detailsButtons[i] = addRenderableWidget(Button.builder(Component.literal("i"), b -> showDetails(row))
                .bounds(left + 462, y, 17, 20).build());
            locateButtons[i] = addRenderableWidget(Button.builder(Component.literal("H"), b -> highlight(row))
                .bounds(left + 483, y, 18, 20).build());
            townButtons[i] = addRenderableWidget(Button.builder(Component.literal("Go"), b -> openTravel(row, true))
                .bounds(left + 505, y, 18, 20).build());
            deleteButtons[i] = addRenderableWidget(Button.builder(Component.literal("Del"), b -> requestDelete(row))
                .bounds(left + 531, y, 46, 20).build());
        }
        page = prefs.marketPage;
        refresh();
    }

    private void showDetails(int row) {
        EarthMarketClient.DisplayQuote q = rowQuotes[row];
        if (q == null) return;
        remember();
        this.minecraft.gui.setScreen(new QuoteDetailScreen(q, new MarketScreen()));
    }

    private void highlight(int row) {
        EarthMarketClient.DisplayQuote q = rowQuotes[row];
        if (q == null) return;
        remember();
        this.minecraft.gui.setScreen(null);
        EarthMarketClient.locateShop(q);
    }

    /** Automatic capital-aware chat draft; player still presses Enter. */
    private void openTravel(int row, boolean ignored) {
        EarthMarketClient.DisplayQuote q = rowQuotes[row];
        if (q == null) return;
        remember();
        CapitalTravelResolver.prepare(this.minecraft, q.town(), q.nation(), q);
    }

    private void refresh() {
        if (search == null) return;
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        List<EarthMarketClient.DisplayQuote> data = new ArrayList<>();
        for (var q : EarthMarketClient.displayQuotes()) {
            int stock = stockClass(q);
            if (availabilityFilter == 1 && stock != 1) continue;
            if (availabilityFilter == 2 && stock != 2) continue;
            if (availabilityFilter == 3 && stock != 3) continue;
            if (!filter.equals("ALL") && !filter.equalsIgnoreCase(q.type())) continue;
            String haystack = (EarthMarketClient.variantLabel(q) + " " + safe(q.shop()) + " " + safe(q.town()) + " " + safe(q.nation()))
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
            if (detailsButtons[i] != null) {
                detailsButtons[i].visible = q != null;
                detailsButtons[i].active = q != null;
            }
            if (locateButtons[i] != null) {
                locateButtons[i].visible = q != null;
                locateButtons[i].active = q != null && ("earthmc-sign".equals(q.source()) || "target-block".equals(q.accuracy()))
                    && q.dimension() != null;
            }
            if (townButtons[i] != null) {
                townButtons[i].visible = q != null;
                townButtons[i].active = q != null && validDestination(q.town());
            }
            if (deleteButtons[i] != null) {
                deleteButtons[i].visible = q != null;
                deleteButtons[i].active = q != null;
            }
        }
    }

    private String stockFilterLabel() {
        return switch (availabilityFilter) {
            case 1 -> "Stock: OK"; case 2 -> "Stock: ?";
            case 3 -> "Stock: X"; default -> "Stock: ALL";
        };
    }
    private void remember() {
        GuiPreferences.State p = GuiPreferences.get();
        p.marketQuery = search == null ? "" : search.getValue();
        p.marketFilter = filter;
        p.marketAscending = ascending;
        p.marketStockFilter = availabilityFilter;
        p.marketPage = page;
        GuiPreferences.save();
    }
    private void requestDelete(int row) {
        EarthMarketClient.DisplayQuote q = rowQuotes[row];
        if (q == null) return;
        remember();
        // A dedicated confirmation screen avoids accidentally removing the wrong row.
        minecraft.gui.setScreen(new DeleteQuoteScreen(q, new MarketScreen()));
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

    static int stockClass(EarthMarketClient.DisplayQuote q) {
        if ("out-of-stock".equals(q.status()) || "full".equals(q.status()) || "unavailable".equals(q.status()) || "sign-red-unverified".equals(q.status()) || q.available() != null && q.available() <= 0) return 3;
        if (q.available() == null || "sign-unverified".equals(q.status())) return 2;
        return 1;
    }

    private static String stockLabel(EarthMarketClient.DisplayQuote q) {
        if ("sign-red-unverified".equals(q.status())) return "CHECK SIGN";
        if ("out-of-stock".equals(q.status())) return "OUT OF STOCK";
        if ("full".equals(q.status())) return "BUY FULL";
        if ("sign-tradeable".equals(q.status()) && q.available() == null) return "TRADEABLE (qty ?)";
        if (q.available() == null) return "quantity unknown";
        String prefix = "buy".equalsIgnoreCase(q.type()) ? "capacity " : "stock ";
        if (q.signLots() != null) {
            String units = q.available() == null ? "overflow" : String.format(Locale.ROOT, "%,d", q.available());
            return prefix + units + " items (" + q.signLots() + " lots x " + q.amount() + ")";
        }
        return prefix + String.format(Locale.ROOT, "%,d", q.available()) + " items";
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
            graphics.text(this.font, (buying ? "B " : "S ") + shortText(EarthMarketClient.variantLabel(q), 20), left + 7, y, fg, false);
            // The original per-trade amount/price is displayed separately from the normalized unit price.
            graphics.text(this.font, originalPrice(q), left + 164, y, 0xFFFFFFFF, false);
            graphics.text(this.font, String.format(Locale.ROOT, "%.4f", q.unitPrice()), left + 286, y, 0xFFFFFFFF, false);
            graphics.text(this.font, shortText(safe(q.town()).isBlank() ? "(unknown)" : q.town(), 13), left + 390, y, 0xFFB8D9F8, false);
            // EarthMC sign lots are converted to item counts in the quote model.
            graphics.text(this.font, shortText(q.shop() + (q.enchantments() == null || q.enchantments().isBlank() ? "" : " | " + q.enchantments()) + "  " + stockLabel(q), 64), left + 17, y + 11, 0xFFB8BEC7, false);
            graphics.text(this.font, shortText(q.nation(), 13), left + 390, y + 11, 0xFF95A8B5, false);
        }
        int pages = Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        graphics.text(this.font, "Records: " + visible.size() + "   Page " + (page + 1) + "/" + pages,
            left + 6, top + 278, 0xFFE0E4EA, false);
        graphics.text(this.font, "i: full details | H: highlight | Go: teleport | Del: delete",
            left + 6, top + 288, 0xFFFFBD7A, false);
    }

    @Override public void onClose() { remember(); this.minecraft.gui.setScreen(null); }
}
