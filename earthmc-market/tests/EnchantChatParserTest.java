import dev.earthmarket.EnchantChatParser;
public class EnchantChatParserTest {
 public static void main(String[] args) {
  var p = new EnchantChatParser();
  assert !p.accept("聊天中的保护 IV");
  assert !p.accept("+--------------Enchantments----------------+");
  assert p.accept("保护 IV");
  assert p.accept("耐久 III");
  assert p.accept("摔落缓冲 IV");
  assert p.details().contains("保护 IV") && p.details().contains("耐久 III");
  p.reset();
  assert !p.hasEntries();
  System.out.println("Enchant chat parser tests OK");
 }
}
