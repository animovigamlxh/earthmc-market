package dev.earthmarket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** Persistent wishlist keyed by item identity, deliberately independent of item-frame coordinates. */
public final class HeadFavorites {
    private static final Path FILE = Path.of("config", "earthmc-market", "head-favorites.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final class Favorite {
        public String id;
        public String name;
        public String fingerprint;
        public long savedAt;
        public String dimension, town, nation;
        public Integer x,y,z;
        public Favorite() {}
        Favorite(String id, String name, String fingerprint) {
            this.id=id; this.name=name; this.fingerprint=fingerprint; savedAt=System.currentTimeMillis();
        }
    }
    private static final Map<String,Favorite> favorites = new LinkedHashMap<>();
    private HeadFavorites() {}
    public static String id(String name, String fingerprint) {
        if(fingerprint != null && !fingerprint.isBlank()) return "fingerprint:" + fingerprint;
        return "name:" + (name == null ? "" : name.strip().toLowerCase(Locale.ROOT));
    }
    public static boolean contains(String name, String fingerprint) {
        return favorites.containsKey(id(name,fingerprint));
    }
    public static String town(String name,String fingerprint) {
        Favorite f=favorites.get(id(name,fingerprint));return f==null?null:f.town;
    }
    public static String nation(String name,String fingerprint) {
        Favorite f=favorites.get(id(name,fingerprint));return f==null?null:f.nation;
    }
    public static void updateLocation(String name,String fingerprint,String town,String nation) {
        Favorite f=favorites.get(id(name,fingerprint));if(f==null)return;
        if(java.util.Objects.equals(f.town,town) && java.util.Objects.equals(f.nation,nation))return;
        if(town!=null)f.town=town;
        if(nation!=null)f.nation=nation;
        save();
    }
    public static void toggle(String name,String fingerprint,String dimension,net.minecraft.core.BlockPos pos,String town,String nation) {
        String key=id(name,fingerprint);
        if(favorites.containsKey(key))favorites.remove(key);
        else {
            Favorite f=new Favorite(key,name,fingerprint);
            f.dimension=dimension;f.town=town;f.nation=nation;
            if(pos!=null){f.x=pos.getX();f.y=pos.getY();f.z=pos.getZ();}
            favorites.put(key,f);
        }
        save();
    }
    public static void toggle(String name, String fingerprint) {
        String key=id(name,fingerprint);
        if(favorites.containsKey(key)) favorites.remove(key);
        else favorites.put(key,new Favorite(key,name,fingerprint));
        save();
    }
    public static List<Favorite> all() {return new ArrayList<>(favorites.values());}
    public static void load() {
        favorites.clear();
        try {
            if(!Files.isRegularFile(FILE)) return;
            try(Reader r=Files.newBufferedReader(FILE)) {
                Favorite[] entries=GSON.fromJson(r,Favorite[].class);
                if(entries!=null) for(Favorite entry:entries) {
                    if(entry!=null && entry.id!=null && entry.name!=null) favorites.put(entry.id,entry);
                }
            }
        } catch(Exception ex) {System.err.println("[EM Market] Favorites load error: "+ex);}
    }
    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp=FILE.resolveSibling("head-favorites.json.tmp");
            try(Writer w=Files.newBufferedWriter(tmp)) {GSON.toJson(favorites.values(),w);}
            try {Files.move(tmp,FILE,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(java.nio.file.AtomicMoveNotSupportedException ex) {Files.move(tmp,FILE,StandardCopyOption.REPLACE_EXISTING);}
        } catch(Exception ex) {System.err.println("[EM Market] Favorites save error: "+ex);}
    }
}
