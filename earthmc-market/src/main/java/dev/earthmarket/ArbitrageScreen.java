package dev.earthmarket;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Snapshot-based cross-shop spreads, no automatic trading or teleporting. */
public final class ArbitrageScreen extends Screen {
    private static final int W = 610, H = 345, PER_PAGE = 7;
    private EditBox search;
    private Button statusButton, previous, next;
    private boolean includeUnknown = true;
    private int page = 0;
    private List<Opportunity> results = List.of();
    private final Opportunity[] current = new Opportunity[PER_PAGE];
    private final Button[] buyTown = new Button[PER_PAGE], sellTown = new Button[PER_PAGE];

    private record Opportunity(String item, EarthMarketClient.DisplayQuote ask, EarthMarketClient.DisplayQuote bid,
                               double spread, int stockClass, int maxUnits) {}

    public ArbitrageScreen() { super(Component.literal("EarthMC Arbitrage")); }
    @Override protected void init() {
        int l = Math.max(4, (width-W)/2), t = Math.max(4, (height-H)/2);
        search = addRenderableWidget(new EditBox(font, l+7, t+25, 285, 20, Component.literal("Find item")));
        search.setHint(Component.literal("Search items, towns, nations"));
        GuiPreferences.State prefs = GuiPreferences.get();
        search.setValue(prefs.arbitrageQuery);
        includeUnknown = prefs.arbitrageIncludeUnknown;
        
        search.setResponder(v -> { page=0; update(); });
        statusButton = addRenderableWidget(Button.builder(Component.literal(includeUnknown ? "Include unverified" : "Verified only"), b -> {
            includeUnknown = !includeUnknown;
            statusButton.setMessage(Component.literal(includeUnknown ? "Include unverified" : "Verified only"));
            page=0; update();
        }).bounds(l+300,t+25,148,20).build());
        addRenderableWidget(Button.builder(Component.literal("All shops"), b -> { remember(); minecraft.gui.setScreen(new MarketScreen()); })
            .bounds(l+456,t+25,145,20).build());
        previous=addRenderableWidget(Button.builder(Component.literal("< Prev"), b->{ page--; update(); })
            .bounds(l+7,t+315,80,20).build());
        next=addRenderableWidget(Button.builder(Component.literal("Next >"), b->{ page++; update(); })
            .bounds(l+522,t+315,80,20).build());
        for (int i=0;i<PER_PAGE;i++) {
            final int index=i; int y=t+75+i*32;
            buyTown[i]=addRenderableWidget(Button.builder(Component.literal("Go Buy"), b->travel(index,true))
                .bounds(l+457,y,70,20).build());
            sellTown[i]=addRenderableWidget(Button.builder(Component.literal("Go Sell"), b->travel(index,false))
                .bounds(l+531,y,70,20).build());
        }
        page = prefs.arbitragePage;
        update();
    }
    private static String safe(String s) { return s == null ? "" : s; }
    private static boolean destinationOk(String s) {
        return !safe(s).isBlank() && !"unknown".equalsIgnoreCase(s) && !s.startsWith("(")
            && s.codePoints().noneMatch(c->Character.isISOControl(c) || Character.isWhitespace(c));
    }
    private void travel(int i, boolean toBuy) {
        Opportunity o = current[i]; if (o == null) return;
        var q = toBuy ? o.ask : o.bid;
        remember();
        CapitalTravelResolver.prepare(minecraft, q.town(), q.nation(), q);
    }
    private void update() {
        if(search==null) return;
        String needle=search.getValue().trim().toLowerCase(Locale.ROOT);
        Map<String,List<EarthMarketClient.DisplayQuote>> byItem=new HashMap<>();
        for(var q:EarthMarketClient.displayQuotes()) {
            if(q.item()==null||q.amount()<=0||!Double.isFinite(q.unitPrice())||q.unitPrice()<=0)continue;
            if(MarketScreen.stockClass(q)==3)continue;
            if(!includeUnknown && MarketScreen.stockClass(q)!=1)continue;
            // Never combine different exact item data. Unresolved sign entries may
            // only pair with one another if there are no known variants of that name.
            String key = (q.item() == null ? "" : q.item().strip().toLowerCase(Locale.ROOT))
                + "\u0000" + (q.variantKey() == null ? "unknown" : "exact:" + q.variantKey())
                + "\u0000" + safe(q.enchantments()).strip().toLowerCase(Locale.ROOT);
            byItem.computeIfAbsent(key, k->new ArrayList<>()).add(q);
        }
        ArrayList<Opportunity> found=new ArrayList<>();
        for(var e:byItem.entrySet()) {
            // Unknown details must not create false-positive arbitrage for item
            // families already observed with structured variants.
            if (e.getKey().contains("\u0000unknown\u0000")) {
                String base = e.getKey().substring(0, e.getKey().indexOf('\u0000'));
                String family = base + "\u0000exact:";
                // Potentially data-bearing items must be inspected before arbitrage.
                if (byItem.keySet().stream().anyMatch(k -> k.startsWith(family))
                    || base.matches(".*(唱片|music.disc|附魔|enchanted|药水|potion|箭|arrow|书|book|旗帜|banner|烟花|firework|头颅|head|盔甲|armor|剑|sword|工具|tool|地图|map|潜影盒|shulker).*")) continue;
            }
            List<EarthMarketClient.DisplayQuote> asks=new ArrayList<>(), bids=new ArrayList<>();
            for(var q:e.getValue()) ("sell".equalsIgnoreCase(q.type())?asks:bids).add(q);
            asks.sort(Comparator.comparingDouble(EarthMarketClient.DisplayQuote::unitPrice));
            bids.sort(Comparator.comparingDouble(EarthMarketClient.DisplayQuote::unitPrice).reversed());
            // Best opposite-side spread per item. Avoid pairing the same physical shop owner and location.
            Opportunity best=null;
            for(var ask:asks) for(var bid:bids) {
                if(bid.unitPrice() <= ask.unitPrice()) break;
                if(safe(ask.shop()).equalsIgnoreCase(safe(bid.shop())) && Objects.equals(ask.town(),bid.town()))continue;
                int certainty=(MarketScreen.stockClass(ask)==1 && MarketScreen.stockClass(bid)==1)?1:2;
                int qty=certainty==1?Math.min(ask.available(),bid.available()):0;
                String hay=(e.getKey()+" "+safe(ask.town())+" "+safe(bid.town())+" "+safe(ask.nation())+" "+safe(bid.nation())).toLowerCase(Locale.ROOT);
                if(!hay.contains(needle))continue;
                double spread=bid.unitPrice()-ask.unitPrice();
                if(best==null || spread>best.spread()) best=new Opportunity(EarthMarketClient.variantLabel(ask),ask,bid,spread,certainty,qty);
            }
            if(best!=null) found.add(best);
        }
        found.sort(Comparator.comparingDouble(Opportunity::spread).reversed());
        results=found;
        int pages=Math.max(1,(results.size()+PER_PAGE-1)/PER_PAGE);
        page=Math.max(0,Math.min(page,pages-1));
        previous.active=page>0;next.active=page+1<pages;
        for(int i=0;i<PER_PAGE;i++) {
            current[i]=page*PER_PAGE+i<results.size()?results.get(page*PER_PAGE+i):null;
            boolean active=current[i]!=null;
            buyTown[i].visible=active;sellTown[i].visible=active;
            buyTown[i].active=active&&destinationOk(current[i].ask.town());
            sellTown[i].active=active&&destinationOk(current[i].bid.town());
        }
    }
    private static String shortName(String s,int max){s=safe(s);return s.length()<=max?s:s.substring(0,max-1)+"…";}
    private static String price(EarthMarketClient.DisplayQuote q){return String.format(Locale.ROOT,"%.3fG/%d",q.bundlePrice(),q.amount());}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        update(); int l=Math.max(4,(width-W)/2),t=Math.max(4,(height-H)/2);
        g.fill(l,t,l+W,t+H,0xEE131F2A);g.outline(l,t,W,H,0xFF688EAA);
        super.extractRenderState(g,mx,my,delta);
        g.text(font,"EarthMC - buy low / sell high (observed prices, not guaranteed profit)",l+7,t+8,0xFFFFFFFF,true);
        g.text(font,"Item / buy source / sell destination",l+7,t+55,0xFFB0D1EB,false);
        g.text(font,"Spread (G/item)",l+318,t+55,0xFFB0D1EB,false);
        for(int i=0;i<PER_PAGE&&page*PER_PAGE+i<results.size();i++) {
            Opportunity o=results.get(page*PER_PAGE+i);int y=t+75+i*32;
            g.fill(l+4,y-2,l+W-4,y+29,i%2==0?0x66304659:0x44304659);
            g.text(font,shortName(o.item(),25),l+7,y,0xFFFFE39B,false);
            g.text(font,"Buy " +shortName(safe(o.ask.town()).isBlank()?"?":o.ask.town(),13)+" "+price(o.ask),l+7,y+11,0xFFB7DFEE,false);
            g.text(font,"Sell "+shortName(safe(o.bid.town()).isBlank()?"?":o.bid.town(),13)+" "+price(o.bid),l+182,y+11,0xFFB3EFCB,false);
            g.text(font,String.format(Locale.ROOT,"+%.4f",o.spread()),l+331,y,0xFF82EAAF,false);
            String flag=o.stockClass()==1?"Max "+o.maxUnits()+" items":"Tradeable; qty unknown";
            g.text(font,shortName(flag,23),l+318,y+11,o.stockClass()==1?0xFF8EDAA7:0xFFFFCB77,false);
        }
        int pages=Math.max(1,(results.size()+PER_PAGE-1)/PER_PAGE);
        g.text(font,"Opportunities: "+results.size()+" | page "+(page+1)+"/"+pages,l+99,t+319,0xFFEEEEEE,false);
        g.text(font,"Profit excludes fees/travel. Stock and prices can change. Go Buy/Sell only prefill chat.",l+7,t+302,0xFFFFC88E,false);
    }
    private void remember() {
        GuiPreferences.State p = GuiPreferences.get();
        p.arbitrageQuery = search == null ? "" : search.getValue();
        p.arbitrageIncludeUnknown = includeUnknown;
        p.arbitragePage = page;
        GuiPreferences.save();
    }
    @Override public void onClose(){remember();minecraft.gui.setScreen(null);}
}
