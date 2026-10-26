package dev.earthmarket;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** A specific item's markets, separated by the direction of the player's transaction. */
final class SingleItemCompareScreen extends Screen {
    private static final int ROWS = 4;
    private static final int WIDTH = 585;
    private static final int HEIGHT = 355;
    private final EarthMarketClient.DisplayQuote selected;
    private final Screen back;
    private List<EarthMarketClient.DisplayQuote> selling = List.of();
    private List<EarthMarketClient.DisplayQuote> buying = List.of();
    private final EarthMarketClient.DisplayQuote[] slots = new EarthMarketClient.DisplayQuote[ROWS * 2];
    private final Button[] details = new Button[ROWS * 2];
    private final Button[] highlights = new Button[ROWS * 2];
    private final Button[] travel = new Button[ROWS * 2];
    private int sellPage = 0;
    private int buyPage = 0;
    private Button sellPrev, sellNext, buyPrev, buyNext;

    SingleItemCompareScreen(EarthMarketClient.DisplayQuote selected, Screen back) {
        super(Component.literal("Compare one item"));
        this.selected = selected;
        this.back = back;
    }

    private static String normalizedVariant(EarthMarketClient.DisplayQuote q) {
        HoverVariantReader.Identity upgraded = HoverVariantReader.upgradeLegacy(q.variantKey(), q.variantDescription());
        return upgraded == null ? (q.variantKey() == null ? "" : q.variantKey()) : upgraded.key();
    }

    private boolean sameItem(EarthMarketClient.DisplayQuote q) {
        if (!Objects.equals(selected.item(), q.item())) return false;
        String first = normalizedVariant(selected), other = normalizedVariant(q);
        if (!first.equals(other)) return false; // Never mix known and unknown variants.
        String a = selected.enchantments() == null ? "" : selected.enchantments().strip();
        String b = q.enchantments() == null ? "" : q.enchantments().strip();
        return a.equals(b);
    }

    private void reload() {
        List<EarthMarketClient.DisplayQuote> sell = new ArrayList<>(), buy = new ArrayList<>();
        for (var q : EarthMarketClient.displayQuotes()) {
            if (!sameItem(q)) continue;
            if ("SELL".equalsIgnoreCase(q.type())) sell.add(q);
            else if ("BUY".equalsIgnoreCase(q.type())) buy.add(q);
        }
        sell.sort(Comparator.comparingDouble(EarthMarketClient.DisplayQuote::unitPrice));
        buy.sort(Comparator.comparingDouble(EarthMarketClient.DisplayQuote::unitPrice).reversed());
        selling = sell; buying = buy;
        sellPage = Math.min(sellPage, Math.max(0, (sell.size() - 1) / ROWS));
        buyPage = Math.min(buyPage, Math.max(0, (buy.size() - 1) / ROWS));
        for (int i = 0; i < ROWS; ++i) {
            slots[i] = sellPage * ROWS + i < sell.size() ? sell.get(sellPage * ROWS + i) : null;
            slots[i + ROWS] = buyPage * ROWS + i < buy.size() ? buy.get(buyPage * ROWS + i) : null;
        }
        if (sellPrev != null) sellPrev.active = sellPage > 0;
        if (sellNext != null) sellNext.active = (sellPage + 1) * ROWS < sell.size();
        if (buyPrev != null) buyPrev.active = buyPage > 0;
        if (buyNext != null) buyNext.active = (buyPage + 1) * ROWS < buy.size();
        for (int i = 0; i < slots.length; i++) {
            boolean present = slots[i] != null;
            if (details[i] != null) { details[i].visible = present; details[i].active = present; }
            if (highlights[i] != null) {
                highlights[i].visible = present;
                highlights[i].active = present && ("earthmc-sign".equals(slots[i].source()) || "target-block".equals(slots[i].accuracy()));
            }
            if (travel[i] != null) {
                travel[i].visible = present;
                String town = present ? slots[i].town() : "";
                travel[i].active = present && town != null && !town.isBlank() && !town.equalsIgnoreCase("unknown");
            }
        }
    }

