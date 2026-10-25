package cn.bucheng.stardewfishing;

import java.util.*;
import java.util.function.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;

/** Server-thread transactions authenticate each item and restore inventory when Vault rejects a sale. */
public final class Market {
    public record Quote(int count, double amount) { }
    private final Items items;
    private final EconomyBridge economy;
    private final Supplier<Settings> settings;
    private final BiConsumer<Player, ItemStack> give;
    private final Set<UUID> busy = new HashSet<>();
    public Market(Items items, EconomyBridge economy, Supplier<Settings> settings, BiConsumer<Player, ItemStack> give) {
        this.items = items; this.economy = economy; this.settings = settings; this.give = give;
    }

    public Quote quote(Player player, boolean kings, boolean handOnly) {
        return summarize(selected(player, kings, handOnly));
    }
    private Map<Integer, ItemStack> selected(Player player, boolean kings, boolean handOnly) {
        Map<Integer, ItemStack> result = new LinkedHashMap<>();
        PlayerInventory inventory = player.getInventory();
        if (handOnly) {
            int slot = inventory.getHeldItemSlot();
            ItemStack item = inventory.getItem(slot);
            Items.Fish fish = items.readFish(item);
            if (fish != null && (!kings || fish.species().rarity() == FishSpecies.Rarity.KING)) result.put(slot, item.clone());
        } else {
            ItemStack[] contents = inventory.getStorageContents();
            for (int i = 0; i < contents.length; i++) {
                Items.Fish fish = items.readFish(contents[i]);
                if (fish != null && (fish.species().rarity() == FishSpecies.Rarity.KING) == kings) result.put(i, contents[i].clone());
            }
        }
        return result;
    }
    private Quote summarize(Map<Integer, ItemStack> selected) {
        int count = 0; double amount = 0;
        for (ItemStack item : selected.values()) {
            Items.Fish fish = items.readFish(item);
            count += item.getAmount();
            amount += fish.species().price(fish.lengthTenths() / 10.0, fish.cooked(), settings.get().cookedMultiplier) * item.getAmount();
        }
        return new Quote(count, economy.round(amount));
    }
    public void sell(Player player, boolean kings, boolean handOnly) {
        if (!economy.available()) throw new IllegalArgumentException("交易服务暂未开放，请联系管理员。");
        if (!busy.add(player.getUniqueId())) return;
        try {
            Map<Integer, ItemStack> selected = selected(player, kings, handOnly);
            Quote quote = summarize(selected);
            if (quote.count() == 0 || quote.amount() <= 0) throw new IllegalArgumentException("没有可售卖的插件鱼类。");
            selected.keySet().forEach(slot -> player.getInventory().setItem(slot, null));
            boolean paid = false;
            try { paid = economy.deposit(player, quote.amount()); }
            finally {
                if (!paid) selected.values().forEach(item -> give.accept(player, item));
            }
            if (!paid) throw new IllegalArgumentException("交易未成功，鱼已退回，请稍后重试。");
            Text.send(player, "&a出售了 " + quote.count() + " 条鱼，获得 &e" + Text.money(quote.amount()) + " 金币&a。");
        } finally { busy.remove(player.getUniqueId()); }
    }

    public void buyTackle(Player player, String id) {
        Settings.Supply supply = requireTackle(id);
        double price = economy.round(supply.price());
        if (!economy.withdraw(player, price)) throw new IllegalArgumentException("购买失败：余额不足或经济服务暂不可用。");
        give.accept(player, items.supply("tackle", supply, 1));
        Text.send(player, "&a花费 " + Text.money(price) + " 金币购买了 " + supply.name() + "。");
    }
    public void install(Player player, String id) {
        Settings.Supply supply = requireTackle(id);
        ItemStack held = heldRod(player);
        Items.Rod rod = items.rod(held);
        if (rod.tackle().equals(id)) throw new IllegalArgumentException("这根鱼竿已经镶嵌了这个吊具。");
        int source = -1;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            Settings.Supply found = items.readSupply(storage[i], "tackle");
            if (found != null && found.id().equals(id)) { source = i; break; }
        }
        if (source < 0) throw new IllegalArgumentException("背包里没有这个吊具，可右键工坊图标购买。");
        Settings.Supply old = settings.get().tackles.get(rod.tackle());
        if (old != null && player.getInventory().firstEmpty() == -1) throw new IllegalArgumentException("请先留出一个空背包格，用于返还旧吊具。");
        ItemStack changed = held.clone(); items.modifyRod(changed, new Items.Rod(rod.id(), rod.level(), id));
        ItemStack ingredient = storage[source].clone(); ingredient.setAmount(ingredient.getAmount() - 1);
        player.getInventory().setItem(source, ingredient.getAmount() == 0 ? null : ingredient);
        player.getInventory().setItemInMainHand(changed);
        if (old != null) give.accept(player, items.supply("tackle", old, 1));
        Text.send(player, "&a已镶嵌 " + supply.name() + "。");
    }
    public void remove(Player player) {
        ItemStack held = heldRod(player);
        Items.Rod rod = items.rod(held);
        Settings.Supply old = settings.get().tackles.get(rod.tackle());
        if (old == null) throw new IllegalArgumentException("当前鱼竿没有吊具。");
        if (player.getInventory().firstEmpty() == -1) throw new IllegalArgumentException("请先留出一个空背包格。");
        ItemStack changed = held.clone(); items.modifyRod(changed, new Items.Rod(rod.id(), rod.level(), ""));
        player.getInventory().setItemInMainHand(changed); give.accept(player, items.supply("tackle", old, 1));
        Text.send(player, "&a已拆下并返还 " + old.name() + "。");
    }
    public void upgrade(Player player) {
        ItemStack held = heldRod(player);
        Items.Rod rod = items.rod(held);
        Settings.Upgrade next = settings.get().upgrades.get(rod.level() + 1);
        if (next == null) throw new IllegalArgumentException("这根鱼竿已经达到最高改造等级。");
        ItemStack changed = held.clone(); items.modifyRod(changed, new Items.Rod(rod.id(), rod.level() + 1, rod.tackle()));
        double price = economy.round(next.price());
        if (!economy.withdraw(player, price)) throw new IllegalArgumentException("改造失败：余额不足或经济服务暂不可用。");
        player.getInventory().setItemInMainHand(changed);
        Text.send(player, "&a鱼竿改造至 " + (rod.level() + 1) + " 级，花费 " + Text.money(price) + " 金币。");
    }
    private ItemStack heldRod(Player player) {
        ItemStack rod = player.getInventory().getItemInMainHand();
        if (rod.getType() != Material.FISHING_ROD) throw new IllegalArgumentException("请关闭界面，在主手拿好鱼竿，再进入工坊。");
        return rod;
    }
    private Settings.Supply requireTackle(String id) {
        Settings.Supply s = settings.get().tackles.get(id);
        if (s == null) throw new IllegalArgumentException("吊具配置已更新，请重新打开工坊。");
        return s;
    }
}
