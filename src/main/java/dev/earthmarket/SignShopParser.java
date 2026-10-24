package dev.earthmarket;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative Chinese QuickShop sign parsing. No stock assertion from sign text. */
public final class SignShopParser {
    private static final Pattern DIRECTION = Pattern.compile("^(出售|收购|SELL|BUY)\\s*(?:\\d+)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE = Pattern.compile("^(?:每\\s*)?(\\d{1,6})\\s*(?:[xX×个件])?\\s*[,，:：]?\\s*(?:价格)?\\s*(\\d+(?:\\.\\d+)?)\\s*[Gg]$", Pattern.CASE_INSENSITIVE);
    public record Result(String owner, String type, String item, int amount, double gold) {}
    private SignShopParser() {}
    public static Result parse(String[] lines) {
        if (lines == null || lines.length != 4) return null;
        String owner = trim(lines[0]), action = trim(lines[1]), item = trim(lines[2]), price = trim(lines[3]);
        Matcher dir = DIRECTION.matcher(action);
        Matcher money = PRICE.matcher(price.replace(" ", ""));
        if (!dir.matches() || !money.matches()) return null;
        if (owner.isBlank() || owner.length() > 40 || item.isBlank() || item.length() > 100) return null;
        // Reject accidental matches on instructions or ordinary public signs.
        if (owner.contains(" ") || owner.contains("：") || owner.contains(":")) return null;
        try {
            int amount = Integer.parseInt(money.group(1));
            double gold = Double.parseDouble(money.group(2));
            if (amount < 1 || amount > 100000 || !(gold > 0) || !Double.isFinite(gold) || gold > 1e9) return null;
            String type = switch (dir.group(1).toUpperCase(Locale.ROOT)) {
                case "出售", "SELL" -> "sell";
                case "收购", "BUY" -> "buy";
                default -> null;
            };
            return type == null ? null : new Result(owner, type, item, amount, gold);
        } catch (NumberFormatException e) { return null; }
    }
    private static String trim(String input) {
        return input == null ? "" : input.replaceAll("§[0-9a-fk-orA-FK-OR]", "").strip();
    }
}
