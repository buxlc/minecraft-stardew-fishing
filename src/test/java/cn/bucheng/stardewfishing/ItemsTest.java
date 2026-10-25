package cn.bucheng.stardewfishing;

import java.nio.file.Path;
import java.util.*;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ItemsTest {
    @TempDir Path temporary;
    Items items; ItemSigner signer; Map<String, Object> fields; ItemStack stack;
    @BeforeEach void setup() throws Exception {
        Settings settings = SettingsFixture.defaults(); signer = new ItemSigner(temporary.resolve("key.bin"));
        JavaPlugin plugin = mock(JavaPlugin.class); when(plugin.getName()).thenReturn("StardewFishing"); items = new Items(plugin, () -> settings, signer);
        fields = new HashMap<>(); PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(any(), any())).thenAnswer(call -> fields.get(((NamespacedKey) call.getArgument(0)).getKey()));
        when(pdc.getOrDefault(any(), any(), any())).thenAnswer(call -> fields.getOrDefault(((NamespacedKey) call.getArgument(0)).getKey(), call.getArgument(2)));
        when(pdc.has(any(), any())).thenAnswer(call -> fields.containsKey(((NamespacedKey) call.getArgument(0)).getKey()));
        ItemMeta meta = mock(ItemMeta.class); when(meta.getPersistentDataContainer()).thenReturn(pdc);
        stack = mock(ItemStack.class); when(stack.hasItemMeta()).thenReturn(true); when(stack.getItemMeta()).thenReturn(meta); when(stack.getType()).thenReturn(Material.COD);
    }
    private void fish() {
        fields.put("kind", "fish"); fields.put("id", "carp"); fields.put("length", 300); fields.put("cooked", 0);
        fields.put("signature", signer.sign("fish|carp|300|false"));
    }
    @Test void ordinaryFishRenamedWithLoreIsNotSellable() { assertNull(items.readFish(stack)); }
    @Test void signedFishAuthenticatesButChangedLengthOrMaterialDoesNot() {
        fish(); assertNotNull(items.readFish(stack));
        fields.put("length", 420); assertNull(items.readFish(stack));
        fields.put("length", 300); when(stack.getType()).thenReturn(Material.SALMON); assertNull(items.readFish(stack));
    }
    @Test void changingCookedFlagCannotRaisePrice() {
        fish(); fields.put("cooked", 1); when(stack.getType()).thenReturn(Material.COOKED_COD);
        assertNull(items.readFish(stack));
    }
    @Test void validPaidRodIsProtectedButUnmodifiedRodCanBeRepairedNormally() {
        when(stack.getType()).thenReturn(Material.FISHING_ROD); String uuid = UUID.randomUUID().toString();
        fields.put("rod_id", uuid); fields.put("rod_level", 2); fields.put("rod_tackle", "lava");
        fields.put("rod_signature", signer.sign("rod|" + uuid + "|2|lava"));
        assertEquals("lava", items.rod(stack).tackle()); assertTrue(items.custom(stack));
        fields.put("rod_level", 0); fields.put("rod_tackle", ""); fields.put("rod_signature", signer.sign("rod|" + uuid + "|0|"));
        assertFalse(items.custom(stack));
    }
    @Test void editingRodTackleCannotUnlockLavaFishing() {
        when(stack.getType()).thenReturn(Material.FISHING_ROD); String uuid = UUID.randomUUID().toString();
        fields.put("rod_id", uuid); fields.put("rod_level", 0); fields.put("rod_tackle", "lava");
        fields.put("rod_signature", signer.sign("rod|" + uuid + "|0|copper"));
        assertEquals("", items.rod(stack).tackle());
    }
}
