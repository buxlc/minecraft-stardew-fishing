package cn.bucheng.stardewfishing;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FoodListenerTest {
    Settings settings; Items items; FoodListener listener;
    @BeforeEach void setup() throws Exception {
        settings = SettingsFixture.defaults(); items = mock(Items.class);
        listener = new FoodListener(mock(JavaPlugin.class), items, () -> settings);
    }
    @Test void furnaceCookingPreservesSpeciesAndLength() {
        ItemStack source = mock(ItemStack.class), result = mock(ItemStack.class);
        var fish = settings.fish.get("river_king"); when(items.readFish(source)).thenReturn(new Items.Fish(fish, 1500, false));
        when(items.fish(fish, 1500, true)).thenReturn(result);
        Block block = mock(Block.class); Furnace furnace = mock(Furnace.class); FurnaceInventory inv = mock(FurnaceInventory.class);
        when(block.getState()).thenReturn(furnace); when(furnace.getInventory()).thenReturn(inv);
        BlockCookEvent event = new BlockCookEvent(block, source, mock(ItemStack.class));
        listener.cook(event); org.junit.jupiter.api.Assertions.assertSame(result, event.getResult());
    }
    @Test void differentFishCannotMergeIntoExpensiveOutput() {
        ItemStack source = mock(ItemStack.class), result = mock(ItemStack.class), existing = mock(ItemStack.class);
        var fish = settings.fish.get("carp"); when(items.readFish(source)).thenReturn(new Items.Fish(fish, 200, false));
        when(items.fish(fish, 200, true)).thenReturn(result); when(existing.getType()).thenReturn(Material.COOKED_COD);
        Block block = mock(Block.class); Furnace furnace = mock(Furnace.class); FurnaceInventory inv = mock(FurnaceInventory.class);
        when(block.getState()).thenReturn(furnace); when(furnace.getInventory()).thenReturn(inv); when(inv.getResult()).thenReturn(existing);
        BlockCookEvent event = new BlockCookEvent(block, source, mock(ItemStack.class)); listener.cook(event);
        org.junit.jupiter.api.Assertions.assertTrue(event.isCancelled());
    }
    @Test void cookedFishCannotReceiveMultiplierAgain() {
        ItemStack source = mock(ItemStack.class); when(items.readFish(source)).thenReturn(new Items.Fish(settings.fish.get("carp"), 200, true));
        BlockCookEvent event = new BlockCookEvent(mock(Block.class), source, mock(ItemStack.class)); listener.cook(event);
        org.junit.jupiter.api.Assertions.assertTrue(event.isCancelled()); verify(items, never()).fish(any(), anyInt(), anyBoolean());
    }
    @Test void campfireUsesTheSameSignedTransformation() {
        ItemStack source = mock(ItemStack.class), result = mock(ItemStack.class); var fish = settings.fish.get("magma_carp");
        when(items.readFish(source)).thenReturn(new Items.Fish(fish, 350, false)); when(items.fish(fish, 350, true)).thenReturn(result);
        Block block = mock(Block.class); when(block.getState()).thenReturn(mock(Campfire.class));
        BlockCookEvent event = new BlockCookEvent(block, source, mock(ItemStack.class)); listener.cook(event);
        org.junit.jupiter.api.Assertions.assertSame(result, event.getResult());
    }
}