    @Override protected void init() {
        int left = Math.max(5, (width - WIDTH) / 2), top = Math.max(5, (height - HEIGHT) / 2);
        sellPrev = addRenderableWidget(Button.builder(Component.literal("<"), b -> { sellPage--; reload(); }).bounds(left + 439, top + 32, 25, 18).build());
        sellNext = addRenderableWidget(Button.builder(Component.literal(">"), b -> { sellPage++; reload(); }).bounds(left + 470, top + 32, 25, 18).build());
        buyPrev = addRenderableWidget(Button.builder(Component.literal("<"), b -> { buyPage--; reload(); }).bounds(left + 439, top + 174, 25, 18).build());
        buyNext = addRenderableWidget(Button.builder(Component.literal(">"), b -> { buyPage++; reload(); }).bounds(left + 470, top + 174, 25, 18).build());
        for (int i = 0; i < slots.length; i++) {
            final int slot = i;
            int y = top + (i < ROWS ? 57 + i * 23 : 199 + (i - ROWS) * 23);
            details[i] = addRenderableWidget(Button.builder(Component.literal("i"), b -> {
                if (slots[slot] != null) minecraft.gui.setScreen(new QuoteDetailScreen(slots[slot], this));
            }).bounds(left + 475, y, 21, 18).build());
            highlights[i] = addRenderableWidget(Button.builder(Component.literal("H"), b -> {
                if (slots[slot] != null) { minecraft.gui.setScreen(null); EarthMarketClient.locateShop(slots[slot]); }
            }).bounds(left + 500, y, 21, 18).build());
            travel[i] = addRenderableWidget(Button.builder(Component.literal("Go"), b -> {
                var q = slots[slot];
                if (q != null) CapitalTravelResolver.prepare(minecraft, q.town(), q.nation(), q);
            }).bounds(left + 525, y, 45, 18).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(left + 250, top + 326, 85, 20).build());
        reload();
    }

    private void drawRows(GuiGraphicsExtractor g, int left, int top, boolean sell) {
        var list = sell ? selling : buying;
        int page = sell ? sellPage : buyPage;
        int baseY = top + (sell ? 57 : 199);
        for (int i = 0; i < ROWS; i++) {
            int index = page * ROWS + i;
            if (index >= list.size()) break;
            var q = list.get(index);
            int y = baseY + i * 23;
            g.fill(left + 7, y - 2, left + WIDTH - 8, y + 19, i % 2 == 0 ? 0x55374454 : 0x55293643);
            String stock = q.available() == null ? "?" : String.format(Locale.ROOT, "%,d", q.available());
            String row = String.format(Locale.ROOT, "%d. %.4f G/件  |  %s  |  库存/额度 %s", index + 1, q.unitPrice(),
                    brief(q.town(), 19), stock);
            g.text(font, brief(row, 54), left + 12, y + 4, sell ? 0xFFFFD08E : 0xFF8DE9B6, false);
        }
    }
    private static String brief(String s, int max) {
        if (s == null) return "?";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        int left = Math.max(5, (width - WIDTH) / 2), top = Math.max(5, (height - HEIGHT) / 2);
        g.fill(left, top, left + WIDTH, top + HEIGHT, 0xF0111D29);
        g.outline(left, top, WIDTH, HEIGHT, 0xFF6994A9);
        g.text(font, "单品比价: " + brief(EarthMarketClient.variantLabel(selected), 53), left + 9, top + 12, 0xFFFFFFFF, true);
        g.text(font, "出售商店（你可以买） | 低价优先 | " + selling.size() + " 家 | 第 " + (sellPage + 1) + " 页", left + 9, top + 36, 0xFFFFD08E, true);
        g.text(font, "收购商店（你可以卖） | 高价优先 | " + buying.size() + " 家 | 第 " + (buyPage + 1) + " 页", left + 9, top + 178, 0xFF8DE9B6, true);
        drawRows(g, left, top, true);
        drawRows(g, left, top, false);
        g.text(font, normalizedVariant(selected).isBlank() ? "注意：此商品缺少精确变种数据，结果仅按名称/附魔匹配" : "已按照物品 ID / 变种 / 附魔匹配", left + 10, top + 306, 0xFFB3C5D4, false);
        super.extractRenderState(g, mx, my, dt);
    }
    @Override public void onClose() { minecraft.gui.setScreen(back); }
}
