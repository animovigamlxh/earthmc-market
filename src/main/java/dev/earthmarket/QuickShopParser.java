package dev.earthmarket;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java parser for the Chinese QuickShop inspection messages shown on EarthMC. */
public final class QuickShopParser {
    private QuickShopParser() {}

    private static final Pattern COLOR = Pattern.compile("§[0-9a-fk-orA-FK-OR]");
    private static final Pattern OWNER = Pattern.compile("所有者\\s*[:：]\\s*([^\\s\\[\\]│|]+)");
    private static final Pattern ITEM = Pattern.compile("物品\\s*[:：]\\s*(.+?)(?:\\s*\\[物品预览\\])?\\s*$", Pattern.MULTILINE);
    private static final Pattern PRICE = Pattern.compile("每\\s*(\\d*)\\s*个\\s*(.+?)\\s*价格\\s*([\\d,.]+)\\s*[gG金]");
    private static final Pattern STOCK = Pattern.compile("库存\\s*[:：]\\s*(\\d+)");
    private static final Pattern SPACE = Pattern.compile("空间\\s*[:：]\\s*(\\d+)");

    public static final class Result {
        public String owner, item, type, state;
        public int amount;
        public double gold;
        public Integer available;
        public boolean complete() {
            return owner != null && item != null && type != null && amount > 0 && gold > 0;
        }
    }

    public static Result parse(String content) {
        String clean = COLOR.matcher(content).replaceAll("");
        Result result = new Result();
        Matcher m = OWNER.matcher(clean);
        if (m.find()) result.owner = m.group(1).trim();
        m = ITEM.matcher(clean);
        if (m.find()) result.item = m.group(1).replace("[物品预览]", "").trim();
        m = PRICE.matcher(clean);
        if (m.find()) {
            try {
                result.amount = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
                result.gold = Double.parseDouble(m.group(3).replace(",", ""));
            } catch (NumberFormatException e) { return result; }
            // Price-line item is an important discriminator for enchantments and variants.
            result.item = m.group(2).trim();
        }
        if (clean.contains("正在出售物品")) result.type = "sell";
        else if (clean.contains("正在收购物品")) result.type = "buy";
        m = STOCK.matcher(clean);
        if (m.find()) result.available = Integer.parseInt(m.group(1));
        m = SPACE.matcher(clean);
        if (m.find()) result.available = Integer.parseInt(m.group(1));
        if (clean.contains("缺货")) { result.state = "out-of-stock"; result.available = 0; }
        else if (clean.contains("空间已不足")) { result.state = "full"; result.available = 0; }
        else result.state = result.available != null && result.available == 0 ? "unavailable" : "available";
        return result;
    }
}
