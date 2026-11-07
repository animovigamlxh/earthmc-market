package dev.earthmarket;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Sell mode never queries blocks or Chest Tracker in extractRenderState / search callbacks. */
public final class SellModeScreen extends Screen {
    private static final int PANEL_W = 595, PANEL_H = 315, ROWS = 7;
    private final Screen back;
    private SellModeData.Snapshot snapshot = new SellModeData.Snapshot(List.of(),0,0,"Not loaded");
    private List<SellModeData.Offer> filtered = List.of();
    private EditBox search;
    private int page;
    private Button previous, next;
    private final Button[] boxes = new Button[ROWS];
    private final Button[] go = new Button[ROWS];

    public SellModeScreen(Screen back) { super(Component.literal("EarthMC Market - Sell Mode")); this.back = back; }

    @Override protected void init() {
        int x = Math.max(5, (width - PANEL_W) / 2);
        int y = Math.max(5, (height - PANEL_H) / 2);
        snapshot = SellModeData.read(); // one read per screen opening, never per frame
        search = addRenderableWidget(new EditBox(font, x+9, y+28, 345, 20, Component.literal("Search owned items")));
        search.setHint(Component.literal("Filter recorded items"));
        search.setResponder(s -> {page = 0; filter();});
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> { snapshot = SellModeData.read(); filter(); })
            .bounds(x+365,y+28,83,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> minecraft.gui.setScreen(back))
            .bounds(x+461,y+28,122,20).build());
        previous = addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> {page--; filter();})
            .bounds(x+10,y+282,90,20).build());
        next = addRenderableWidget(Button.builder(Component.literal("Next >"), b -> {page++; filter();})
            .bounds(x+490,y+282,93,20).build());
        for (int i=0; i<ROWS; i++) {
            final int line = i;
            int ry = y + 83 + i * 26;
            boxes[i] = addRenderableWidget(Button.builder(Component.literal("Boxes"), b -> showBoxes(line))
                .bounds(x+462,ry,59,20).build());
            go[i] = addRenderableWidget(Button.builder(Component.literal("Go"), b -> travel(line))
                .bounds(x+527,ry,53,20).build());
        }
        filter();
    }
    private void filter() {
        String q = search == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        List<SellModeData.Offer> matches = new ArrayList<>();
        for (var offer : snapshot.offers()) {
            String match = (offer.name()+" "+offer.itemId()+" "+offer.buyer().town()+" "+offer.buyer().nation()).toLowerCase(Locale.ROOT);
            if (match.contains(q)) matches.add(offer);
        }
        filtered = matches;
        page = Math.max(0, Math.min(page, Math.max(0,(filtered.size()-1)/ROWS)));
        if (previous!=null) previous.active = page>0;
        if (next!=null) next.active = (page+1)*ROWS<filtered.size();
        for(int i=0;i<ROWS;i++) {
            boolean has=page*ROWS+i<filtered.size();
            if(boxes[i]!=null) {boxes[i].visible=has;boxes[i].active=has;}
            if(go[i]!=null) {
                go[i].visible=has;
                var offer=has?filtered.get(page*ROWS+i):null;
                go[i].active=has && offer!=null && !blank(offer.buyer().town());
            }
        }
    }
    private static boolean blank(String s) {return s==null || s.isBlank() || s.equalsIgnoreCase("unknown");}
    private SellModeData.Offer get(int line) {
        int i=page*ROWS+line; return i>=0 && i<filtered.size()?filtered.get(i):null;
    }
    private void showBoxes(int line) {
        var offer=get(line);
        if (offer!=null) minecraft.gui.setScreen(new SellModeBoxesScreen(this,offer));
    }
    private void travel(int line) {
        var offer=get(line);
        if(offer!=null) CapitalTravelResolver.prepare(minecraft,offer.buyer().town(),offer.buyer().nation(),offer.buyer());
    }
    private static String clip(String s,int max) {
        if(s==null) return "";
        return s.length()>max?s.substring(0,max-1)+"…":s;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        int x=Math.max(5,(width-PANEL_W)/2), y=Math.max(5,(height-PANEL_H)/2);
        g.fill(x,y,x+PANEL_W,y+PANEL_H,0xEF101923);
        g.outline(x,y,PANEL_W,PANEL_H,0xFF6390A7);
        super.extractRenderState(g,mx,my,delta);
        g.text(font,"Sell Mode | recorded boxes → BUY offers (not live inventory)",x+10,y+8,0xFFFFFFFF,true);
        g.text(font,"Item / stored",x+11,y+65,0xFF8DC8E9,false);
        g.text(font,"BUY price / capacity",x+193,y+65,0xFF8DC8E9,false);
        g.text(font,"Buyer town / value",x+330,y+65,0xFF8DC8E9,false);
        for(int i=0;i<ROWS;i++) {
            var offer=get(i); if(offer==null) break;
            int ry=y+83+i*26;
            g.fill(x+6,ry-2,x+PANEL_W-7,ry+23,i%2==0?0x66304559:0x44304559);
            g.text(font,clip(offer.name(),24),x+11,ry,0xFF9FE9BB,false);
            g.text(font,"Stock: "+offer.count()+" in "+offer.boxes().size()+" boxes",x+11,ry+11,0xFFB9C6D2,false);
            g.text(font,String.format(Locale.ROOT,"%.4f G / item",offer.unitGold()),x+193,ry,0xFFFFD487,false);
            g.text(font,"Can sell: "+offer.possible()+(offer.capacityUnknown()?" (?)":""),x+193,ry+11,0xFFB9C6D2,false);
            g.text(font,clip(offer.buyer().town(),17),x+330,ry,0xFFD2E2F8,false);
            g.text(font,String.format(Locale.ROOT,"%s%.2fG",offer.capacityUnknown()?"~":"",offer.expectedGold()),x+330,ry+11,0xFFFFD487,false);
        }
        g.text(font,"Recorded: "+snapshot.boxes()+" boxes / "+snapshot.items()+" items | Sellable: "+filtered.size()+" | Page "+(page+1),
            x+10,y+268,0xFFCFD7E1,false);
        g.text(font,clip(snapshot.warning(),93),x+105,y+287,0xFFFFB77B,false);
    }
    @Override public void onClose() {minecraft.gui.setScreen(back);}
}
