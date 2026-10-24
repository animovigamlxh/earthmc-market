package dev.earthmarket;
public final class SignShopParserTest {
    public static void main(String[] args) {
        check(new String[]{"Felle_Homme", "出售 99", "沙子", "每64x 1G"}, "sell", 64, 1);
        check(new String[]{"Felle_Homme", "收购 1", "去皮橡木", "每64x 1G"}, "buy", 64, 1);
        check(new String[]{"AusKou", "出售 70", "镶根泥", "每24x 1G"}, "sell", 24, 1);
        check(new String[]{"Manly196898_", "出售 1", "下界合金靴子", "单价：27G"}, "sell", 1, 27);
        check(new String[]{"Manly196898_", "出售 1", "下界合金胸甲", "单价：26G"}, "sell", 1, 26);
        check(new String[]{"thebestlung", "出售 53", "远古残骸", "单价：4G"}, "sell", 1, 4);
        check(new String[]{"thebestlung", "收购 7", "远古残骸", "单价：3G"}, "buy", 1, 3);
        check(new String[]{"GrayWolf", "出售 4", "信标", "单价：7G"}, "sell", 1, 7);
        if (SignShopParser.parse(new String[]{"公告", "欢迎", "光临", "无价格"}) != null) throw new AssertionError("ordinary sign recognized");
        System.out.println("SignShopParser tests passed");
    }
    static void check(String[] lines, String type, int count, double gold) {
        var out = SignShopParser.parse(lines);
        if (out == null || !out.type().equals(type) || out.amount() != count || out.gold() != gold)
            throw new AssertionError(java.util.Arrays.toString(lines));
    }
}
