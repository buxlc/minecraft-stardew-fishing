package cn.bucheng.stardewfishing;

import java.util.*;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Furnace;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Cooking carries the authenticated species and exact length into a newly signed cooked item. */
public final class FoodListener implements Listener {
    private final Items items;
    private final Supplier<Settings> settings;
    private final JavaPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    public FoodListener(JavaPlugin plugin, Items items, Supplier<Settings> settings) {
        this.plugin = plugin; this.items = items; this.settings = settings;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void cook(BlockCookEvent event) {
        Items.Fish fish = items.readFish(event.getSource());
        if (fish == null) {
            if (items.custom(event.getSource())) event.setCancelled(true);
            return;
        }
        if (fish.cooked()) { event.setCancelled(true); return; }
        ItemStack result = items.fish(fish.species(), fish.lengthTenths(), true);
        // Some server implementations only compare the output material during furnace checks.
        // Never let different lengths/species merge and inherit the first item's selling price.
        if (event.getBlock().getState() instanceof Furnace furnace) {
            ItemStack current = furnace.getInventory().getResult();
            if (current != null && !Text.air(current.getType())
                    && (!current.isSimilar(result) || current.getAmount() >= current.getMaxStackSize())) {
                event.setCancelled(true); return;
            }
        }
        event.setResult(result);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void eat(PlayerItemConsumeEvent event) {
        Items.Fish fish = items.readFish(event.getItem());
        if (fish == null) return;
        UUID id = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        if (cooldowns.getOrDefault(id, 0L) > now) return;
        Settings s = settings.get();
        int duration = fish.species().rarity() == FishSpecies.Rarity.KING ? s.kingBuffSeconds : fish.cooked() ? s.cookedBuffSeconds : s.rawBuffSeconds;
        PotionEffectType effect = Registry.EFFECT.get(NamespacedKey.minecraft(fish.species().buff().toLowerCase(Locale.ROOT)));
        if (effect == null) return;
        cooldowns.put(id, now + s.buffCooldown * 1000L);
        int ticks = fish.species().buff().equals("SATURATION") ? 1 : duration * 20;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.getPlayer().isOnline() && !event.getPlayer().isDead()) {
                event.getPlayer().addPotionEffect(new PotionEffect(effect, ticks, 0, true, true, true));
                Text.send(event.getPlayer(), "&a享用鲜鱼，获得 " + Items.buffName(fish.species().buff()) + " I。");
            }
        });
    }
    @EventHandler public void quit(PlayerQuitEvent event) { cooldowns.remove(event.getPlayer().getUniqueId()); }
}
