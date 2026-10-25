package cn.bucheng.stardewfishing;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Spigot 1.21.4 entry point. All gameplay and economy operations run on the server thread. */
public final class StardewFishingPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener, FishingEngine.Backend {
    private Settings settings;
    private Items items;
    private PlayerStore store;
    private EconomyBridge economy;
    private Market market;
    private Menus menus;
    private Recipes recipes;
    private FishingEngine engine;
    private CatchRoller roller;

    @Override public void onEnable() {
        try {
            saveDefaultConfig(); if (!new File(getDataFolder(), "fish.yml").exists()) saveResource("fish.yml", false);
            settings = readSettings();
            items = new Items(this, () -> settings, new ItemSigner(getDataFolder().toPath().resolve("item-key.bin")));
            store = new PlayerStore(getDataFolder().toPath().resolve("players"));
            economy = new EconomyBridge(this);
            market = new Market(items, economy, () -> settings, this::give);
            roller = new CatchRoller(settings, ThreadLocalRandom.current());
            engine = new FishingEngine(this, this);
            menus = new Menus(this, () -> settings, items, store, market, this::toggle, p -> engine.cancel(p));
            recipes = new Recipes(this, () -> settings, items); recipes.install();
            var manager = getServer().getPluginManager();
            manager.registerEvents(engine, this); manager.registerEvents(menus, this); manager.registerEvents(recipes, this);
            manager.registerEvents(new FoodListener(this, items, () -> settings), this); manager.registerEvents(this, this);
            PluginCommand command = Objects.requireNonNull(getCommand("sfish")); command.setExecutor(this); command.setTabCompleter(this);
            getLogger().info("星露渔歌已启用：" + settings.fish.size() + " 个鱼种，经济服务" + (economy.available() ? "已连接" : "未连接（售卖与购买暂不可用）"));
        } catch (Exception | LinkageError e) {
            getLogger().log(Level.SEVERE, "星露渔歌启动失败，已停止加载。请修复配置或恢复数据备份。", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        if (menus != null) menus.closeAll();
        if (engine != null) engine.close();
        if (recipes != null) recipes.close();
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
    }
    private Settings readSettings() throws Exception {
        var config = new org.bukkit.configuration.file.YamlConfiguration(); config.load(new File(getDataFolder(), "config.yml"));
        var fish = new org.bukkit.configuration.file.YamlConfiguration(); fish.load(new File(getDataFolder(), "fish.yml"));
        return new Settings(config, fish);
    }
    private void reloadSettings() throws Exception {
        Settings replacement = readSettings(), previous = settings;
        settings = replacement;
        try { recipes.install(); }
        catch (RuntimeException e) {
            settings = previous;
            try { recipes.install(); } catch (RuntimeException rollback) { e.addSuppressed(rollback); }
            throw e;
        }
        menus.closeAll(); engine.close(); engine = new FishingEngine(this, this);
        getServer().getPluginManager().registerEvents(engine, this);
        roller = new CatchRoller(settings, ThreadLocalRandom.current());
    }
    @Override public boolean enabled(Player player) {
        return player.hasPermission("stardewfishing.use") && !settings.disabledWorlds.contains(player.getWorld().getName())
                && store.get(player.getUniqueId(), settings.defaultEnabled).enabled();
    }
    @Override public boolean lavaAllowed(ItemStack rod) {
        Settings.Supply tackle = settings.tackles.get(items.rod(rod).tackle());
        return tackle != null && tackle.lava();
    }
    @Override public UUID identifyRod(ItemStack rod) { return items.identifyRod(rod); }
    @Override public boolean sameRod(Player player, EquipmentSlot hand, UUID id) { return id.equals(items.rod(player.getInventory().getItem(hand)).id()); }
    @Override public int minWaitTicks() { return settings.lavaMinWait; }
    @Override public int maxWaitTicks() { return settings.lavaMaxWait; }
    @Override public int biteWindowTicks() { return settings.lavaBiteWindow; }

    @Override public boolean catchFish(Player player, Location spot, ItemStack castRod, boolean lava) {
        if (!enabled(player)) return false;
        int baitSlot = -1; Settings.Supply bait = null;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            Settings.Supply candidate = items.readSupply(storage[i], "bait");
            if (candidate != null && (bait == null || candidate.tier() > bait.tier())) { bait = candidate; baitSlot = i; }
        }
        FishingContext context = settings.context(spot, lava, bait == null ? 0 : bait.tier());
        if (settings.fish.values().stream().noneMatch(f -> f.rarity() != FishSpecies.Rarity.KING && f.matches(context))) {
            Text.send(player, "&7这里暂时没有可钓的鱼，试试其他水域吧。"); return false;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() < settings.baitChance) {
            Settings.Supply drop = roller.baitDrop(); give(player, items.supply("bait", drop, 1));
            Text.send(player, "&a你钓到了 " + drop.name() + "，可放在背包中使用。"); return true;
        }
        Items.Rod rod = items.rod(castRod);
        Settings.Supply tackle = settings.tackles.get(rod.tackle());
        Settings.Upgrade upgrade = settings.upgrades.get(rod.level());
        double luck = (bait == null ? 0 : bait.luck()) + (tackle == null ? 0 : tackle.luck()) + (upgrade == null ? 0 : upgrade.luck());
        luck += castRod.getEnchantments().entrySet().stream().filter(e -> e.getKey().getKey().getKey().equals("luck_of_the_sea")).mapToInt(Map.Entry::getValue).max().orElse(0) * .1;
        luck += player.getActivePotionEffects().stream().filter(e -> e.getType().getKey().getKey().equals("luck")).mapToDouble(e -> (e.getAmplifier() + 1) * .1).sum();
        double lengthBonus = (bait == null ? 0 : bait.lengthBonus()) + (tackle == null ? 0 : tackle.lengthBonus()) + (upgrade == null ? 0 : upgrade.lengthBonus());
        long now = System.currentTimeMillis();
        CatchRoller.Catch caught = roller.roll(context, luck, lengthBonus,
                f -> store.kingReady(player.getUniqueId(), settings.defaultEnabled, f, now, settings.kingCooldownHours));
        if (caught == null) return false;
        ItemStack reward = items.fish(caught.species(), caught.lengthTenths(), false);
        boolean first = store.caught(player.getUniqueId(), settings.defaultEnabled, caught.species(), caught.lengthTenths(), now);
        if (baitSlot >= 0) {
            ItemStack consumed = storage[baitSlot].clone(); consumed.setAmount(consumed.getAmount() - 1);
            player.getInventory().setItem(baitSlot, consumed.getAmount() == 0 ? null : consumed);
        }
        give(player, reward);
        FishSpecies fish = caught.species();
        Text.send(player, fish.rarity().color + "钓到了 " + fish.name() + " &b" + Text.length(caught.lengthTenths()) + " cm &7· &e"
                + Text.money(fish.price(caught.lengthTenths() / 10.0, false, settings.cookedMultiplier)) + " 金币" + (first ? " &a[图鉴已解锁]" : ""));
        if (fish.rarity() == FishSpecies.Rarity.KING) {
            player.sendTitle(Text.color("&6传说渔获"), Text.color("&e" + fish.name()), 10, 60, 20);
            player.playSound(player.getLocation(), "ui.toast.challenge_complete", 1F, 1F);
        }
        return true;
    }
    private void toggle(Player player) {
        store.toggle(player.getUniqueId(), settings.defaultEnabled);
        engine.cancel(player);
        Text.send(player, store.get(player.getUniqueId(), settings.defaultEnabled).enabled() ? "&a特色钓鱼已开启。" : "&7特色钓鱼已关闭，现在可以按原版规则钓鱼。");
    }
    void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (overflow.isEmpty()) return;
        overflow.values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left, drop -> {
            drop.setOwner(player.getUniqueId()); drop.setPickupDelay(0); drop.setInvulnerable(true); drop.setFireTicks(0);
        }));
        Text.send(player, "&e背包已满，物品已放在你的脚边，请及时拾取。");
    }
    @EventHandler public void quit(PlayerQuitEvent event) { store.forget(event.getPlayer().getUniqueId()); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("stardewfishing.admin")) { Text.send(sender, "&c你暂时不能执行这个操作。"); return true; }
                reloadSettings(); Text.send(sender, "&a配置、鱼种与合成配方已重载。"); return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("give")) {
                if (!sender.hasPermission("stardewfishing.admin")) { Text.send(sender, "&c你暂时不能执行这个操作。"); return true; }
                adminGive(sender, args); return true;
            }
            if (!(sender instanceof Player player)) { Text.send(sender, "&e玩家可使用 /sfish 打开菜单，控制台可执行 reload 或 give。"); return true; }
            if (!player.hasPermission("stardewfishing.use")) { Text.send(player, "&c你暂时不能使用钓鱼手册。"); return true; }
            if (args.length == 0) menus.home(player);
            else switch (args[0].toLowerCase(Locale.ROOT)) {
                case "toggle" -> toggle(player); case "book" -> menus.catalog(player, "all", 0);
                case "caught" -> menus.catalog(player, "caught", 0); case "kings" -> menus.catalog(player, "king", 0);
                case "sell" -> menus.sell(player); case "rod" -> menus.workshop(player); case "bait" -> menus.guide(player);
                default -> Text.send(player, "&7使用 /sfish 打开手册；可用 book、caught、kings、sell、rod、bait、toggle。");
            }
        } catch (IllegalArgumentException e) { Text.send(sender, "&e" + e.getMessage()); }
        catch (Exception e) { getLogger().log(Level.SEVERE, "钓鱼命令执行失败", e); Text.send(sender, "&c操作未完成，原配置或数据已保留，请联系管理员查看记录。"); }
        return true;
    }
    private void adminGive(CommandSender sender, String[] args) {
        if (args.length < 5) throw new IllegalArgumentException("用法: /sfish give <玩家> <fish|bait|tackle> <ID> <数量1~64> [鱼长cm] [cooked]");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) throw new IllegalArgumentException("目标玩家必须在线。");
        int amount = Integer.parseInt(args[4]); if (amount < 1 || amount > 64) throw new IllegalArgumentException("数量须为1~64。");
        ItemStack item;
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "fish" -> {
                FishSpecies fish = settings.fish.get(args[3]); if (fish == null) throw new IllegalArgumentException("鱼种ID不存在。");
                double size = args.length > 5 ? Double.parseDouble(args[5]) : fish.minLength();
                if (!Double.isFinite(size) || size < fish.minLength() || size > fish.maxLength()) throw new IllegalArgumentException("长度超出该鱼种范围。");
                item = items.fish(fish, (int) Math.round(size * 10), args.length > 6 && args[6].equalsIgnoreCase("cooked")); item.setAmount(amount);
            }
            case "bait", "tackle" -> {
                var map = args[2].equalsIgnoreCase("bait") ? settings.baits : settings.tackles;
                Settings.Supply supply = map.get(args[3]); if (supply == null) throw new IllegalArgumentException("物品ID不存在。");
                item = items.supply(args[2].toLowerCase(Locale.ROOT), supply, amount);
            }
            default -> throw new IllegalArgumentException("类型只能是 fish、bait 或 tackle。");
        }
        give(target, item); Text.send(sender, "&a已发放物品给 " + target.getName() + "。管理员发放不会解锁图鉴。");
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("stardewfishing.use")) choices.addAll(List.of("book", "caught", "kings", "sell", "rod", "bait", "toggle"));
            if (sender.hasPermission("stardewfishing.admin")) choices.addAll(List.of("reload", "give"));
        } else if (sender.hasPermission("stardewfishing.admin") && args[0].equalsIgnoreCase("give")) {
            if (args.length == 2) Bukkit.getOnlinePlayers().forEach(p -> choices.add(p.getName()));
            if (args.length == 3) choices.addAll(List.of("fish", "bait", "tackle"));
            if (args.length == 4) choices.addAll((switch (args[2]) { case "fish" -> settings.fish; case "bait" -> settings.baits; case "tackle" -> settings.tackles; default -> Map.<String, Object>of(); }).keySet());
            if (args.length == 5) choices.addAll(List.of("1", "16", "64"));
            if (args.length == 7 && args[2].equalsIgnoreCase("fish")) choices.add("cooked");
        }
        String start = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(c -> c.toLowerCase(Locale.ROOT).startsWith(start)).toList();
    }
}
