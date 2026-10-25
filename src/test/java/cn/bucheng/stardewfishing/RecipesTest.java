package cn.bucheng.stardewfishing;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecipesTest {
    Settings settings;
    Items items;
    JavaPlugin plugin;
    Recipes recipes;
    @BeforeEach void setup() throws Exception {
        settings = SettingsFixture.defaults(); items = mock(Items.class); plugin = mock(JavaPlugin.class); when(plugin.getName()).thenReturn("StardewFishing");
        recipes = new Recipes(plugin, () -> settings, items);
    }
    ItemStack material(Material material) { ItemStack i = mock(ItemStack.class); when(i.getType()).thenReturn(material); return i; }
    @Test void upgradingBaitRequiresAuthenticatedBaitNotVanillaSeeds() {
        var definition = settings.recipes.stream().filter(r -> r.id().equals("fine")).findFirst().orElseThrow();
        ItemStack seeds = material(Material.WHEAT_SEEDS), sugar = material(Material.SUGAR), kelp = material(Material.DRIED_KELP);
        assertFalse(recipes.matches(definition, new ItemStack[]{seeds, sugar, kelp}));
        when(items.readSupply(seeds, "bait")).thenReturn(settings.baits.get("basic")); when(items.custom(seeds)).thenReturn(true);
        assertTrue(recipes.matches(definition, new ItemStack[]{kelp, seeds, sugar, null}));
        assertFalse(recipes.matches(definition, new ItemStack[]{kelp, seeds, sugar, sugar}));
    }
    @Test void automatedCrafterCannotBypassBaitAuthentication() {
        Block block = mock(Block.class); Crafter crafter = mock(Crafter.class); when(block.getState()).thenReturn(crafter);
        Inventory inventory = mock(Inventory.class); when(crafter.getInventory()).thenReturn(inventory);
        ItemStack seeds = material(Material.WHEAT_SEEDS), sugar = material(Material.SUGAR), kelp = material(Material.DRIED_KELP);
        when(inventory.getContents()).thenReturn(new ItemStack[]{seeds, sugar, kelp});
        ShapelessRecipe recipe = mock(ShapelessRecipe.class); NamespacedKey recipeKey = new NamespacedKey(plugin, "bait_fine"); when(recipe.getKey()).thenReturn(recipeKey);
        CrafterCraftEvent event = mock(CrafterCraftEvent.class); when(event.getBlock()).thenReturn(block); when(event.getRecipe()).thenReturn(recipe);
        recipes.autoCraft(event); verify(event).setCancelled(true);
        reset(event); when(event.getBlock()).thenReturn(block); when(event.getRecipe()).thenReturn(recipe);
        when(items.readSupply(seeds, "bait")).thenReturn(settings.baits.get("basic"));
        recipes.autoCraft(event); verify(event, never()).setCancelled(true);
    }
    @Test void paidRodAndCustomFishCannotBeConsumedInVanillaRecipes() {
        ItemStack customRod = material(Material.FISHING_ROD); when(items.custom(customRod)).thenReturn(true);
        CraftingInventory inventory = mock(CraftingInventory.class); when(inventory.getMatrix()).thenReturn(new ItemStack[]{customRod});
        PrepareItemCraftEvent event = mock(PrepareItemCraftEvent.class); when(event.getInventory()).thenReturn(inventory);
        recipes.prepare(event); verify(inventory).setResult(null);
        CraftItemEvent craft = mock(CraftItemEvent.class); when(craft.getInventory()).thenReturn(inventory);
        recipes.craft(craft); verify(craft).setCancelled(true);
    }
}
