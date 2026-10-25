package cn.bucheng.stardewfishing;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Read-only virtual inventories: every click, drag, number-key and shift transfer is cancelled. */
public final class Menus implements Listener {
    private static final int[] GRID = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
    private final JavaPlugin plugin;
    private final Supplier<Settings> settings;
    private final Items items;
    private final PlayerStore store;
    private final Market market;
    private final Consumer<Player> toggle, cancelFishing;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public Menus(JavaPlugin plugin, Supplier<Settings> settings, Items items, PlayerStore store, Market market,
                 Consumer<Player> toggle, Consumer<Player> cancelFishing) {
        this.plugin = plugin; this.settings = settings; this.items = items; this.store = store; this.market = market;
        this.toggle = toggle; this.cancelFishing = cancelFishing;
    }
    public void home(Player player) {
        PlayerStore.Profile profile = profile(player);
        long ordinary = settings.get().fish.values().stream().filter(f -> f.rarity() != FishSpecies.Rarity.KING).count();
        long kings = settings.get().fish.size() - ordinary;
        long ownNormal = settings.get().fish.values().stream().filter(f -> f.rarity() != FishSpecies.Rarity.KING && profile.records().containsKey(f.id())).count();
        long ownKings = settings.get().fish.values().stream().filter(f -> f.rarity() == FishSpecies.Rarity.KING && profile.records().containsKey(f.id())).count();
        Menu m = menu(player, "&3星露渔歌 &8· &f钓鱼手册");
        m.button(11, Text.icon(Material.BOOK, "&b鱼类图鉴", "&7查看全部鱼种、长度和出没条件", "&7普通鱼收集：&a" + ownNormal + "/" + ordinary), c -> catalog(player, "all", 0));
        m.button(13, Text.icon(Material.WRITABLE_BOOK, "&a我的渔获图鉴", "&7查看已经钓获的鱼与个人最长纪录", "&7已发现：&e" + (ownNormal + ownKings) + " 种"), c -> catalog(player, "caught", 0));
        m.button(15, Text.icon(Material.HEART_OF_THE_SEA, "&6鱼王图鉴", "&7稀有传说与严苛的出没条件", "&7鱼王收集：&6" + ownKings + "/" + kings), c -> catalog(player, "king", 0));
        m.button(29, Text.icon(Material.GOLD_INGOT, "&e渔获售卖", "&7出售生鱼或烤鱼", "&7烤制售价 ×" + settings.get().cookedMultiplier, "&7鱼王有单独的售卖确认"), c -> sell(player));
        m.button(31, Text.icon(Material.FISHING_ROD, "&b鱼竿工坊", "&7请先在主手拿好需要改造的鱼竿", "&7改造、购买与镶嵌吊具"), c -> workshop(player));
        m.button(33, Text.icon(Material.WHEAT_SEEDS, "&a鱼饵与钓鱼指南", "&7鱼饵无序合成配方", "&7岩浆钓鱼与烤鱼说明"), c -> guide(player));
        m.button(49, Text.icon(profile.enabled() ? Material.LIME_DYE : Material.GRAY_DYE,
                profile.enabled() ? "&a特色钓鱼：已开启" : "&7特色钓鱼：已关闭",
                "&7点击切换，设置会永久保存", "&7关闭后按原版规则钓鱼"), c -> { toggle.accept(player); home(player); });
        player.openInventory(m.inventory);
    }
    public void catalog(Player player, String mode, int requestedPage) {
        PlayerStore.Profile profile = profile(player);
        List<FishSpecies> list = settings.get().fish.values().stream().filter(f -> switch (mode) {
            case "caught" -> profile.records().containsKey(f.id()); case "king" -> f.rarity() == FishSpecies.Rarity.KING; default -> true;
        }).toList();
        int pages = Math.max(1, (list.size() + GRID.length - 1) / GRID.length);
        int page = Math.clamp(requestedPage, 0, pages - 1);
        String title = switch (mode) { case "caught" -> "我的渔获图鉴"; case "king" -> "鱼王图鉴"; default -> "全部鱼类图鉴"; };
        Menu m = menu(player, "&3" + title + " &8· " + (page + 1) + "/" + pages);
        for (int i = 0; i < GRID.length && page * GRID.length + i < list.size(); i++) {
            FishSpecies fish = list.get(page * GRID.length + i);
            PlayerStore.Record record = profile.records().get(fish.id());
            List<String> lore = new ArrayList<>(List.of("&7品质：" + fish.rarity().color + fish.rarity().title,
                    "&7长度：&b" + fish.minLength() + " ~ " + fish.maxLength() + " cm",
                    "&7生鲜售价：&e" + Text.money(fish.minPrice()) + " ~ " + Text.money(fish.maxPrice()),
                    "&7出没：&f" + fish.locationHint(), "&7食用增益：&a" + Items.buffName(fish.buff()) + " I"));
            if (fish.minBait() > 0) lore.add("&7需要鱼饵等级：&e" + fish.minBait() + " 及以上");
            if (record == null) lore.add("&8尚未钓获");
            else {
                lore.add("&a已解锁 &7· 钓获 &e" + record.count() + " 条");
                lore.add("&7个人最长：&b" + Text.length(record.longestTenths()) + " cm");
                lore.add("&7首次钓获：&f" + DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(record.firstCaught())));
                if (fish.rarity() == FishSpecies.Rarity.KING) {
                    long remaining = record.lastKing() + (long) (settings.get().kingCooldownHours * 3600000) - System.currentTimeMillis();
                    lore.add(remaining <= 0 ? "&a鱼王可再次挑战" : "&7再次挑战还需：&e" + Math.max(1, (remaining + 59999) / 60000) + " 分钟");
                }
            }
            m.inventory.setItem(GRID[i], Text.icon(fish.material(), fish.rarity().color + fish.name(), lore));
        }
        if (list.isEmpty()) m.inventory.setItem(22, Text.icon(Material.GLASS_BOTTLE, "&7尚无钓获记录", "&7拿起鱼竿，去水边试试吧。"));
        if (page > 0) m.button(45, Text.icon(Material.ARROW, "&e上一页"), c -> catalog(player, mode, page - 1));
        if (page + 1 < pages) m.button(53, Text.icon(Material.ARROW, "&e下一页"), c -> catalog(player, mode, page + 1));
        back(m, player); player.openInventory(m.inventory);
    }
    public void sell(Player player) {
        Menu m = menu(player, "&3渔获售卖");
        Market.Quote hand = market.quote(player, false, true), normal = market.quote(player, false, false), kings = market.quote(player, true, false);
        ItemStack held = player.getInventory().getItemInMainHand();
        m.button(20, Text.icon(Material.COD, "&a出售主手渔获", "&7数量：&f" + hand.count(), "&7预计收入：&e" + Text.money(hand.amount()), "&7点击出售主手整组鱼"), c -> {
            Items.Fish handFish = items.readFish(player.getInventory().getItemInMainHand());
            if (handFish != null && handFish.species().rarity() == FishSpecies.Rarity.KING) confirmKing(player, true);
            else { market.sell(player, false, true); sell(player); }
        });
        m.button(22, Text.icon(Material.CHEST, "&e出售背包普通鱼", "&7数量：&f" + normal.count(), "&7预计收入：&e" + Text.money(normal.amount()), "&7点击出售所有品质的普通鱼", "&7鱼王会保留"), c -> { market.sell(player, false, false); sell(player); });
        m.button(24, Text.icon(Material.HEART_OF_THE_SEA, "&6出售背包鱼王", "&7数量：&f" + kings.count(), "&7预计收入：&e" + Text.money(kings.amount()), "&7点击后再次确认"), c -> confirmKing(player, false));
        m.inventory.setItem(40, Text.icon(Material.CAMPFIRE, "&6烤鱼更值钱", "&7熔炉、烟熏炉或营火可烤制", "&7烤制售价 ×" + settings.get().cookedMultiplier, "&7图鉴记录不会因出售或食用而消失", "&8价格以点击成交时的实际背包为准"));
        back(m, player); player.openInventory(m.inventory);
    }
    private void confirmKing(Player player, boolean hand) {
        Market.Quote quote = market.quote(player, true, hand);
        Menu m = menu(player, "&6确认出售鱼王");
        m.button(21, Text.icon(Material.LIME_WOOL, "&a确认出售", "&7将出售 " + quote.count() + " 条，预计 " + Text.money(quote.amount()) + " 金币", "&7图鉴和最长纪录会保留"), c -> {
            if (!quote.equals(market.quote(player, true, hand))) { confirmKing(player, hand); Text.send(player, "&e渔获或价格有变化，请核对后再确认。"); return; }
            market.sell(player, true, hand); sell(player);
        });
        m.button(23, Text.icon(Material.RED_WOOL, "&c保留鱼王"), c -> sell(player));
        back(m, player); player.openInventory(m.inventory);
    }
    public void workshop(Player player) {
        Menu m = menu(player, "&3鱼竿工坊 &8· 主手鱼竿");
        ItemStack held = player.getInventory().getItemInMainHand();
        boolean hasRod = held.getType() == Material.FISHING_ROD;
        Items.Rod rod = items.rod(held);
        m.inventory.setItem(4, hasRod ? held.clone() : Text.icon(Material.BARRIER, "&c请先在主手拿好鱼竿"));
        Settings.Upgrade next = settings.get().upgrades.get(rod.level() + 1);
        m.button(11, Text.icon(Material.ANVIL, "&b鱼竿精工改造", "&7当前等级：&e" + rod.level() + "/3",
                next == null ? "&a已达到最高等级" : "&7下一等级费用：&e" + Text.money(next.price()) + " 金币",
                next == null ? "&7享受垂钓吧" : "&7改造提升珍稀概率与长度分布"), c -> { cancelFishing.accept(player); market.upgrade(player); workshop(player); });
        m.button(13, Text.icon(Material.SHEARS, "&e拆卸吊具", "&7旧吊具会返还背包", "&7请预留一个空格"), c -> { cancelFishing.accept(player); market.remove(player); workshop(player); });
        m.button(15, Text.icon(Material.BOOK, "&a工坊说明", "&7每根鱼竿有一个吊具槽", "&7左键：消耗背包中吊具并镶嵌", "&7右键：购买一个吊具", "&7更换时返还旧吊具，需一个空格", "&7镶嵌和拆卸免费，改造按级收费"), c -> guide(player));
        int index = 0;
        for (Settings.Supply tackle : settings.get().tackles.values()) {
            int slot = index < 7 ? 28 + index : 37 + index - 7;
            if (slot > 43) break;
            index++;
            ItemStack icon = items.supply("tackle", tackle, 1);
            var meta = icon.getItemMeta(); List<String> lore = new ArrayList<>(meta.getLore());
            lore.add(Text.color("&7购买价格：&e" + Text.money(tackle.price()) + " 金币"));
            lore.add(Text.color(rod.tackle().equals(tackle.id()) ? "&a当前已镶嵌" : "&b左键镶嵌 &8| &e右键购买"));
            meta.setLore(lore); icon.setItemMeta(meta);
            m.button(slot, icon, click -> {
                cancelFishing.accept(player);
                if (click == ClickType.RIGHT) market.buyTackle(player, tackle.id()); else market.install(player, tackle.id());
                workshop(player);
            });
        }
        back(m, player); player.openInventory(m.inventory);
    }
    public void guide(Player player) {
        Menu m = menu(player, "&3鱼饵与钓鱼指南");
        int index = 0;
        for (Settings.BaitRecipe recipe : settings.get().recipes) {
            if (index >= 7) break;
            Settings.Supply bait = settings.get().baits.get(recipe.id());
            List<String> lore = new ArrayList<>(List.of("&7无序合成：摆放位置任意", "&7产量：&e" + recipe.amount() + " 个", "&7材料："));
            recipe.ingredients().forEach(input -> lore.add("&f  " + ingredientName(input)));
            lore.add("&7珍稀加成：&a+" + Math.round(bait.luck() * 100) + "%");
            m.inventory.setItem(10 + index++, Text.icon(bait.material(), "&a" + bait.name(), lore));
        }
        m.inventory.setItem(29, Text.icon(Material.FISHING_ROD, "&b钓鱼方式", "&7正常抛竿、等待咬钩后收竿", "&7没有额外小游戏", "&7成功钓鱼时消耗背包最高级鱼饵", "&7钓到额外鱼饵时不消耗鱼饵", "&7加成越高，越容易获得稀有与更长的鱼"));
        m.inventory.setItem(31, Text.icon(Material.MAGMA_CREAM, "&6岩浆钓鱼", "&7安装熔岩吊具后前往地狱", "&7向敞开的岩浆表面抛竿", "&7等待咬钩提示后及时右键收竿", "&7提前收竿不会获得渔获"));
        m.inventory.setItem(33, Text.icon(Material.COOKED_SALMON, "&e烤制与鱼王", "&7鱼烤制后保留种类和长度", "&7烤鱼售价 ×" + settings.get().cookedMultiplier + "，增益时间更长", "&7不同种类或长度的鱼请分批取出烤制", "&7同一种鱼王个人冷却 " + settings.get().kingCooldownHours + " 小时", "&7鱼王必须满足图鉴中的出没条件"));
        back(m, player); player.openInventory(m.inventory);
    }
    private String ingredientName(String input) {
        if (input.startsWith("bait:")) return settings.get().baits.get(input.substring(5)).name();
        return switch (input) {
            case "WHEAT" -> "小麦"; case "ROTTEN_FLESH" -> "腐肉"; case "SUGAR" -> "糖";
            case "DRIED_KELP" -> "干海带"; case "PRISMARINE_CRYSTALS" -> "海晶砂粒";
            case "HONEY_BOTTLE" -> "蜂蜜瓶"; case "GHAST_TEAR" -> "恶魂之泪"; case "GLOWSTONE_DUST" -> "荧石粉"; default -> input;
        };
    }
    private PlayerStore.Profile profile(Player player) { return store.get(player.getUniqueId(), settings.get().defaultEnabled); }
    private Menu menu(Player player, String title) {
        Menu menu = new Menu(player.getUniqueId());
        menu.inventory = Bukkit.createInventory(menu, 54, Text.color(title));
        ItemStack pane = Text.icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) menu.inventory.setItem(i, pane);
        return menu;
    }
    private void back(Menu menu, Player player) { menu.button(49, Text.icon(Material.ARROW, "&f返回钓鱼手册"), c -> home(player)); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.owner.equals(player.getUniqueId())
                || !player.hasPermission("stardewfishing.use") || event.getRawSlot() < 0 || event.getRawSlot() >= 54
                || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) return;
        Consumer<ClickType> action = menu.actions.get(event.getRawSlot());
        long now = System.nanoTime();
        if (action == null || now - lastClick.getOrDefault(player.getUniqueId(), 0L) < 180000000L) return;
        lastClick.put(player.getUniqueId(), now);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != menu.inventory || !player.hasPermission("stardewfishing.use")) return;
            try { action.accept(event.getClick()); }
            catch (IllegalArgumentException e) { Text.send(player, "&e" + e.getMessage()); }
            catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "钓鱼菜单操作失败: " + player.getUniqueId(), e);
                Text.send(player, "&c操作未完成，请联系管理员查看记录。");
            }
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) { if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true); }
    @EventHandler public void quit(PlayerQuitEvent event) { lastClick.remove(event.getPlayer().getUniqueId()); }
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) player.closeInventory();
    }
    private static final class Menu implements InventoryHolder {
        final UUID owner; Inventory inventory; final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();
        Menu(UUID owner) { this.owner = owner; }
        void button(int slot, ItemStack item, Consumer<ClickType> click) { inventory.setItem(slot, item); actions.put(slot, click); }
        @Override public Inventory getInventory() { return inventory; }
    }
}
