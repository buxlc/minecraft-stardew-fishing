package cn.bucheng.stardewfishing;

import java.util.*;
import java.util.function.Supplier;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.block.Crafter;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Matches authenticated bait ingredients again on preparation and on the actual craft. */
public final class Recipes implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<Settings> settings;
    private final Items items;
    private final List<NamespacedKey> registered = new ArrayList<>();
    public Recipes(JavaPlugin plugin, Supplier<Settings> settings, Items items) {
        this.plugin = plugin; this.settings = settings; this.items = items;
    }
    public void install() {
        List<ShapelessRecipe> staged = new ArrayList<>();
        for (Settings.BaitRecipe definition : settings.get().recipes) {
            NamespacedKey key = new NamespacedKey(plugin, "bait_" + definition.id());
            ShapelessRecipe recipe = new ShapelessRecipe(key, items.supply("bait", settings.get().baits.get(definition.id()), definition.amount()));
            for (String ingredient : definition.ingredients()) {
                Material m = ingredient.startsWith("bait:") ? settings.get().baits.get(ingredient.substring(5)).material() : Material.matchMaterial(ingredient);
                recipe.addIngredient(new RecipeChoice.MaterialChoice(m));
            }
            staged.add(recipe);
        }
        close();
        for (ShapelessRecipe recipe : staged) {
            if (!Bukkit.addRecipe(recipe)) throw new IllegalStateException("无法注册鱼饵配方: " + recipe.getKey());
            registered.add(recipe.getKey());
        }
    }
    private Settings.BaitRecipe definition(Recipe recipe) {
        if (!(recipe instanceof Keyed keyed)) return null;
        return settings.get().recipes.stream().filter(r -> new NamespacedKey(plugin, "bait_" + r.id()).equals(keyed.getKey())).findFirst().orElse(null);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void prepare(PrepareItemCraftEvent event) {
        Settings.BaitRecipe recipe = definition(event.getRecipe());
        if (recipe != null) {
            if (!matches(recipe, event.getInventory().getMatrix())) event.getInventory().setResult(null);
        } else if (Arrays.stream(event.getInventory().getMatrix()).anyMatch(items::custom)) event.getInventory().setResult(null);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void craft(CraftItemEvent event) {
        Settings.BaitRecipe recipe = definition(event.getRecipe());
        if (recipe != null ? !matches(recipe, event.getInventory().getMatrix()) : Arrays.stream(event.getInventory().getMatrix()).anyMatch(items::custom)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void autoCraft(CrafterCraftEvent event) {
        if (!(event.getBlock().getState() instanceof Crafter crafter)) return;
        ItemStack[] matrix = crafter.getInventory().getContents();
        Settings.BaitRecipe recipe = definition(event.getRecipe());
        if (recipe != null ? !matches(recipe, matrix) : Arrays.stream(matrix).anyMatch(items::custom)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void useBait(PlayerInteractEvent event) {
        if (items.readSupply(event.getItem(), "bait") != null || items.readSupply(event.getItem(), "tackle") != null) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                event.setUseItemInHand(Event.Result.DENY);
                Text.send(event.getPlayer(), "&7鱼饵放在背包即可自动使用；吊具请在 /sfish 工坊镶嵌。");
            }
        }
    }
    boolean matches(Settings.BaitRecipe recipe, ItemStack[] matrix) {
        List<String> remaining = new ArrayList<>(recipe.ingredients());
        for (ItemStack item : matrix) {
            if (item == null || Text.air(item.getType())) continue;
            Settings.Supply bait = items.readSupply(item, "bait");
            String token = bait != null ? "bait:" + bait.id() : items.custom(item) ? "invalid" : item.getType().name();
            if (!remaining.remove(token)) return false;
        }
        return remaining.isEmpty();
    }
    @Override public void close() { registered.forEach(Bukkit::removeRecipe); registered.clear(); }
}
