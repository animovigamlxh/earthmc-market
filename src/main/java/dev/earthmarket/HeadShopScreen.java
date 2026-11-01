package dev.earthmarket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** A read-only catalogue of loaded item-frame head displays and *possible* payment signs.
 * A redstone payment item may be unrelated to the displayed head. Never pretend this is
 * a verified QuickShop transaction, and never merge it into ordinary arbitrage quotes. */
public final class HeadShopScreen extends Screen {
    private static final int ROWS = 6;
    private static final int WIDTH = 590;
    private static final int HEIGHT = 292;
    private final Screen previous;
    private int page;
    private final Entry[] rows = new Entry[ROWS];
    private final Button[] details = new Button[ROWS];
    private final Button[] go = new Button[ROWS];
    private Button backPage, nextPage;
    private EditBox search;
    private static String previousQuery = "";
    private final Button[] highlight = new Button[ROWS];
    private final Button[] favoriteButtons = new Button[ROWS];
    private Button favoritesFilter;
    private static boolean onlyFavorites = false;
    private List<Entry> entries = List.of();

    public HeadShopScreen(Screen previous) {
        super(Component.literal("EarthMC Head Shops"));
        this.previous = previous;
    }

    record Entry(HeadFrameScanner.Head head, EarthMarketClient.DisplayQuote payment, String status, boolean unavailable) {}

