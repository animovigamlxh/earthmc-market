package dev.earthmarket;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Full-width, scrollable item details; never assumes a hover variant was captured. */
final class QuoteDetailScreen extends Screen {
    private final EarthMarketClient.DisplayQuote q;
    private final Screen back;
    private final List<String> lines = new ArrayList<>();
    private int offset = 0;
    private Button previous, next;

    QuoteDetailScreen(EarthMarketClient.DisplayQuote q, Screen back) {
        super(Component.literal("EarthMC shop details"));
        this.q = q;
        this.back = back;
        append("商品名称", q.item());
        append("类型", q.type());
        String key = q.variantKey();
        String desc = q.variantDescription();
        HoverVariantReader.Identity upgraded = HoverVariantReader.upgradeLegacy(key, desc);
        if (upgraded != null) { key = upgraded.key(); desc = upgraded.label(); }
        if (key != null && key.contains(":") && !key.startsWith("template#")) {
            String itemId = key.split("#",2)[0];
            append("Minecraft 物品 ID", itemId);
            if (itemId.contains(":music_disc_")) append("唱片名称", HoverVariantReader.friendlyLabel(itemId));
        }
        boolean identified = key != null && !key.isBlank();
        append("变种识别状态", identified ? "已捕获物品数据" : "未捕获精确物品数据（无法确认具体变种）");
        append("变种描述", desc == null || desc.isBlank() ? "无" : desc);
        append("变种标识", identified ? key : "未知");
        append("附魔", q.enchantments());
        append("单价", String.format(Locale.ROOT, "%.6f G/件", q.unitPrice()));
        append("每份", q.amount() + " 件 / " + q.bundlePrice() + " G");
        append("剩余份数", q.signLots() == null ? "未知" : q.signLots().toString());
        append("可交易件数", q.available() == null ? "未知" : q.available().toString());
        append("状态", q.status());
        append("店主", q.shop());
        append("城镇／国家", q.town() + " / " + q.nation());
        append("商店告示牌坐标", q.x() + ", " + q.y() + ", " + q.z());
        append("维度", q.dimension());
        append("数据来源", q.source());
        append("更新时间", q.updated());
    }

    private void append(String name, String value) {
        String full = name + ": " + (value == null || value.isBlank() ? "（未记录）" : value);
        // Character based wrapping is deliberately conservative for Chinese wide glyphs.
        int max = 45;
        for (int i=0; i<full.length(); i+=max) lines.add(full.substring(i, Math.min(i+max,full.length())));
    }

    @Override protected void init() {
        int cy = height - 31;
        previous = addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { offset=Math.max(0,offset-pageSize()); update(); })
            .bounds(width/2-172,cy,76,20).build());
        next = addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { offset=Math.min(Math.max(0,lines.size()-pageSize()),offset+pageSize()); update(); })
            .bounds(width/2-88,cy,76,20).build());
        addRenderableWidget(Button.builder(Component.literal("返回 GUI"), b -> onClose())
            .bounds(width/2+10,cy,110,20).build());
        update();
    }
    private int pageSize() { return Math.max(4, (height - 91) / 13); }
    private void update() {
        if(previous != null) previous.active=offset>0;
        if(next != null) next.active=offset+pageSize()<lines.size();
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        int left=Math.max(5,width/2-240), right=Math.min(width-5,width/2+240);
        g.fill(left,10,right,height-6,0xF0192531);
        g.outline(left,10,right-left,height-16,0xFF619DB8);
        g.text(font,"商店商品完整信息（含识别状态）",left+10,20,0xFFB7EBFF,true);
        int shown=Math.min(pageSize(),lines.size()-offset);
        for(int i=0;i<shown;i++) g.text(font, lines.get(offset+i),left+10,43+i*13,0xFFE6EAF1,false);
        g.text(font,"第 "+(offset+1)+" - "+(offset+shown)+" / "+lines.size()+" 行",left+10,height-52,0xFFB9C6D3,false);
        super.extractRenderState(g,mx,my,dt);
    }
    @Override public void onClose(){ minecraft.gui.setScreen(back); }
}
