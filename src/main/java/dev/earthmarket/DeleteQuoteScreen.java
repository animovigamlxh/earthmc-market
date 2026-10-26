package dev.earthmarket;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit second step for irreversible local record deletion. */
final class DeleteQuoteScreen extends Screen {
    private final EarthMarketClient.DisplayQuote quote;
    private final Screen back;
    DeleteQuoteScreen(EarthMarketClient.DisplayQuote quote, Screen back) {
        super(Component.literal("Confirm record deletion"));
        this.quote = quote;
        this.back = back;
    }
    @Override protected void init() {
        int cx = width / 2, cy = height / 2;
        addRenderableWidget(Button.builder(Component.literal("Delete this record"), b -> {
            EarthMarketClient.deleteQuote(quote.id());
            minecraft.gui.setScreen(back);
        }).bounds(cx - 154, cy + 35, 148, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(cx + 6, cy + 35, 148, 20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int cx = width / 2, cy = height / 2;
        g.fill(cx - 166, cy - 66, cx + 166, cy + 64, 0xF0202936);
        g.outline(cx - 166, cy - 66, 332, 130, 0xFFCF8178);
        g.text(font, "Delete saved shop observation?", cx - 146, cy - 51, 0xFFFFC5A4, true);
        g.text(font, compact(quote.item() + " / " + quote.type(), 45), cx - 146, cy - 30, 0xFFFFFFFF, false);
        g.text(font, compact("Owner: " + quote.shop() + " | Town: " + quote.town(), 48), cx - 146, cy - 17, 0xFFC4D9EE, false);
        g.text(font, "This removes the selected record from prices.json.", cx - 146, cy + 1, 0xFFFFCCB3, false);
        g.text(font, "Deleted sign locations remain ignored until restored.", cx - 146, cy + 14, 0xFFCECEDA, false);
        super.extractRenderState(g, mx, my, delta);
    }
    private static String compact(String s, int max) { return s.length() <= max ? s : s.substring(0, max-1) + "…"; }
    @Override public void onClose() { minecraft.gui.setScreen(back); }
}