    @Override protected void init() {
        int x = Math.max(4, (width-WIDTH)/2), y = Math.max(4, (height-HEIGHT)/2);
        search = new EditBox(this.font,x+7,y+41,310,19,Component.literal("Search head name"));
        search.setHint(Component.literal("Search head name / fingerprint"));
        search.setValue(previousQuery);
        search.setResponder(value -> { previousQuery=value; page=0; refresh(); });
        addRenderableWidget(search);
        favoritesFilter = addRenderableWidget(Button.builder(Component.literal(""), b -> {onlyFavorites=!onlyFavorites;page=0;refresh();})
            .bounds(x+323,y+41,145,19).build());
        backPage = addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> {page--; updatePage();})
            .bounds(x+7,y+HEIGHT-27,75,20).build());
        nextPage = addRenderableWidget(Button.builder(Component.literal("Next >"), b -> {page++; updatePage();})
            .bounds(x+WIDTH-83,y+HEIGHT-27,75,20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh frames"), b -> {HeadCatalogue.scan(minecraft); refresh();})
            .bounds(x+102,y+HEIGHT-27,130,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> minecraft.gui.setScreen(previous))
            .bounds(x+250,y+HEIGHT-27,82,20).build());
        for (int i=0;i<ROWS;i++) {
            final int row=i;
            int ry=y+83+i*30;
            favoriteButtons[i]=addRenderableWidget(Button.builder(Component.literal("☆"),b -> toggleFavorite(row))
                .bounds(x+450,ry,23,20).build());
            details[i]=addRenderableWidget(Button.builder(Component.literal("i"),b -> info(row))
                .bounds(x+477,ry,23,20).build());
            highlight[i]=addRenderableWidget(Button.builder(Component.literal("H"),b -> mark(row))
                .bounds(x+504,ry,23,20).build());
            go[i]=addRenderableWidget(Button.builder(Component.literal("Go"),b -> travel(row))
                .bounds(x+531,ry,45,20).build());
        }
        refresh();
    }

    private void refresh() {
        Minecraft mc=Minecraft.getInstance();
        if (!EarthMarketClient.isEarthMc(mc)) {entries=List.of(); return;}
        List<HeadCatalogue.SavedHead> frames=HeadCatalogue.all();
        String query=search==null?previousQuery:search.getValue();
        query=query.strip().toLowerCase(Locale.ROOT);
        List<EarthMarketClient.DisplayQuote> quotes=EarthMarketClient.displayQuotes();
        String dim=mc.level.dimension().toString();
        List<Entry> found=new ArrayList<>();
        for (HeadCatalogue.SavedHead stored: frames) {
            HeadFrameScanner.Head head=stored.asHead();
            if (!query.isEmpty() && !(head.displayName().toLowerCase(Locale.ROOT).contains(query)
                || head.fingerprint().toLowerCase(Locale.ROOT).contains(query))) continue;
            List<EarthMarketClient.DisplayQuote> nearby=new ArrayList<>();
            for (EarthMarketClient.DisplayQuote q:quotes) {
                if (!"earthmc-sign".equals(q.source()) || !Objects.equals(stored.dimension,q.dimension())) continue;
                // In real EarthMC redstone head shops the sign may sell a placeholder item
                // (e.g. a map). Proximity is evidence of a candidate, NOT proof of linkage.
                int dx=Math.abs(q.x()-head.position().getX());
                int dy=Math.abs(q.y()-head.position().getY());
                int dz=Math.abs(q.z()-head.position().getZ());
                if (dx<=2 && dy<=4 && dz<=2) nearby.add(q);
            }
            nearby.sort(Comparator.comparingInt(q -> distance2(q,head.position())));
            EarthMarketClient.DisplayQuote candidate=null;
            String status="No nearby payment sign";
            if (nearby.size()==1) {candidate=nearby.get(0);status="Possible payment (unverified)";}
            else if (nearby.size()>1) {
                // A unique position-aligned sign is a useful lead, but still just a candidate.
                int best=distance2(nearby.get(0),head.position());
                int second=distance2(nearby.get(1),head.position());
                if (best<second && nearby.get(0).x()==head.position().getX()
                    && nearby.get(0).z()==head.position().getZ()) {
                    candidate=nearby.get(0);status="Aligned payment candidate (unverified)";
                } else status="Multiple payment signs: unconfirmed";
            }
            found.add(new Entry(head,candidate,status,false));
        }
        if(onlyFavorites) {
            found.removeIf(e -> !HeadFavorites.contains(e.head().displayName(),e.head().fingerprint()));
            // Keep favorite products in the wishlist even after their last observed frame is gone.
            for(HeadFavorites.Favorite fav : HeadFavorites.all()) {
                boolean present=found.stream().anyMatch(e -> HeadFavorites.id(e.head().displayName(),e.head().fingerprint()).equals(fav.id));
                if(!present) found.add(new Entry(new HeadFrameScanner.Head(BlockPos.ZERO,fav.name,fav.fingerprint,false),null,"No currently listed head (wishlist)",true));
            }
        }
        found.sort(Comparator.comparing(e -> e.head().displayName().toLowerCase(Locale.ROOT)));
        entries=found;
        updatePage();
    }

    /** Page changes touch only the already prepared rows; no world scan or quote matching. */
    private void updatePage() {
        if(favoritesFilter!=null) favoritesFilter.setMessage(Component.literal(onlyFavorites?"★ Favorites only":"☆ Show favorites"));
        int totalPages=Math.max(1,(entries.size()+ROWS-1)/ROWS);
        page=Math.max(0,Math.min(page,totalPages-1));
        if (backPage!=null) backPage.active=page>0;
        if (nextPage!=null) nextPage.active=page+1<totalPages;
        for(int i=0;i<ROWS;i++) {
            rows[i]=page*ROWS+i<entries.size()?entries.get(page*ROWS+i):null;
            if(details[i]!=null) {details[i].visible=rows[i]!=null;details[i].active=rows[i]!=null;}
            if(go[i]!=null) {go[i].visible=rows[i]!=null;go[i].active=rows[i]!=null && !rows[i].unavailable() && rows[i].payment()!=null;}
            if(highlight[i]!=null) {highlight[i].visible=rows[i]!=null;highlight[i].active=rows[i]!=null && !rows[i].unavailable();}
            if(favoriteButtons[i]!=null) {
                favoriteButtons[i].visible=rows[i]!=null;
                favoriteButtons[i].active=rows[i]!=null;
                if(rows[i]!=null) favoriteButtons[i].setMessage(Component.literal(HeadFavorites.contains(rows[i].head().displayName(), rows[i].head().fingerprint())?"★":"☆"));
            }
        }
    }

    private void toggleFavorite(int row) {
        Entry e=rows[row]; if(e==null)return;
        HeadFavorites.toggle(e.head().displayName(),e.head().fingerprint());
        refresh();
    }
    private static int distance2(EarthMarketClient.DisplayQuote q, BlockPos p) {
        int x=q.x()-p.getX(), y=q.y()-p.getY(), z=q.z()-p.getZ();
        return x*x+y*y+z*z;
    }
    private void info(int row) {
        Entry e=rows[row]; if(e==null || minecraft.player==null) return;
        BlockPos p=e.head().position();
        if(e.unavailable()) {minecraft.player.sendSystemMessage(Component.literal("[EM Market] Wishlisted head: "+e.head().displayName()+" (not currently listed)"));return;}
        minecraft.player.sendSystemMessage(Component.literal("[EM Market] Head: " + e.head().displayName()
            + " | style="+e.head().nameSource()+" color=#"+Integer.toHexString(e.head().nameColor())
            + " | frame="+p.getX()+","+p.getY()+","+p.getZ()
            + " | " + e.status()
            + (e.payment()==null?"":(" | possible payment " + e.payment().bundlePrice()+"G / "
                + e.payment().amount()+"x " +e.payment().item()+" at "
                +e.payment().x()+","+e.payment().y()+","+e.payment().z()))));
    }
    private void mark(int row) {
        Entry e=rows[row]; if(e==null || e.unavailable() || minecraft.player==null || minecraft.level==null)return;
        String dim=minecraft.level.dimension().toString();
        // The frame can be catalogued from a different dimension: find its stored dimension.
        for(HeadCatalogue.SavedHead saved:HeadCatalogue.all()) {
            if(saved.pos().equals(e.head().position()) && saved.name.equals(e.head().displayName())) {
                dim=saved.dimension; break;
            }
        }
        String message=ShopLocator.startHead(e.head().position(),dim);
        minecraft.player.sendSystemMessage(Component.literal("[EM Market] "+message));
        if(message.startsWith("Highlighting")) minecraft.gui.setScreen(null);
    }
    private void travel(int row) {
        Entry e=rows[row]; if(e==null || e.payment()==null) return;
        info(row);
        // This is a navigation lead only: price cannot be treated as a verified head quote.
        CapitalTravelResolver.prepare(minecraft,e.payment().town(),e.payment().nation(),e.payment());
    }
    private static String shorten(String value,int limit) {
        if(value==null)return "";
        return value.length()<=limit?value:value.substring(0,limit-1)+"…";
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        int x=Math.max(4,(width-WIDTH)/2), y=Math.max(4,(height-HEIGHT)/2);
        g.fill(x,y,x+WIDTH,y+HEIGHT,0xF0101821);
        g.outline(x,y,WIDTH,HEIGHT,0xFF658AA0);
        super.extractRenderState(g,mx,my,delta);
        g.text(font,"Head shops | saved frames (auto-pruned when confirmed missing)",x+9,y+8,0xFFFFFFFF,true);
        g.text(font,"Search left; H outlines nearby frame; absent loaded heads removed after 3 checks.",x+9,y+23,0xFFFFBE83,false);
        g.text(font,"Head name / frame XYZ",x+9,y+65,0xFF98D8EF,false);
        g.text(font,"Candidate price / matching state",x+265,y+65,0xFF98D8EF,false);
        for(int i=0;i<ROWS;i++) {
            Entry e=rows[i]; if(e==null)continue;
            int yy=y+85+i*30;
            g.fill(x+5,yy-3,x+WIDTH-5,yy+24,i%2==0?0x66334455:0x44243341);
            BlockPos p=e.head().position();
            g.text(font,shorten(e.head().displayName(),32),x+9,yy,e.head().nameColor(),e.head().nameBold());
            g.text(font,e.unavailable()?"Not currently listed":p.getX()+", "+p.getY()+", "+p.getZ(),x+9,yy+12,0xFFB8C8D2,false);
            var q=e.payment();
            g.text(font,q==null?"No trusted price":String.format(Locale.ROOT,"%.2fG / %dx (%s)",q.bundlePrice(),q.amount(),shorten(q.item(),12)),
                x+265,yy,0xFFFFD17D,false);
            g.text(font,shorten(e.status(),37),x+265,yy+12,0xFFB8C8D2,false);
        }
        g.text(font,"Loaded frames: "+entries.size()+" | page "+(page+1)+"/"+Math.max(1,(entries.size()+ROWS-1)/ROWS),
            x+9,y+HEIGHT-47,0xFFE2E6EB,false);
        g.text(font,"★: wishlist | i: info | H: 18s outline | Go: possible payment",x+9,y+HEIGHT-36,0xFFB8BEC7,false);
    }
    @Override public void onClose() {minecraft.gui.setScreen(previous);}
}
