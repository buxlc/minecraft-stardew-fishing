package cn.bucheng.stardewfishing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.util.RayTraceResult;

/** Uses vanilla water fishing and a fireproof, pinned native bobber for lava fishing. */
public final class FishingEngine implements Listener, AutoCloseable {
    /** All methods run synchronously. catchFish owns rewards, bait use and collection persistence. */
    public interface Backend {
        boolean enabled(Player player);
        boolean lavaAllowed(ItemStack rod);
        UUID identifyRod(ItemStack rod);
        boolean sameRod(Player player, EquipmentSlot hand, UUID rodId);
        boolean catchFish(Player player, Location spot, ItemStack castRod, boolean lava);
        int minWaitTicks();
        int maxWaitTicks();
        int biteWindowTicks();
    }

    private static final double MAX_DISTANCE_SQUARED = 32 * 32;
    private final JavaPlugin plugin;
    private final Backend backend;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Session> hooks = new HashMap<>();
    private final Set<PlayerFishEvent> synthetic = Collections.newSetFromMap(new IdentityHashMap<>());
    private final BukkitTask ticker;
    private boolean closed;

    public FishingEngine(JavaPlugin plugin, Backend backend) {
        this.plugin = plugin;
        this.backend = backend;
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Observes protection decisions before changing caught entities or granting a reward. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (closed || event.isCancelled() || synthetic.contains(event)) return;
        Player player = event.getPlayer();
        if (event.getState() == PlayerFishEvent.State.FISHING) {
            if (!backend.enabled(player)) return;
            cancel(player);
            EquipmentSlot hand = fishingHand(event);
            ItemStack rod = player.getInventory().getItem(hand);
            if (rod == null || rod.getType() != Material.FISHING_ROD) return;
            UUID rodId = backend.identifyRod(rod);
            if (rodId == null) return;
            player.getInventory().setItem(hand, rod);
            Session session = new Session(player, event.getHook(), hand, rodId, rod.clone(), backend.lavaAllowed(rod));
            if (session.fireproof) event.getHook().setInvulnerable(true);
            sessions.put(player.getUniqueId(), session);
            hooks.put(event.getHook().getUniqueId(), session);
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.hook.getUniqueId().equals(event.getHook().getUniqueId())) return;
        if (!valid(session)) {
            discard(session, true);
            return;
        }
        if (session.lavaSpot != null) {
            if (event.getState() == PlayerFishEvent.State.REEL_IN || event.getState() == PlayerFishEvent.State.IN_GROUND) {
                reelLava(session);
            } else if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY) {
                discard(session, false);
            }
            return;
        }
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            // Bukkit exposes the not-yet-added vanilla loot entity here. Discarding it prevents
            // the native retrieve implementation from adding a second reward to the world.
            if (!(event.getCaught() instanceof Item item)) {
                discard(session, false);
                return;
            }
            item.remove();
            discard(session, false);
            award(session, event.getHook().getLocation(), false);
        } else if (event.getState() == PlayerFishEvent.State.REEL_IN
                || event.getState() == PlayerFishEvent.State.IN_GROUND
                || event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY) {
            discard(session, false);
        }
    }

    /** Air clicks may be pre-cancelled by Bukkit; the item-use result is the relevant decision. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (closed || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.lavaSpot == null || event.getHand() != session.hand) return;
        if (event.useItemInHand() == Event.Result.DENY) return;
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (!valid(session)) {
            discard(session, true);
            return;
        }
        reelLava(session);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHookDamage(EntityDamageEvent event) {
        Session session = hooks.get(event.getEntity().getUniqueId());
        if (session == null || !session.fireproof) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.LAVA
                || event.getCause() == EntityDamageEvent.DamageCause.FIRE
                || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                || event.getCause() == EntityDamageEvent.DamageCause.HOT_FLOOR) {
            event.setCancelled(true);
        }
    }

    private void tick() {
        if (closed) return;
        for (Session session : new ArrayList<>(sessions.values())) {
            try {
                if (!valid(session)) {
                    discard(session, true);
                    continue;
                }
                FishHook hook = session.hook;
                if (session.fireproof) {
                    hook.setFireTicks(0);
                    hook.setVisualFire(false);
                }
                if (session.lavaSpot == null) {
                    Location current = hook.getLocation();
                    Location lava = lavaSurface(current);
                    if (lava == null) lava = lavaOnPath(session.previous, current);
                    session.previous = current;
                    if (lava == null) continue;
                    if (!session.fireproof) {
                        session.player.sendMessage(ChatColor.RED + "这根鱼竿没有岩浆吊具，无法在岩浆中垂钓。");
                        discard(session, true);
                        continue;
                    }
                    if (hook.getHookedEntity() != null) continue;
                    session.lavaSpot = lava;
                    session.timing = new FishingTiming(waitTicks(session.castRod), Math.max(1, backend.biteWindowTicks()));
                    hook.setGravity(false);
                    hook.setVelocity(new Vector());
                    hook.teleport(lava);
                    session.player.sendMessage(ChatColor.GOLD + "浮漂落入岩浆，等待鱼儿咬钩……");
                }
                // If the pool was drained or a block was placed over it, it is no longer fishable.
                if (lavaSurface(session.lavaSpot.clone().subtract(0, 0.1, 0)) == null) {
                    discard(session, true);
                    continue;
                }
                hook.setVelocity(new Vector());
                hook.teleport(session.lavaSpot);
                FishingTiming.Transition transition = session.timing.tick();
                if (transition == FishingTiming.Transition.BITE) {
                    PlayerFishEvent bite = new PlayerFishEvent(session.player, null, hook, session.hand, PlayerFishEvent.State.BITE);
                    call(bite);
                    if (bite.isCancelled() || !valid(session)) {
                        session.timing.rejectBite();
                        continue;
                    }
                    session.player.playSound(session.lavaSpot, "entity.fishing_bobber.splash", 1.0F, 0.7F);
                    session.player.sendTitle("", ChatColor.GOLD + "鱼儿咬钩了！右键收竿", 0, Math.max(1, backend.biteWindowTicks()), 5);
                    session.player.spawnParticle(Particle.LAVA, session.lavaSpot, 8, 0.2, 0.12, 0.2, 0);
                } else if (transition == FishingTiming.Transition.MISSED) {
                    call(new PlayerFishEvent(session.player, null, hook, session.hand, PlayerFishEvent.State.FAILED_ATTEMPT));
                    session.player.sendMessage(ChatColor.GRAY + "鱼儿跑掉了，继续等待下一次咬钩。");
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Fishing session failed for " + session.player.getUniqueId(), exception);
                discard(session, true);
            }
        }
    }

    private void reelLava(Session session) {
        if (sessions.get(session.player.getUniqueId()) != session) return;
        boolean ready = session.timing != null && session.timing.canCatch();
        // Detach first so events or other callbacks cannot redeem the same bite twice.
        discard(session, false);
        try {
            if (!ready) {
                call(new PlayerFishEvent(session.player, null, session.hook, session.hand, PlayerFishEvent.State.REEL_IN));
                session.player.sendMessage(ChatColor.GRAY + "已收竿，鱼儿尚未咬钩。");
                return;
            }
            PlayerFishEvent caught = new PlayerFishEvent(session.player, null, session.hook, session.hand, PlayerFishEvent.State.CAUGHT_FISH);
            caught.setExpToDrop(ThreadLocalRandom.current().nextInt(1, 7));
            call(caught);
            if (caught.isCancelled() || !valid(session)) return;
            if (award(session, session.lavaSpot.clone(), true)) {
                damageRod(session);
                int experience = Math.max(0, Math.min(1000, caught.getExpToDrop()));
                if (experience > 0) {
                    session.player.getWorld().spawn(session.player.getLocation(), ExperienceOrb.class,
                            orb -> orb.setExperience(experience));
                }
                session.player.playSound(session.player.getLocation(), "entity.fishing_bobber.retrieve", 1F, 1F);
            }
        } finally {
            session.hook.remove();
        }
    }

    private boolean award(Session session, Location spot, boolean lava) {
        try {
            return backend.catchFish(session.player, spot, session.castRod.clone(), lava);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Unable to grant fishing reward for " + session.player.getUniqueId(), exception);
            session.player.sendMessage(ChatColor.RED + "本次渔获发放失败，请联系管理员检查记录。");
            return false;
        }
    }

    private boolean valid(Session session) {
        Player player = session.player;
        if (!player.isOnline() || player.isDead() || !backend.enabled(player)
                || !session.hook.isValid() || session.hook.isDead()
                || !player.getWorld().equals(session.hook.getWorld())
                || player.getLocation().distanceSquared(session.hook.getLocation()) > MAX_DISTANCE_SQUARED) return false;
        if (!backend.sameRod(player, session.hand, session.rodId)) return false;
        ItemStack current = player.getInventory().getItem(session.hand);
        return current != null && current.isSimilar(session.castRod);
    }

    private int waitTicks(ItemStack rod) {
        int minimum = Math.max(1, Math.min(72000, backend.minWaitTicks()));
        int maximum = Math.max(minimum, Math.min(72000, backend.maxWaitTicks()));
        int base = ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
        int lure = Math.min(20, enchantmentLevel(rod, "lure"));
        return Math.max(40, base - lure * 100);
    }

    private static int enchantmentLevel(ItemStack rod, String name) {
        return rod.getEnchantments().entrySet().stream()
                .filter(entry -> entry.getKey().getKey().getKey().equals(name))
                .mapToInt(Map.Entry::getValue).max().orElse(0);
    }

    private static Location lavaOnPath(Location previous, Location current) {
        if (previous == null || !previous.getWorld().equals(current.getWorld())) return null;
        Vector movement = current.toVector().subtract(previous.toVector());
        double length = movement.length();
        if (length < 0.001 || length > 8) return null;
        RayTraceResult trace = current.getWorld().rayTraceBlocks(previous, movement.multiply(1 / length),
                length, FluidCollisionMode.ALWAYS, false);
        if (trace == null || trace.getHitBlock() == null || trace.getHitBlock().getType() != Material.LAVA) return null;
        Location hit = trace.getHitPosition().toLocation(current.getWorld()).subtract(0, 0.01, 0);
        return lavaSurface(hit);
    }

    /** A native fish hook may be fractionally above the liquid surface when the tick runs. */
    private static Location lavaSurface(Location location) {
        Block block = location.getBlock();
        if (block.getType() != Material.LAVA) {
            if (location.getY() - block.getY() > 0.35) return null;
            block = block.getRelative(0, -1, 0);
            if (block.getType() != Material.LAVA) return null;
        }
        // Fish only an exposed surface; deep or covered lava must not act as a bobber platform.
        if (!block.getRelative(0, 1, 0).isPassable() || block.getRelative(0, 1, 0).getType() == Material.LAVA) return null;
        return new Location(location.getWorld(), location.getX(), block.getY() + 1.04, location.getZ());
    }

    private void call(PlayerFishEvent event) {
        synthetic.add(event);
        try { plugin.getServer().getPluginManager().callEvent(event); }
        finally { synthetic.remove(event); }
    }

    private void damageRod(Session session) {
        Player player = session.player;
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack rod = player.getInventory().getItem(session.hand);
        if (rod == null || rod.getType() != Material.FISHING_ROD || !(rod.getItemMeta() instanceof Damageable meta) || meta.isUnbreakable()) return;
        int unbreaking = Math.max(0, Math.min(1000, enchantmentLevel(rod, "unbreaking")));
        if (ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) return;
        PlayerItemDamageEvent damage = new PlayerItemDamageEvent(player, rod, 1);
        plugin.getServer().getPluginManager().callEvent(damage);
        if (damage.isCancelled() || damage.getDamage() <= 0) return;
        long updated = (long) meta.getDamage() + damage.getDamage();
        int maximum = meta.hasMaxDamage() ? meta.getMaxDamage() : Material.FISHING_ROD.getMaxDurability();
        if (updated >= maximum) {
            player.getInventory().setItem(session.hand, new ItemStack(Material.AIR));
            plugin.getServer().getPluginManager().callEvent(new PlayerItemBreakEvent(player, rod));
            player.playSound(player.getLocation(), "entity.item.break", 1F, 1F);
        } else {
            meta.setDamage((int) updated);
            rod.setItemMeta(meta);
            player.getInventory().setItem(session.hand, rod);
        }
    }

    private EquipmentSlot fishingHand(PlayerFishEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return EquipmentSlot.OFF_HAND;
        if (event.getHand() == EquipmentSlot.HAND) return EquipmentSlot.HAND;
        return event.getPlayer().getInventory().getItemInMainHand().getType() == Material.FISHING_ROD
                ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
    }

    private void discard(Session session, boolean removeHook) {
        sessions.remove(session.player.getUniqueId(), session);
        hooks.remove(session.hook.getUniqueId(), session);
        if (removeHook) session.hook.remove();
    }

    /** Immediately clears a player's bobber, including after they disable custom fishing in the GUI. */
    public void cancel(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) discard(session, true);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onKick(PlayerKickEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onDeath(PlayerDeathEvent event) { cancel(event.getEntity()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onWorld(PlayerChangedWorldEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onSwap(PlayerSwapHandItemsEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.hand == EquipmentSlot.HAND) cancel(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && event.getItemDrop().getItemStack().isSimilar(session.castRod)) cancel(event.getPlayer());
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ticker.cancel();
        HandlerList.unregisterAll(this);
        for (Session session : new ArrayList<>(sessions.values())) discard(session, true);
        synthetic.clear();
    }

    private static final class Session {
        private final Player player;
        private final FishHook hook;
        private final EquipmentSlot hand;
        private final UUID rodId;
        private final ItemStack castRod;
        private final boolean fireproof;
        private Location lavaSpot;
        private FishingTiming timing;
        private Location previous;

        private Session(Player player, FishHook hook, EquipmentSlot hand, UUID rodId, ItemStack castRod, boolean fireproof) {
            this.player = player;
            this.hook = hook;
            this.hand = hand;
            this.rodId = rodId;
            this.castRod = castRod;
            this.fireproof = fireproof;
            this.previous = hook.getLocation();
        }
    }
}
