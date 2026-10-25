package cn.bucheng.stardewfishing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FishingEngineTest {
    JavaPlugin plugin;
    FishingEngine.Backend backend;
    FishingEngine engine;
    Player player;
    FishHook hook;
    ItemStack rod;
    PlayerInventory inventory;
    World world;
    PluginManager manager;
    Runnable tick;
    AtomicReference<Location> hookLocation;
    boolean lavaPool;

    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        manager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(manager);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L))).thenAnswer(call -> {
            tick = call.getArgument(1);
            return mock(BukkitTask.class);
        });
        backend = mock(FishingEngine.Backend.class);
        world = mock(World.class);
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        hook = mock(FishHook.class);
        rod = mock(ItemStack.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(inventory);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(inventory.getItem(EquipmentSlot.HAND)).thenReturn(rod);
        when(rod.getType()).thenReturn(Material.FISHING_ROD);
        when(rod.clone()).thenReturn(rod);
        when(rod.isSimilar(rod)).thenReturn(true);
        when(hook.getUniqueId()).thenReturn(UUID.randomUUID());
        when(hook.getWorld()).thenReturn(world);
        when(hook.isValid()).thenReturn(true);
        hookLocation = new AtomicReference<>(new Location(world, 2, 63.9, 0));
        when(hook.getLocation()).thenAnswer(call -> hookLocation.get().clone());
        when(hook.teleport(any(Location.class))).thenAnswer(call -> {
            hookLocation.set(((Location) call.getArgument(0)).clone());
            return true;
        });
        when(backend.enabled(player)).thenReturn(true);
        when(backend.identifyRod(rod)).thenReturn(UUID.randomUUID());
        when(backend.sameRod(eq(player), eq(EquipmentSlot.HAND), any(UUID.class))).thenReturn(true);
        when(backend.catchFish(eq(player), any(Location.class), eq(rod), anyBoolean())).thenReturn(true);
        when(backend.minWaitTicks()).thenReturn(40);
        when(backend.maxWaitTicks()).thenReturn(40);
        when(backend.biteWindowTicks()).thenReturn(3);
        when(world.getBlockAt(any(Location.class))).thenAnswer(call -> block(((Location) call.getArgument(0)).getBlockY()));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> block(call.getArgument(1)));
        engine = new FishingEngine(plugin, backend);
    }

    private Block block(int y) {
        Block block = mock(Block.class);
        when(block.getY()).thenReturn(y);
        Material material = lavaPool && y < 64 ? Material.LAVA : Material.AIR;
        when(block.getType()).thenReturn(material);
        when(block.isPassable()).thenReturn(true);
        when(block.getRelative(anyInt(), anyInt(), anyInt())).thenAnswer(call -> block(y + (int) call.getArgument(1)));
        return block;
    }

    private PlayerFishEvent event(PlayerFishEvent.State state, Item item) {
        return new PlayerFishEvent(player, item, hook, EquipmentSlot.HAND, state);
    }

    private void cast() { engine.onFish(event(PlayerFishEvent.State.FISHING, null)); }

    private void lavaCast() {
        lavaPool = true;
        when(backend.lavaAllowed(rod)).thenReturn(true);
        cast();
    }

    private PlayerInteractEvent reel() {
        PlayerInteractEvent click = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, rod, null, null, EquipmentSlot.HAND);
        engine.onInteract(click);
        return click;
    }

    @Test void disabledCustomFishingLeavesVanillaLootAndRodUntouched() {
        when(backend.enabled(player)).thenReturn(false);
        cast();
        Item vanilla = mock(Item.class);
        engine.onFish(event(PlayerFishEvent.State.CAUGHT_FISH, vanilla));
        verify(vanilla, never()).remove();
        verify(backend, never()).identifyRod(any());
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }

    @Test void castingWithAnEmptyHandDoesNotCreateACustomSession() {
        when(inventory.getItem(EquipmentSlot.HAND)).thenReturn(null);
        assertDoesNotThrow(this::cast);
        verify(backend, never()).identifyRod(any());
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }

    @Test void waterFishingKeepsTheServersNativeWaitAndLureConfiguration() {
        cast();
        verify(hook, never()).setWaitTime(anyInt(), anyInt());
        verify(hook, never()).setApplyLure(anyBoolean());
    }

    @Test void waterCatchDiscardsVanillaLootAndRedeemsTheSessionOnlyOnce() {
        cast();
        Item vanilla = mock(Item.class);
        PlayerFishEvent caught = event(PlayerFishEvent.State.CAUGHT_FISH, vanilla);
        engine.onFish(caught);
        engine.onFish(caught);
        verify(vanilla, times(1)).remove();
        verify(backend, times(1)).catchFish(eq(player), any(Location.class), eq(rod), eq(false));
        assertFalse(caught.isCancelled(), "native retrieve must still apply water durability and experience");
        verify(hook, never()).remove();
    }

    @Test void aProtectionCancelledWaterCatchGrantsNothing() {
        cast();
        Item vanilla = mock(Item.class);
        PlayerFishEvent caught = event(PlayerFishEvent.State.CAUGHT_FISH, vanilla);
        caught.setCancelled(true);
        engine.onFish(caught);
        verify(vanilla, never()).remove();
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }

    @Test void disablingWhileWaitingRemovesTheHookBeforeAnotherCatchCanBeAwarded() {
        cast();
        when(backend.enabled(player)).thenReturn(false);
        tick.run();
        verify(hook).remove();
        engine.onFish(event(PlayerFishEvent.State.CAUGHT_FISH, mock(Item.class)));
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }

    @Test void changingRodAttachmentsAfterCastingInvalidatesTheCatchSnapshot() {
        cast();
        when(rod.isSimilar(rod)).thenReturn(false);
        tick.run();
        verify(hook).remove();
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }

    @Test void anInvalidatedHookIsCleanedUpWithoutAwardingAnything() {
        cast();
        when(hook.isValid()).thenReturn(false);
        tick.run();
        tick.run();
        verify(hook, times(1)).remove();
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }

    @Test void movingBeyondTheVanillaCastingDistanceDisposesTheSession() {
        cast();
        hookLocation.set(new Location(world, 40, 64, 0));
        tick.run();
        verify(hook).remove();
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }

    @Test void lavaWithoutTheRequiredAttachmentCannotWaitForACatch() {
        lavaPool = true;
        cast();
        tick.run();
        verify(hook).remove();
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }

    @Test void reelingLavaBeforeItsBiteGivesNoReward() {
        lavaCast();
        tick.run();
        PlayerInteractEvent click = reel();
        assertEquals(Event.Result.DENY, click.useItemInHand());
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
        verify(hook).remove();
    }

    @Test void lavaRequiresItsWaitThenHasOnlyOneRewardPerBite() {
        lavaCast();
        for (int i = 0; i < 40; i++) tick.run();
        reel();
        reel();
        verify(backend, times(1)).catchFish(eq(player), any(Location.class), eq(rod), eq(true));
        verify(manager, atLeastOnce()).callEvent(argThat(event -> event instanceof PlayerFishEvent fish
                && fish.getState() == PlayerFishEvent.State.BITE));
        verify(manager, atLeastOnce()).callEvent(argThat(event -> event instanceof PlayerFishEvent fish
                && fish.getState() == PlayerFishEvent.State.CAUGHT_FISH));
    }

    @Test void aSuccessfulLavaCatchConsumesRodDurabilityInSurvival() {
        Damageable meta = mock(Damageable.class);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(rod.getItemMeta()).thenReturn(meta);
        when(meta.getDamage()).thenReturn(10);
        when(meta.hasMaxDamage()).thenReturn(true);
        when(meta.getMaxDamage()).thenReturn(64);
        lavaCast();
        for (int i = 0; i < 40; i++) tick.run();
        reel();
        verify(meta).setDamage(11);
        verify(rod).setItemMeta(meta);
    }

    @Test void aProtectionPluginCanPreventLavaRodDamageWithoutDuplicatingTheCatch() {
        Damageable meta = mock(Damageable.class);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(rod.getItemMeta()).thenReturn(meta);
        doAnswer(call -> {
            if (call.getArgument(0) instanceof org.bukkit.event.player.PlayerItemDamageEvent damage)
                damage.setCancelled(true);
            return null;
        }).when(manager).callEvent(any(Event.class));
        lavaCast();
        for (int i = 0; i < 40; i++) tick.run();
        reel();
        verify(meta, never()).setDamage(anyInt());
        verify(backend, times(1)).catchFish(eq(player), any(Location.class), eq(rod), eq(true));
    }

    @Test void aMissedLavaBiteMustWaitAgainInsteadOfRemainingRedeemable() {
        lavaCast();
        for (int i = 0; i < 43; i++) tick.run();
        reel();
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }

    @Test void aProtectionPluginCanCancelALavaCatch() {
        doAnswer(call -> {
            if (call.getArgument(0) instanceof PlayerFishEvent fish && fish.getState() == PlayerFishEvent.State.CAUGHT_FISH)
                fish.setCancelled(true);
            return null;
        }).when(manager).callEvent(any(Event.class));
        lavaCast();
        for (int i = 0; i < 40; i++) tick.run();
        reel();
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
        verify(hook).remove();
    }

    @Test void denyingItemUseCannotRedeemALavaBite() {
        lavaCast();
        for (int i = 0; i < 40; i++) tick.run();
        PlayerInteractEvent denied = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, rod, null, null, EquipmentSlot.HAND);
        denied.setUseItemInHand(Event.Result.DENY);
        engine.onInteract(denied);
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
        verify(hook, never()).remove();
        reel();
        verify(backend, times(1)).catchFish(eq(player), any(Location.class), eq(rod), eq(true));
    }

    @Test void drainingTheLavaPoolInvalidatesAnAlreadyFloatingBobber() {
        lavaCast();
        tick.run();
        lavaPool = false;
        tick.run();
        verify(hook).remove();
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }

    @Test void closingTheEngineRemovesActiveHooksAndStopsFurtherRewards() {
        cast();
        engine.close();
        engine.close();
        tick.run();
        engine.onFish(event(PlayerFishEvent.State.CAUGHT_FISH, mock(Item.class)));
        verify(hook, times(1)).remove();
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }

    @Test void aProtectionPluginCanCancelALavaBite() {
        doAnswer(call -> {
            if (call.getArgument(0) instanceof PlayerFishEvent fish && fish.getState() == PlayerFishEvent.State.BITE)
                fish.setCancelled(true);
            return null;
        }).when(manager).callEvent(any(Event.class));
        lavaCast();
        for (int i = 0; i < 40; i++) tick.run();
        reel();
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }

    @Test void handSwapImmediatelyDisposesAnActiveSession() {
        cast();
        engine.onSwap(new PlayerSwapHandItemsEvent(player, rod, rod));
        verify(hook).remove();
        engine.onFish(event(PlayerFishEvent.State.CAUGHT_FISH, mock(Item.class)));
        verify(backend, never()).catchFish(any(), any(), any(), anyBoolean());
    }
}
