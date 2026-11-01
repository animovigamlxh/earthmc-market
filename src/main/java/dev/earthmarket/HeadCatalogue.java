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
        public boolean specific;
        public long lastSeen;
        public SavedHead() {}
        SavedHead(String dim, HeadFrameScanner.Head head) {
            dimension=dim; x=head.position().getX(); y=head.position().getY(); z=head.position().getZ();
            name=head.displayName(); fingerprint=head.fingerprint(); specific=head.specific();
            lastSeen=System.currentTimeMillis();
        }
        public BlockPos pos() { return new BlockPos(x,y,z); }
        public HeadFrameScanner.Head asHead() { return new HeadFrameScanner.Head(pos(),name,fingerprint,specific); }
    }
    private static final Map<String,SavedHead> saved = new HashMap<>();
    private static final Map<String,Integer> missing = new HashMap<>();
    private static int tickCounter;
    private static boolean dirty;
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
    public static void tick(Minecraft mc) {
        if (mc==null || mc.player==null || mc.level==null || !EarthMarketClient.isEarthMc(mc)) return;
        // Five seconds per scan, not two. This is a separate scanner from QuickShop signs.
        if (++tickCounter % 100 == 0) scan(mc);
        if (dirty && System.currentTimeMillis() - lastWriteAt >= SAVE_DEBOUNCE_MS) {
            write(); dirty = false; lastWriteAt = System.currentTimeMillis();
        }
    }
    public static void scan(Minecraft mc) {
        if(mc==null || mc.level==null || mc.player==null || !EarthMarketClient.isEarthMc(mc)) return;
        String dimension=mc.level.dimension().toString();
        boolean changed=false;
        Set<String> observed=new HashSet<>();
        for(HeadFrameScanner.Head head:HeadFrameScanner.nearby(mc)) {
            String key=key(dimension,head.position());
            observed.add(key);
            SavedHead old=saved.get(key);
            if(old==null || !head.displayName().equals(old.name) || !head.fingerprint().equals(old.fingerprint)) {
                saved.put(key,new SavedHead(dimension,head)); changed=true;
            } else old.lastSeen=System.currentTimeMillis();
            missing.remove(key);
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
            int count=missing.merge(key,1,Integer::sum);
            if(count>=3){ saved.remove(key); missing.remove(key); changed=true; }
        }
        if(changed) dirty = true;
    }
}
