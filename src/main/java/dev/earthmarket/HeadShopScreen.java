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
import java.util.Map;
import java.util.HashMap;
import net.minecraft.client.gui.screens.ChatScreen;
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
    private final boolean askOnly;
    private final Button[] classifyButtons = new Button[ROWS];
    private int page;
    private final Entry[] rows = new Entry[ROWS];
    private final Button[] details = new Button[ROWS];
    private final Button[] go = new Button[ROWS];
    private final Button[] townButtons = new Button[ROWS];
    private final Button[] nationButtons = new Button[ROWS];
    private Button backPage, nextPage;
    private EditBox search;
    private static String previousQuery = "";
    private final Button[] highlight = new Button[ROWS];
    private final Button[] favoriteButtons = new Button[ROWS];
    private Button favoritesFilter;
    private Button historyFilter;
    private static boolean includeHistory = false;
    private final Map<String,HeadCatalogue.SavedHead> storedIndex = new HashMap<>();
    private static boolean onlyFavorites = false;
    private List<Entry> entries = List.of();
    private List<Entry> catalogueEntries = List.of();

    public HeadShopScreen(Screen previous) {
        super(Component.literal("EarthMC Head Shops"));
        this.previous = previous;
        this.askOnly = false;
    }

    public HeadShopScreen(Screen previous, boolean askOnly) {
        super(Component.literal(askOnly ? "Head Not For Sale / Ask" : "EarthMC Head Shops"));
        this.previous = previous;
        this.askOnly = askOnly;
    }

    record Entry(HeadFrameScanner.Head head, EarthMarketClient.DisplayQuote payment, String status, boolean unavailable) {}

    @Override protected void init() {
        int x = Math.max(4, (width-WIDTH)/2), y = Math.max(4, (height-HEIGHT)/2);
        search = new EditBox(this.font,x+7,y+41,310,19,Component.literal("Search head name"));
        search.setHint(Component.literal("Search head name / fingerprint"));
        search.setValue(previousQuery);
        search.setResponder(value -> { previousQuery=value; page=0; filterEntries(); });
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal(askOnly ? "Shop Heads" : "Ask Heads"),
            b -> minecraft.gui.setScreen(new HeadShopScreen(previous,!askOnly)))
            .bounds(x+472,y+41,109,19).build());
        historyFilter=addRenderableWidget(Button.builder(Component.literal(""), b -> {includeHistory=!includeHistory;page=0;filterEntries();})
            .bounds(x+343,y+HEIGHT-27,140,20).build());
        favoritesFilter = addRenderableWidget(Button.builder(Component.literal(""), b -> {onlyFavorites=!onlyFavorites;page=0;filterEntries();})
            .bounds(x+323,y+41,145,19).build());
        backPage = addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> {page--; updatePage();})
            .bounds(x+7,y+HEIGHT-27,75,20).build());
        nextPage = addRenderableWidget(Button.builder(Component.literal("Next >"), b -> {page++; updatePage();})
            .bounds(x+WIDTH-83,y+HEIGHT-27,75,20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh frames"), b -> {HeadCatalogue.scan(minecraft); rebuildCatalogue();})
            .bounds(x+102,y+HEIGHT-27,130,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> minecraft.gui.setScreen(previous))
            .bounds(x+250,y+HEIGHT-27,82,20).build());
        for (int i=0;i<ROWS;i++) {
            final int row=i;
            int ry=y+83+i*30;
            classifyButtons[i]=addRenderableWidget(Button.builder(Component.literal("Move"),b -> moveCategory(row))
                .bounds(x+367,ry,43,20).build());
            favoriteButtons[i]=addRenderableWidget(Button.builder(Component.literal("☆"),b -> toggleFavorite(row))
                .bounds(x+414,ry,22,20).build());
            details[i]=addRenderableWidget(Button.builder(Component.literal("i"),b -> info(row))
                .bounds(x+440,ry,22,20).build());
            highlight[i]=addRenderableWidget(Button.builder(Component.literal("H"),b -> mark(row))
                .bounds(x+466,ry,22,20).build());
            townButtons[i]=addRenderableWidget(Button.builder(Component.literal("T"),b -> travelExplicit(row,false))
                .bounds(x+492,ry,22,20).build());
            nationButtons[i]=addRenderableWidget(Button.builder(Component.literal("N"),b -> travelExplicit(row,true))
                .bounds(x+518,ry,22,20).build());
            go[i]=addRenderableWidget(Button.builder(Component.literal("Go"),b -> travel(row))
                .bounds(x+544,ry,35,20).build());
        }
        rebuildCatalogue();
    }

    /** A costly world/quote join is done only on opening or explicit refresh, never when typing. */
    private void rebuildCatalogue() {
        Minecraft mc=Minecraft.getInstance();
        if (!EarthMarketClient.isEarthMc(mc) || mc.level==null) {
            catalogueEntries=List.of(); filterEntries(); return;
        }
        List<HeadCatalogue.SavedHead> frames=HeadCatalogue.all();
        storedIndex.clear();
        for(HeadCatalogue.SavedHead frame:frames)storedIndex.put(frame.dimension+":"+frame.x+":"+frame.y+":"+frame.z,frame);
        List<EarthMarketClient.DisplayQuote> quotes=EarthMarketClient.displayQuotes();
        // Spatial bucket index: query just nearby signs, not the entire historical price catalogue.
        Map<String,List<EarthMarketClient.DisplayQuote>> buckets=new HashMap<>();
        for(EarthMarketClient.DisplayQuote q:quotes) {
            if(!"earthmc-sign".equals(q.source()) || q.dimension()==null) continue;
            String key=bucket(q.dimension(),q.x(),q.y(),q.z());
            buckets.computeIfAbsent(key,k->new ArrayList<>()).add(q);
        }
        List<Entry> built=new ArrayList<>(frames.size());
        for(HeadCatalogue.SavedHead stored:frames) {
            HeadFrameScanner.Head head=stored.asHead();
            BlockPos p=head.position();
            List<EarthMarketClient.DisplayQuote> nearby=new ArrayList<>();
            int bx=Math.floorDiv(p.getX(),8),by=Math.floorDiv(p.getY(),8),bz=Math.floorDiv(p.getZ(),8);
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
                String key=stored.dimension+":"+(bx+dx)+":"+(by+dy)+":"+(bz+dz);
                for(EarthMarketClient.DisplayQuote q:buckets.getOrDefault(key,List.of())) {
                    if(Math.abs(q.x()-p.getX())<=5 && Math.abs(q.y()-p.getY())<=5 && Math.abs(q.z()-p.getZ())<=5)
                        nearby.add(q);
                }
            }
            // A redstone head shop is usually a vertical stack: frame above/below payment sign.
            // Favor the exact X/Z column before any nearby sign; never treat diagonal proximity
            // as proof of the payment connection.
            List<EarthMarketClient.DisplayQuote> aligned=new ArrayList<>();
            for(EarthMarketClient.DisplayQuote q:nearby)
                if(q.x()==p.getX() && q.z()==p.getZ() && q.y()!=p.getY()) aligned.add(q);
            EarthMarketClient.DisplayQuote below=closestVertical(aligned,p,true);
            EarthMarketClient.DisplayQuote above=closestVertical(aligned,p,false);
            EarthMarketClient.DisplayQuote candidate=null;
            String status="No aligned payment sign / scan signs nearby";
            if(below!=null && above==null) {
                candidate=below; status="Payment sign below (vertical match; unverified)";
            } else if(above!=null && below==null) {
                candidate=above; status="Payment sign above (vertical match; unverified)";
            } else if(below!=null) {
                // If a head is present *above* the upper sign, that upper sign most likely
                // serves the other head. This head uses the lower sign instead.
                boolean headAboveUpper=false;
                for(HeadCatalogue.SavedHead other:frames) {
                    if(other==stored || !stored.dimension.equals(other.dimension))continue;
                    BlockPos op=other.pos();
                    if(op.getX()==p.getX() && op.getZ()==p.getZ()
                        && op.getY()>above.y() && op.getY()-above.y()<=5) {
                        headAboveUpper=true; break;
                    }
                }
                if(headAboveUpper) {
                    candidate=below;status="Lower sign; upper sign serves head above (inferred)";
                } else {
                    int down=p.getY()-below.y(),up=above.y()-p.getY();
                    if(down<=up) {candidate=below;status="Lower aligned sign preferred (unverified)";}
                    else {candidate=above;status="Nearest upper aligned sign (unverified)";}
                }
            } else if(nearby.size()==1) {
                // A side-by-side head shop can exist, but don't label a nearby sign trusted.
                candidate=nearby.get(0);status="Nearby off-axis payment candidate (unverified)";
            } else if(nearby.size()>1) {
                status="Multiple off-axis payment signs: unconfirmed";
            }
            if(candidate==null)status="Sale unknown; ask owner (no linked payment sign)";
            built.add(new Entry(head,candidate,status,false));
        }
        built.sort(Comparator.comparing(e->e.head().displayName().toLowerCase(Locale.ROOT)));
        catalogueEntries=built;
        filterEntries();
    }
    private static EarthMarketClient.DisplayQuote closestVertical(
        List<EarthMarketClient.DisplayQuote> signs,BlockPos frame,boolean below) {
        EarthMarketClient.DisplayQuote nearest=null;
        int best=Integer.MAX_VALUE;
        for(EarthMarketClient.DisplayQuote q:signs) {
            int dy=below?frame.getY()-q.y():q.y()-frame.getY();
            if(dy<=0 || dy>5)continue;
            if(dy<best) {best=dy;nearest=q;}
        }
        return nearest;
    }
    private static String bucket(String dimension,int x,int y,int z) {
        return dimension+":"+Math.floorDiv(x,8)+":"+Math.floorDiv(y,8)+":"+Math.floorDiv(z,8);
    }
    /** Search, favorites and paging operate on cached rows, no world scans or quote joins. */
    private void filterEntries() {
        String query=(search==null?previousQuery:search.getValue()).strip().toLowerCase(Locale.ROOT);
        List<Entry> found=new ArrayList<>();
        for(Entry e:catalogueEntries) {
            String dim=storedDimension(e);
            String choice=dim==null?"":HeadSaleStatus.get(dim,e.head().position(),e.head().fingerprint());
            boolean asking="ask".equals(choice) || (!"sale".equals(choice) && e.payment()==null);
            if(!includeHistory) {
                HeadCatalogue.SavedHead h=storedRecord(e);
                if(h==null || !h.dimension.equals(minecraft.level.dimension().toString()) ||
                    System.currentTimeMillis()-h.lastSeen>60_000L ||
                    minecraft.player.distanceToSqr(h.x+.5,h.y+.5,h.z+.5)>48*48)continue;
            }
            if(asking!=askOnly)continue;
            if(!query.isEmpty() && !e.head().displayName().toLowerCase(Locale.ROOT).contains(query)
                && !e.head().fingerprint().toLowerCase(Locale.ROOT).contains(query))continue;
            if(onlyFavorites && !HeadFavorites.contains(e.head().displayName(),e.head().fingerprint()))continue;
            found.add(e);
        }
        if(onlyFavorites && askOnly && includeHistory) for(HeadFavorites.Favorite fav:HeadFavorites.all()) {
            if(!query.isEmpty() && !fav.name.toLowerCase(Locale.ROOT).contains(query))continue;
            boolean present=false;
            for(Entry e:found) if(HeadFavorites.id(e.head().displayName(),e.head().fingerprint()).equals(fav.id)) {present=true;break;}
            if(!present)found.add(new Entry(new HeadFrameScanner.Head(BlockPos.ZERO,fav.name,fav.fingerprint,false),null,
                "No currently listed head (wishlist)",true));
        }
        entries=found;
        updatePage();
    }

    /** Page changes touch only the already prepared rows; no world scan or quote matching. */
    private void updatePage() {
        if(historyFilter!=null) historyFilter.setMessage(Component.literal(includeHistory?"History: all":"Nearby: seen now"));
        if(favoritesFilter!=null) favoritesFilter.setMessage(Component.literal(onlyFavorites?"★ Favorites only":"☆ Show favorites"));
        int totalPages=Math.max(1,(entries.size()+ROWS-1)/ROWS);
        page=Math.max(0,Math.min(page,totalPages-1));
        if (backPage!=null) backPage.active=page>0;
        if (nextPage!=null) nextPage.active=page+1<totalPages;
        for(int i=0;i<ROWS;i++) {
            rows[i]=page*ROWS+i<entries.size()?entries.get(page*ROWS+i):null;
            if(classifyButtons[i]!=null) {
                classifyButtons[i].visible=rows[i]!=null && !rows[i].unavailable();
                classifyButtons[i].active=classifyButtons[i].visible;
                classifyButtons[i].setMessage(Component.literal(askOnly?"Sale":"Ask"));
            }
            if(details[i]!=null) {details[i].visible=rows[i]!=null;details[i].active=rows[i]!=null;}
            if(go[i]!=null) {go[i].visible=rows[i]!=null;go[i].active=rows[i]!=null && !rows[i].unavailable() && (knownTown(rows[i]) || knownNation(rows[i]));}
            if(townButtons[i]!=null) {townButtons[i].visible=rows[i]!=null;townButtons[i].active=rows[i]!=null && !rows[i].unavailable() && knownTown(rows[i]);}
            if(nationButtons[i]!=null) {nationButtons[i].visible=rows[i]!=null;nationButtons[i].active=rows[i]!=null && !rows[i].unavailable() && knownNation(rows[i]);}
            if(highlight[i]!=null) {highlight[i].visible=rows[i]!=null;highlight[i].active=rows[i]!=null && !rows[i].unavailable();}
            if(favoriteButtons[i]!=null) {
                favoriteButtons[i].visible=rows[i]!=null;
                favoriteButtons[i].active=rows[i]!=null;
                if(rows[i]!=null) favoriteButtons[i].setMessage(Component.literal(HeadFavorites.contains(rows[i].head().displayName(), rows[i].head().fingerprint())?"★":"☆"));
            }
        }
    }

    private void moveCategory(int row) {
        Entry e=rows[row]; if(e==null || e.unavailable())return;
        String dim=storedDimension(e);
        if(dim==null)return;
        HeadSaleStatus.set(dim,e.head().position(),e.head().fingerprint(),askOnly?"sale":"ask");
        filterEntries();
    }
    private void toggleFavorite(int row) {
        Entry e=rows[row]; if(e==null)return;
        HeadFavorites.toggle(e.head().displayName(),e.head().fingerprint());
        filterEntries();
    }
    private HeadCatalogue.SavedHead storedRecord(Entry e) {
        if(e==null || e.unavailable())return null;
        // Avoid a whole-catalogue traversal on each button refresh.
        for(HeadCatalogue.SavedHead h:storedIndex.values()) {
            if(h.pos().equals(e.head().position()) && Objects.equals(h.fingerprint,e.head().fingerprint())) return h;
        }
        return null;
    }
    private boolean knownTown(Entry e) {return validName(travelTown(e));}
    private boolean knownNation(Entry e) {return validName(travelNation(e));}
    private String travelTown(Entry e) {
        HeadCatalogue.SavedHead h=storedRecord(e);
        return h!=null && validName(h.town)?h.town:(e.payment()==null?null:e.payment().town());
    }
    private String travelNation(Entry e) {
        HeadCatalogue.SavedHead h=storedRecord(e);
        return h!=null && validName(h.nation)?h.nation:(e.payment()==null?null:e.payment().nation());
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
            + " | " + e.status() + " | payment mapping is proximity-only and NOT a verified head sale"
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
        minecraft.gui.setScreen(null);
        String message=ShopLocator.startHead(e.head().position(),dim);
        minecraft.player.sendSystemMessage(Component.literal("[EM Market] "+message));
        if(!message.startsWith("Highlighting")) {
            NavigationGuide.setTarget(PendingShopTravel.headQuote(e.head().position(),dim,e.head().displayName()));
            minecraft.player.sendSystemMessage(Component.literal("[EM Market] Head tracer active; move toward the frame."));
        }
    }
    private static boolean validName(String name) {
        return name!=null && !name.isBlank() && !"unknown".equalsIgnoreCase(name)
            && name.codePoints().noneMatch(c->Character.isWhitespace(c) || Character.isISOControl(c));
    }
    /** T/N never guess a capital. Still arm the exact head frame as arrival target. */
    private void travelExplicit(int row, boolean nation) {
        Entry e=rows[row];
        if(e==null || e.unavailable())return;
        String destination=nation?travelNation(e):travelTown(e);
        if(!validName(destination))return;
        String dimension=storedDimension(e);
        if(dimension==null)return;
        String command=(nation?"/n spawn ":"/t spawn ")+destination;
        minecraft.gui.setScreen(null);
        PendingShopTravel.armHead(e.head().position(),dimension,e.head().displayName(),command);
        minecraft.gui.setScreen(new ChatScreen(command,false));
    }
    private String storedDimension(Entry e) {
        HeadCatalogue.SavedHead saved=storedRecord(e);
        return saved==null?null:saved.dimension;
    }
    private void travel(int row) {
        Entry e=rows[row]; if(e==null) return;
        String dim=storedDimension(e);
        if(dim==null) {info(row);return;}
        // Close our screen first; the capital API callback will open the chat draft.
        minecraft.gui.setScreen(null);
        // The payment sign is only a travel clue, never the highlighted target.
        String town=travelTown(e),nation=travelNation(e);
        if(!validName(town) && !validName(nation)) {
            minecraft.player.sendSystemMessage(Component.literal("[EM Market] Location unavailable. Visit this head and refresh to resolve town/nation."));
            return;
        }
        EarthMarketClient.DisplayQuote clue=e.payment()!=null?e.payment():PendingShopTravel.headQuote(e.head().position(),dim,e.head().displayName());
        CapitalTravelResolver.prepare(minecraft,town,nation,clue,
            e.head().position(),dim,e.head().displayName());
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
        g.text(font,askOnly?"Head Not For Sale / Ask | unlinked or manually flagged":"Head shops | payment candidates (not verified)",x+9,y+8,0xFFFFFFFF,true);
        g.text(font,askOnly?"Ask the owner before assuming a decorative head is for sale.":"H outlines a frame; Move changes sale/ask category.",x+9,y+23,0xFFFFBE83,false);
        g.text(font,"Head name / XYZ",x+9,y+65,0xFF98D8EF,false);
        g.text(font,"Candidate price / matching state",x+265,y+65,0xFF98D8EF,false);
        for(int i=0;i<ROWS;i++) {
            Entry e=rows[i]; if(e==null)continue;
            int yy=y+85+i*30;
            g.fill(x+5,yy-3,x+WIDTH-5,yy+24,i%2==0?0x66334455:0x44243341);
            BlockPos p=e.head().position();
            g.text(font,shorten(e.head().displayName(),32),x+9,yy,e.head().nameColor(),e.head().nameBold());
            HeadCatalogue.SavedHead stored=storedRecord(e);
            String locality=stored==null?"":(validName(stored.town)?"  "+stored.town:stored.wilderness?"  Wilderness":"  Location pending");
            g.text(font,e.unavailable()?"Not currently listed":p.getX()+", "+p.getY()+", "+p.getZ()+locality,x+9,yy+12,0xFFB8C8D2,false);
            var q=e.payment();
            g.text(font,q==null?"Price not linked":String.format(Locale.ROOT,"%.2fG / %dx (%s)",q.bundlePrice(),q.amount(),shorten(q.item(),12)),
                x+265,yy,0xFFFFD17D,false);
            g.text(font,shorten(e.status(),37),x+265,yy+12,0xFFB8C8D2,false);
        }
        g.text(font,"Heads: "+entries.size()+" | page "+(page+1)+"/"+Math.max(1,(entries.size()+ROWS-1)/ROWS),
            x+9,y+HEIGHT-47,0xFFE2E6EB,false);
        g.text(font,"Move: switch category | ★: save | H: frame | T/N/Go: if location known",x+9,y+HEIGHT-36,0xFFB8BEC7,false);
    }
    @Override public void onClose() {minecraft.gui.setScreen(previous);}
}
