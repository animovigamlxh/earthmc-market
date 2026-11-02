package dev.earthmarket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/** User classification of an observed frame, independent of automatic payment guesses. */
public final class HeadSaleStatus {
    private static final Path FILE = Path.of("config", "earthmc-market", "head-sale-status.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String,String> statuses = new HashMap<>();
    private static boolean loaded;
    private HeadSaleStatus() {}
    private static String key(String dimension, BlockPos pos, String fingerprint) {
        return dimension+":"+pos.getX()+":"+pos.getY()+":"+pos.getZ()+":"+(fingerprint==null?"":fingerprint);
    }
    private static synchronized void load() {
        if(loaded)return;
        loaded=true;
        if(!Files.isRegularFile(FILE))return;
        try(Reader r=Files.newBufferedReader(FILE)) {
            @SuppressWarnings("unchecked") Map<String,String> values=GSON.fromJson(r,Map.class);
            if(values!=null)statuses.putAll(values);
        }catch(Exception e){System.err.println("[EM Market] Head classifications load failed: "+e);}
    }
    public static synchronized String get(String dimension, BlockPos pos, String fingerprint) {
        load();return statuses.getOrDefault(key(dimension,pos,fingerprint),"");
    }
    public static synchronized void set(String dimension, BlockPos pos, String fingerprint, String status) {
        load();String k=key(dimension,pos,fingerprint);
        if(!"ask".equals(status) && !"sale".equals(status))statuses.remove(k);
        else statuses.put(k,status);
        try {
            Files.createDirectories(FILE.getParent());
            Path temp=FILE.resolveSibling(FILE.getFileName()+".tmp");
            try(Writer w=Files.newBufferedWriter(temp)){GSON.toJson(statuses,w);}
            try{Files.move(temp,FILE,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(java.nio.file.AtomicMoveNotSupportedException ignored){Files.move(temp,FILE,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception e){System.err.println("[EM Market] Head classifications save failed: "+e);}
    }
}
