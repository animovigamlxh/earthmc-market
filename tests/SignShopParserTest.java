package dev.earthmarket;
public final class SignShopParserTest {
    public static void main(String[] args) {
        check(new String[]{"Felle_Homme", "出售 99", "沙子", "每64x 1G"}, "sell", 64, 1);
        check(new String[]{"Felle_Homme", "收购 1", "去皮橡木", "每64x 1G"}, "buy", 64, 1);
        check(new String[]{"AusKou", "出售 70", "镶根泥", "每24x 1G"}, "sell", 24, 1);
        if (SignShopParser.parse(new String[]{"公告", "欢迎", "光临", "无价格"}) != null) throw new AssertionError("ordinary sign recognized");
        System.out.println("SignShopParser tests passed");
    }
    static void check(String[] lines, String type, int count, double gold) {
        var out = SignShopParser.parse(lines);
        if (out == null || !out.type().equals(type) || out.amount() != count || out.gold() != gold)
            throw new AssertionError(java.util.Arrays.toString(lines));
    }
}
