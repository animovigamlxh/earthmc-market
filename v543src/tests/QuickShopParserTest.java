package dev.earthmarket;

public final class QuickShopParserTest {
    private static void check(String text, String item, String type, int units, double gold, int available, String state) {
        var q = QuickShopParser.parse(text);
        if (!q.complete() || !item.equals(q.item) || !type.equals(q.type)
            || q.amount != units || q.gold != gold || q.available == null || q.available != available
            || !state.equals(q.state)) {
            throw new AssertionError("Unexpected scan for " + item + ": " + q.item + " " + q.type + " " + q.amount + " " + q.gold + " " + q.available + " " + q.state);
        }
    }

    public static void main(String[] args) {
        check("商店信息：\n所有者：shenhuis5\n物品：珠光蛙明灯 [物品预览]\n库存:18\n每64个珠光蛙明灯价格1G\n这个商店现在正在出售物品。",
            "珠光蛙明灯", "sell", 64, 1, 18, "available");
        check("商店信息：\n所有者：Freyr12969\n物品：煤炭块 [物品预览]\n空间:0\n每30个煤炭块价格1G\n这个商店现在正在收购物品。\n这个商店空间已不足",
            "煤炭块", "buy", 30, 1, 0, "full");
        check("商店信息：\n所有者：M0E9017\n物品：末影珍珠 [物品预览]\n库存:0\n每16个末影珍珠价格1G\n这个商店现在正在出售物品。\n这个商店已缺货",
            "末影珍珠", "sell", 16, 1, 0, "out-of-stock");
        check("商店信息：\n所有者：shenhuis5\n物品：绿宝石 [物品预览]\n库存:46\n每3个绿宝石价格1G\n这个商店现在正在出售物品。",
            "绿宝石", "sell", 3, 1, 46, "available");
        check("商店信息：\n所有者：Chamberlain_232\n物品：风爆 I [物品预览]\n库存:7\n每个风爆 I价格27G\n这个商店现在正在出售物品。",
            "风爆 I", "sell", 1, 27, 7, "available");
        System.out.println("All five QuickShop samples passed");
    }
}
