package dev.earthmarket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small persistent view preferences, separate from price observations. */
final class GuiPreferences {
    private static final Path FILE = Path.of("config", "earthmc-market", "gui-state.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final class State {
        String marketQuery = "";
        String marketFilter = "ALL";
        boolean marketAscending = true;
        int marketStockFilter = 0;
        int marketPage = 0;
        String arbitrageQuery = "";
        boolean arbitrageIncludeUnknown = true;
        int arbitragePage = 0;
    }
    private static State state;
    static synchronized State get() {
        if (state != null) return state;
        state = new State();
        try {
            if (Files.isRegularFile(FILE)) {
                try (Reader r = Files.newBufferedReader(FILE)) {
                    State read = GSON.fromJson(r, State.class);
                    if (read != null) state = read;
                }
            }
        } catch (Exception ignored) { state = new State(); }
        if (state.marketQuery == null) state.marketQuery = "";
        if (state.marketFilter == null || !java.util.List.of("ALL", "SELL", "BUY").contains(state.marketFilter)) state.marketFilter = "ALL";
        if (state.marketStockFilter < 0 || state.marketStockFilter > 3) state.marketStockFilter = 0;
        if (state.arbitrageQuery == null) state.arbitrageQuery = "";
        state.marketPage = Math.max(0, state.marketPage);
        state.arbitragePage = Math.max(0, state.arbitragePage);
        return state;
    }
    static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling("gui-state.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) { GSON.toJson(get(), w); }
            Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { System.err.println("[EarthMarket] GUI settings save failed: " + e); }
    }
}
