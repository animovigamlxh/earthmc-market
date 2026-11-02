package dev.earthmarket;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Parses the separate Enchantments section emitted after an EarthMC QuickShop inspection. */
public final class EnchantChatParser {
    public EnchantChatParser() {}
    private static final Pattern COLORS = Pattern.compile("§[0-9a-fk-orA-FK-OR]");
    private static final Pattern HEADER = Pattern.compile("(?i)\\bEnchantments\\b|附魔(?:列表|属性|信息)");
    private static final Pattern LEVEL = Pattern.compile("(?iu)^.{1,65}?\\s+(?:[IVXLCDM]{1,8}|[1-9][0-9]?)$");
    private final List<String> entries = new ArrayList<>();
    private boolean reading;

    public boolean started() { return reading; }
    public boolean hasEntries() { return !entries.isEmpty(); }
    public String details() { return String.join(", ", entries); }
    public void reset() { reading = false; entries.clear(); }

    /** Feed one Minecraft chat component; return true when a new enchant entry was added. */
    public boolean accept(String raw) {
        boolean changed = false;
        for (String line : COLORS.matcher(raw).replaceAll("").split("\\R")) {
            String cleaned = line.trim();
            if (HEADER.matcher(cleaned).find()) {
                reading = true;
                entries.clear();
                continue;
            }
            if (!reading) continue;
            String candidate = cleaned.replaceAll("^[+┃│|\\-─═\\s]+", "")
                                      .replaceAll("[+┃│|\\-─═\\s]+$", "").trim();
            if (candidate.isEmpty()) continue;
            // End boundary: separator/footer of the enchanting section.
            if (candidate.matches(".*(?:商店信息|所有者\\s*[:：]|物品\\s*[:：]).*")) { reading = false; break; }
            if (LEVEL.matcher(candidate).matches() && !entries.contains(candidate) && entries.size() < 24) {
                entries.add(candidate);
                changed = true;
            }
        }
        return changed;
    }
}
