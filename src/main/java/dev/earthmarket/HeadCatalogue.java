package dev.earthmarket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persistent catalogue of observed frame heads, not a claim about shop stock. */
public final class HeadCatalogue {
    private static final Path FILE = Path.of("config", "earthmc-market", "head-frames.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final class SavedHead {
        public String dimension;
        public int x,y,z;
        public String name, fingerprint;
        public String manualName;
        public boolean specific;
        public int nameColor;
        public boolean nameBold;
        public String nameSource;
        public long lastSeen;
        public String town, nation;
        public boolean wilderness;
        public SavedHead() {}
        SavedHead(String dim, HeadFrameScanner.Head head) {
            dimension=dim; x=head.position().getX(); y=head.position().getY(); z=head.position().getZ();
            name=head.displayName(); fingerprint=head.fingerprint(); specific=head.specific();
            nameColor=head.nameColor(); nameBold=head.nameBold(); nameSource=head.nameSource();
            lastSeen=System.currentTimeMillis();
        }
        public BlockPos pos() { return new BlockPos(x,y,z); }
        public HeadFrameScanner.Head asHead() { return new HeadFrameScanner.Head(pos(),manualName==null || manualName.isBlank()?name:manualName,fingerprint,specific,nameColor==0?0xFF82E7B4:nameColor,nameBold,nameSource==null?"legacy":nameSource); }
    }
    private static final Map<String,SavedHead> saved = new HashMap<>();
    private static final Map<String,Integer> missing = new HashMap<>();
    private static final Map<String,Long> locationRequests = new HashMap<>();
    private static int tickCounter;
    private static boolean dirty;
    private static boolean detailedScan;
    private static boolean blockScanActive;
    private static BlockPos scanOrigin;
    private static String scanDimension;
    private static int scanIndex, scanDiscovered, scanWalls, scanFloor, scanAdded, scanUpdated;
    private static final int RADIUS=16, HALF_HEIGHT=10, SIDE=RADIUS*2+1;
    private static final int TOTAL=SIDE*SIDE*(HALF_HEIGHT*2+1);
    private static final int BLOCK_BUDGET_PER_TICK=450;

