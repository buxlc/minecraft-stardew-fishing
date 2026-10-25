package cn.bucheng.stardewfishing;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketTest {
    private Settings settings;
    private Items items;
    private Player player;
    private PlayerInventory inventory;
    private EconomyBridge.Provider provider;
    private Market market;
    private ItemStack[] storage;

    @BeforeEach void setup() throws Exception {
        settings = SettingsFixture.defaults(); items = mock(Items.class); player = mock(Player.class); inventory = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getInventory()).thenReturn(inventory);
        storage = new ItemStack[36];
        when(inventory.getStorageContents()).thenAnswer(call -> storage.clone());
        when(inventory.getItem(anyInt())).thenAnswer(call -> storage[call.getArgument(0)]);
        when(inventory.getItemInMainHand()).thenAnswer(call -> storage[0]); when(inventory.getHeldItemSlot()).thenReturn(0);
        doAnswer(call -> { storage[call.getArgument(0)] = call.getArgument(1); return null; }).when(inventory).setItem(anyInt(), any());
        provider = mock(EconomyBridge.Provider.class); when(provider.enabled()).thenReturn(true); when(provider.digits()).thenReturn(2);
        EconomyBridge economy = new EconomyBridge(() -> provider);
        market = new Market(items, economy, () -> settings, (p, item) -> {
            for (int i = 0; i < storage.length; i++) if (storage[i] == null) { storage[i] = item; return; }
            fail("refund lost an item");
        });
    }
    private ItemStack fish(String id, int length, int amount, boolean cooked) {
        ItemStack stack = mock(ItemStack.class); when(stack.getAmount()).thenReturn(amount); when(stack.clone()).thenReturn(stack);
        when(items.readFish(stack)).thenReturn(new Items.Fish(settings.fish.get(id), length, cooked)); return stack;
    }
    @Test void ordinarySalePaysCookedValueAndPreservesKingsAndVanillaItems() {
        storage[0] = fish("carp", 420, 2, true);
        ItemStack king = storage[1] = fish("river_king", 1800, 1, false);
        ItemStack vanilla = storage[2] = mock(ItemStack.class);
        when(provider.deposit(player, 120)).thenReturn(true);
        assertEquals(new Market.Quote(2, 120), market.quote(player, false, false));
        market.sell(player, false, false);
        assertNull(storage[0]); assertSame(king, storage[1]); assertSame(vanilla, storage[2]);
        verify(provider).deposit(player, 120);
    }
    @Test void rejectedDepositRestoresAllFishAndDoesNotPay() {
        ItemStack first = storage[0] = fish("carp", 420, 2, false), second = storage[4] = fish("gudgeon", 240, 1, false);
        assertThrows(IllegalArgumentException.class, () -> market.sell(player, false, false));
        assertTrue(Arrays.asList(storage).containsAll(List.of(first, second)));
        verify(provider).deposit(player, 112);
    }
    @Test void throwingEconomyAlsoRestoresFish() {
        ItemStack first = storage[0] = fish("carp", 420, 2, false);
        when(provider.deposit(player, 80)).thenThrow(new IllegalStateException("backend unavailable"));
        assertThrows(IllegalStateException.class, () -> market.sell(player, false, false));
        assertTrue(Arrays.asList(storage).contains(first));
    }
    @Test void reentrantSaleCannotAwardMoneyTwice() {
        storage[0] = fish("carp", 420, 2, false);
        when(provider.deposit(player, 80)).thenAnswer(call -> { market.sell(player, false, false); return true; });
        market.sell(player, false, false); verify(provider, times(1)).deposit(player, 80);
    }
    @Test void kingHandSelectionNeverSellsAReplacementOrdinaryFish() {
        storage[0] = fish("carp", 420, 2, false);
        assertEquals(new Market.Quote(0, 0), market.quote(player, true, true));
        assertThrows(IllegalArgumentException.class, () -> market.sell(player, true, true));
        verify(provider, never()).deposit(any(), anyDouble());
    }
    @Test void missingVaultLeavesInventoryIntact() {
        ItemStack original = storage[0] = fish("carp", 420, 2, false); when(provider.enabled()).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> market.sell(player, false, false));
        assertSame(original, storage[0]);
    }
}
