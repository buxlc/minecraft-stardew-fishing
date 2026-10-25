package cn.bucheng.stardewfishing;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MenusTest {
    private static final class UI implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit;
        final Player player = mock(Player.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final List<Runnable> queued = new ArrayList<>();
        final Consumer<Player> toggle = mock(Consumer.class);
        final Menus menus;
        Inventory current;
        UI() throws Exception {
            Settings s = SettingsFixture.defaults();
            JavaPlugin plugin = mock(JavaPlugin.class); Server server = mock(Server.class);
            when(plugin.getServer()).thenReturn(server); when(server.getScheduler()).thenReturn(scheduler);
            when(scheduler.runTask(any(), any(Runnable.class))).thenAnswer(call -> { queued.add(call.getArgument(1)); return mock(BukkitTask.class); });
            UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true); when(player.hasPermission(anyString())).thenReturn(true);
            when(player.openInventory(any(Inventory.class))).thenAnswer(call -> { current = call.getArgument(0); return null; });
            when(player.getOpenInventory()).thenAnswer(call -> view(current));
            PlayerStore store = mock(PlayerStore.class); when(store.get(id, true)).thenReturn(new PlayerStore.Profile(true, Map.of()));
            bukkit = mockStatic(Bukkit.class);
            ItemFactory factory = mock(ItemFactory.class); ItemMeta meta = mock(ItemMeta.class); when(meta.clone()).thenReturn(meta);
            when(factory.getItemMeta(any(Material.class))).thenReturn(meta); when(factory.isApplicable(any(ItemMeta.class), any(Material.class))).thenReturn(true);
            when(factory.asMetaFor(any(ItemMeta.class), any(Material.class))).thenReturn(meta);
            bukkit.when(Bukkit::getItemFactory).thenReturn(factory);
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), anyString())).thenAnswer(call -> {
                InventoryHolder holder = call.getArgument(0); Inventory inventory = mock(Inventory.class); when(inventory.getHolder()).thenReturn(holder); return inventory;
            });
            menus = new Menus(plugin, () -> s, mock(Items.class), store, mock(Market.class), toggle, p -> {});
            menus.home(player);
        }
        InventoryView view(Inventory inventory) { InventoryView v = mock(InventoryView.class); when(v.getTopInventory()).thenReturn(inventory); return v; }
        InventoryClickEvent click(int slot, ClickType type) {
            InventoryClickEvent e = mock(InventoryClickEvent.class); InventoryView v = view(current);
            when(e.getView()).thenReturn(v); when(e.getWhoClicked()).thenReturn(player); when(e.getRawSlot()).thenReturn(slot); when(e.getClick()).thenReturn(type); return e;
        }
        @Override public void close() { bukkit.close(); }
    }
    @Test void virtualInventoryCancelsShiftNumbersBottomClicksAndDrags() throws Exception {
        try (UI ui = new UI()) {
            for (ClickType type : List.of(ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.MIDDLE)) {
                InventoryClickEvent e = ui.click(49, type); ui.menus.click(e); verify(e).setCancelled(true);
            }
            InventoryClickEvent bottom = ui.click(60, ClickType.LEFT); ui.menus.click(bottom); verify(bottom).setCancelled(true);
            InventoryDragEvent drag = mock(InventoryDragEvent.class); InventoryView v = ui.view(ui.current); when(drag.getView()).thenReturn(v);
            ui.menus.drag(drag); verify(drag).setCancelled(true); assertTrue(ui.queued.isEmpty());
        }
    }
    @Test void toggleRunsOnceOnNextTickAndDoubleClickIsThrottled() throws Exception {
        try (UI ui = new UI()) {
            InventoryClickEvent click = ui.click(49, ClickType.LEFT); ui.menus.click(click); ui.menus.click(click);
            verify(ui.toggle, never()).accept(ui.player); assertEquals(1, ui.queued.size());
            ui.queued.getFirst().run(); verify(ui.toggle).accept(ui.player);
        }
    }
    @Test void queuedActionIsDiscardedWhenMenuOrPermissionsChange() throws Exception {
        try (UI ui = new UI()) {
            ui.menus.click(ui.click(49, ClickType.LEFT)); ui.current = mock(Inventory.class); ui.queued.getFirst().run();
            verify(ui.toggle, never()).accept(ui.player);
        }
        try (UI ui = new UI()) {
            ui.menus.click(ui.click(49, ClickType.LEFT)); when(ui.player.hasPermission(anyString())).thenReturn(false); ui.queued.getFirst().run();
            verify(ui.toggle, never()).accept(ui.player);
        }
    }
}