    private static long lastWriteAt;
    private static final long SAVE_DEBOUNCE_MS = 8_000L;
    private HeadCatalogue() {}
    private static String key(String dim, BlockPos p) { return dim+":"+p.getX()+":"+p.getY()+":"+p.getZ(); }
    public static void load() {
        try {
            if (!Files.isRegularFile(FILE)) return;
            try (Reader r=Files.newBufferedReader(FILE)) {
                SavedHead[] entries=GSON.fromJson(r,SavedHead[].class);
                if(entries!=null) for(SavedHead h:entries)
                    if(h!=null && h.dimension!=null && h.name!=null) saved.put(key(h.dimension,h.pos()),h);
            }
        } catch (Exception e) { System.err.println("[EM Market] Head catalogue load error: "+e); }
    }
    private static void write() {
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp=FILE.resolveSibling("head-frames.json.tmp");
            try (Writer w=Files.newBufferedWriter(tmp)) { GSON.toJson(saved.values(),w); }
            try { Files.move(tmp,FILE,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp,FILE,StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { System.err.println("[EM Market] Head catalogue save error: "+e); }
    }
    public static List<SavedHead> all() { return new ArrayList<>(saved.values()); }
    public static SavedHead get(String dimension,BlockPos position) {return saved.get(key(dimension,position));}
    public static void tick(Minecraft mc) {
        if (mc==null || mc.player==null || mc.level==null || !EarthMarketClient.isEarthMc(mc)) return;
        // Five seconds per scan, not two. This is a separate scanner from QuickShop signs.
        if (++tickCounter % 100 == 0) {
            scan(mc);
            if(!blockScanActive)startBlockScan(mc,false);
        }
        processBlockScan(mc);
        if (dirty && System.currentTimeMillis() - lastWriteAt >= SAVE_DEBOUNCE_MS) {
            write(); dirty = false; lastWriteAt = System.currentTimeMillis();
        }
    }
    /** Manually starts a bounded, non-blocking scan; completes over multiple ticks. */
    public static void manualScan(Minecraft mc) {
        if(mc==null || mc.player==null || mc.level==null || !EarthMarketClient.isEarthMc(mc))return;
        scan(mc);
        startBlockScan(mc,true);
        message(mc,"Wall/floor head scan started: 33x33 blocks, height +/-10. Results in chat when complete.");
    }
    private static void message(Minecraft mc,String text) {
        if(mc.player!=null)mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[EM Market] "+text));
    }
    private static void startBlockScan(Minecraft mc,boolean detailed) {
        if(mc.player==null || mc.level==null)return;
        scanOrigin=mc.player.blockPosition();
        scanDimension=mc.level.dimension().toString();
        scanIndex=scanDiscovered=scanWalls=scanFloor=scanAdded=scanUpdated=0;
        detailedScan=detailed;
        blockScanActive=true;
    }
    private static void processBlockScan(Minecraft mc) {
        if(!blockScanActive || scanOrigin==null)return;
        if(mc.player==null || mc.level==null || !scanDimension.equals(mc.level.dimension().toString())) {
            blockScanActive=false;return;
        }
        // Player moved away: discard stale work; next periodic scan uses the new position.
        if(mc.player.distanceToSqr(scanOrigin.getX()+.5,mc.player.getY(),scanOrigin.getZ()+.5)>32*32) {
            blockScanActive=false;return;
        }
        int remaining=BLOCK_BUDGET_PER_TICK;
        while(scanIndex<TOTAL && remaining-->0) {
            int n=scanIndex++;
            int horizontal=n%(SIDE*SIDE), dy=n/(SIDE*SIDE)-HALF_HEIGHT;
            int dx=horizontal%SIDE-RADIUS, dz=horizontal/SIDE-RADIUS;
            BlockPos p=scanOrigin.offset(dx,dy,dz);
            if(!mc.level.hasChunkAt(p))continue;
            // Checking the block state is cheap; retrieving a block entity happens ONLY for skulls.
            var state=mc.level.getBlockState(p);
            boolean wall=state.is(net.minecraft.world.level.block.Blocks.PLAYER_WALL_HEAD);
            if(!wall && !state.is(net.minecraft.world.level.block.Blocks.PLAYER_HEAD))continue;
            HeadFrameScanner.Head head=HeadFrameScanner.blockAt(mc,p);
            if(head==null)continue;
            scanDiscovered++;
            if(wall)scanWalls++;else scanFloor++;
            String k=key(scanDimension,p);
            SavedHead old=saved.get(k);
            if(old==null)scanAdded++;
            else if(!java.util.Objects.equals(head.fingerprint(),old.fingerprint) ||
                    !java.util.Objects.equals(head.displayName(),old.name))scanUpdated++;
            recordHead(mc,scanDimension,head);
        }
        if(scanIndex>=TOTAL) {
            blockScanActive=false;
            if(detailedScan)message(mc,"Block scan complete: wall heads="+scanWalls+
                ", floor heads="+scanFloor+", discovered="+scanDiscovered+
                ", new="+scanAdded+", updated="+scanUpdated+
                ". Head blocks without profile data are still recorded. Open Ask Heads -> Nearby.");
            detailedScan=false;
        }
    }
    private static void recordHead(Minecraft mc,String dimension,HeadFrameScanner.Head head) {
        String k=key(dimension,head.position());
        SavedHead old=saved.get(k);
        if(old==null || !java.util.Objects.equals(old.name,head.displayName())
           || !java.util.Objects.equals(old.fingerprint,head.fingerprint())
           || old.nameColor!=head.nameColor() || old.nameBold!=head.nameBold()
           || !java.util.Objects.equals(old.nameSource,head.nameSource())) {
            SavedHead updated=new SavedHead(dimension,head);
            if(old!=null){ updated.town=old.town;updated.nation=old.nation;updated.wilderness=old.wilderness;
                if(java.util.Objects.equals(old.fingerprint,head.fingerprint()) || head.fingerprint().isBlank() || old.fingerprint==null || old.fingerprint.isBlank())updated.manualName=old.manualName; }
            saved.put(k,updated);dirty=true;
        } else old.lastSeen=System.currentTimeMillis();
        missing.remove(k);
        requestLocation(mc,dimension,head);
    }

    /** Resolve placed heads as well as frames; manual block scan must not bypass this. */
    private static void requestLocation(Minecraft mc,String dimension,HeadFrameScanner.Head head) {
        String k=key(dimension,head.position());
        SavedHead record=saved.get(k);
        if(record==null || record.wilderness || record.town!=null || record.nation!=null)return;
        long now=System.currentTimeMillis();
        if(now-locationRequests.getOrDefault(k,0L)<120_000L)return;
        locationRequests.put(k,now);
        EarthLocationResolver.resolve(head.position().getX(),head.position().getZ(),result -> {
            if(!result.ok())return;
            mc.execute(() -> {
                SavedHead current=saved.get(k);
                if(current!=null && (java.util.Objects.equals(current.fingerprint,head.fingerprint())
                    || head.fingerprint().isBlank())) {
                    current.town=result.town();current.nation=result.nation();
                    current.wilderness=result.wilderness();dirty=true;
                }
            });
        });
    }

    /** A manually assigned label applies to this recorded head, not all same-named heads. */
    public static void rename(SavedHead head,String text) {
        if(head==null)return;
        String clean=text==null?"":text.strip();
        if(clean.length()>80)clean=clean.substring(0,80);
        SavedHead current=saved.get(key(head.dimension,head.pos()));
        if(current==null)return;
        current.manualName=clean.isBlank()?null:clean;
        dirty=true;
    }
    public static void scan(Minecraft mc) {
        if(mc==null || mc.level==null || mc.player==null || !EarthMarketClient.isEarthMc(mc)) return;
        String dimension=mc.level.dimension().toString();
        boolean changed=false;
        Set<String> observed=new HashSet<>();
        List<HeadFrameScanner.Head> seenHeads=new ArrayList<>(HeadFrameScanner.nearby(mc));
        seenHeads.addAll(HeadFrameScanner.nearbyBlocks(mc));
        for(HeadFrameScanner.Head head:seenHeads) {
            String key=key(dimension,head.position());
            observed.add(key);
            SavedHead old=saved.get(key);
            if(old==null || !head.displayName().equals(old.name) || !head.fingerprint().equals(old.fingerprint)
                || head.nameColor()!=old.nameColor || head.nameBold()!=old.nameBold
                || !java.util.Objects.equals(head.nameSource(),old.nameSource)) {
                SavedHead updated=new SavedHead(dimension,head);
                if(old!=null){updated.town=old.town;updated.nation=old.nation;updated.wilderness=old.wilderness;
                    if(java.util.Objects.equals(old.fingerprint,head.fingerprint()) || head.fingerprint().isBlank() || old.fingerprint==null || old.fingerprint.isBlank())updated.manualName=old.manualName;}
                saved.put(key,updated); changed=true;
            } else old.lastSeen=System.currentTimeMillis();
            missing.remove(key);
            SavedHead record=saved.get(key);
            if(record!=null && record.lastSeen>0 && record.town==null && record.nation==null && !record.wilderness
               && System.currentTimeMillis()-locationRequests.getOrDefault(key,0L)>120_000L) {
                locationRequests.put(key,System.currentTimeMillis());
                // Location API resolves off the render thread. Do not submit the entire history.
                EarthLocationResolver.resolve(head.position().getX(),head.position().getZ(),result -> {
                    if(!result.ok())return;
                    mc.execute(() -> {
                        SavedHead now=saved.get(key);
                        if(now!=null && java.util.Objects.equals(now.fingerprint,head.fingerprint())) {
                            now.town=result.town();now.nation=result.nation();now.wilderness=result.wilderness();dirty=true;
                        }
                    });
                });
            }
        }
        // Confirm disappearance only while the exact chunk is loaded and the player is close.
        // Require 3 observations (spaced 2s) to tolerate short entity sync delays.
        for(SavedHead head:new ArrayList<>(saved.values())) {
            if(!dimension.equals(head.dimension)) continue;
            BlockPos p=head.pos();
            if(mc.player.distanceToSqr(p.getX()+.5,p.getY()+.5,p.getZ()+.5)>20*20 || !mc.level.hasChunkAt(p)) { missing.remove(key(dimension,p)); continue; }
            String key=key(dimension,p);
            if(observed.contains(key)) continue;
            // The one-pass frame scan already populated observed. Avoid re-enumerating
            // ALL rendering entities once for every saved head (quadratic in busy shops).
            // Block heads may not appear in the chunk's block-entity map at all.
            // Only remove when the block itself is no longer a player head.
            if(head.nameSource!=null && head.nameSource.startsWith("placed-")) {
                var state=mc.level.getBlockState(p);
                if(state.is(net.minecraft.world.level.block.Blocks.PLAYER_HEAD)
                   ||state.is(net.minecraft.world.level.block.Blocks.PLAYER_WALL_HEAD))continue;
            }
            int count=missing.merge(key,1,Integer::sum);
            if(count>=3){ saved.remove(key); missing.remove(key); changed=true; }
        }
        if(changed) dirty = true;
    }
}
