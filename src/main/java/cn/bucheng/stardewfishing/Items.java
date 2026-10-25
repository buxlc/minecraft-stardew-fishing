package cn.bucheng.stardewfishing;

import java.util.*;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Lore is presentation only. All trade, cooking and equipment decisions use authenticated data. */
public final class Items {
    public record Fish(FishSpecies species, int lengthTenths, boolean cooked) { }
    public record Rod(UUID id, int level, String tackle) { }
    private final Supplier<Settings> settings;
    private final ItemSigner signer;
    private final NamespacedKey kind, id, length, cooked, signature, rodId, rodLevel, rodTackle, rodSignature;
    private static final String ROD_HEADER = Text.color("&8[星露渔歌 · 鱼竿改造]");

    public Items(JavaPlugin plugin, Supplier<Settings> settings, ItemSigner signer) {
        this.settings = settings; this.signer = signer;
        kind = new NamespacedKey(plugin, "kind"); id = new NamespacedKey(plugin, "id");
        length = new NamespacedKey(plugin, "length"); cooked = new NamespacedKey(plugin, "cooked");
        signature = new NamespacedKey(plugin, "signature"); rodId = new NamespacedKey(plugin, "rod_id");
        rodLevel = new NamespacedKey(plugin, "rod_level"); rodTackle = new NamespacedKey(plugin, "rod_tackle");
        rodSignature = new NamespacedKey(plugin, "rod_signature");
    }

    public ItemStack fish(FishSpecies f, int size, boolean isCooked) {
        Material material = isCooked ? f.material() == Material.COD ? Material.COOKED_COD : Material.COOKED_SALMON : f.material();
        List<String> lore = new ArrayList<>(List.of("&7品质：" + f.rarity().color + f.rarity().title,
                "&7长度：&b" + Text.length(size) + " cm", "&7售价：&e" + Text.money(f.price(size / 10.0, isCooked, settings.get().cookedMultiplier)) + " 金币",
                "&7水域：&f" + f.locationHint(), "&7状态：" + (isCooked ? "&6烤制 · 售价 ×" + settings.get().cookedMultiplier : "&a生鲜 · 可放熔炉、烟熏炉或营火烤制"),
                "&7食用：&a" + buffName(f.buff()) + " I"));
        lore.add("&8通过 /sfish 出售或查阅图鉴");
        ItemStack item = Text.icon(material, f.rarity().color + (isCooked ? "烤制 · " : "") + f.name(), lore);
        ItemMeta meta = item.getItemMeta();
        var food = meta.getFood();
        food.setNutrition(isCooked ? 6 : 2); food.setSaturation(isCooked ? .6f : .1f);
        meta.setFood(food);
        // Copy the vanilla fish's consumable component; only food values are customized.
        write(meta.getPersistentDataContainer(), "fish", f.id(), size, isCooked);
        item.setItemMeta(meta);
        return item;
    }

    public Fish readFish(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer p = item.getItemMeta().getPersistentDataContainer();
        String value = p.get(id, PersistentDataType.STRING);
        Integer size = p.get(length, PersistentDataType.INTEGER), state = p.get(cooked, PersistentDataType.INTEGER);
        if (value == null || size == null || state == null || (state != 0 && state != 1) || !valid(p, "fish", value, size, state == 1)) return null;
        FishSpecies f = settings.get().fish.get(value);
        if (f == null || size < Math.ceil(f.minLength() * 10) || size > Math.floor(f.maxLength() * 10)) return null;
        Material expected = state == 1 ? f.material() == Material.COD ? Material.COOKED_COD : Material.COOKED_SALMON : f.material();
        return item.getType() == expected ? new Fish(f, size, state == 1) : null;
    }

    public ItemStack supply(String type, Settings.Supply s, int amount) {
        List<String> lore = new ArrayList<>();
        if (type.equals("bait")) {
            lore.add("&7等级：&e" + s.tier());
            lore.add("&7成功收获时自动使用背包中最高级鱼饵");
            lore.add("&7每次收获消耗 1 个，钓到鱼饵时不消耗");
        } else {
            lore.add("&7在鱼竿工坊为主手鱼竿镶嵌");
            lore.add("&7更换时返还旧吊具");
            if (s.lava()) lore.add("&6解锁地狱岩浆钓鱼");
        }
        lore.add("&7珍稀加成：&a+" + Math.round(s.luck() * 100) + "%");
        lore.add("&7长度分布加成：&b+" + Math.round(s.lengthBonus() * 100) + "%");
        ItemStack item = Text.icon(s.material(), (type.equals("bait") ? "&a" : "&b") + s.name(), lore);
        item.setAmount(amount);
        ItemMeta meta = item.getItemMeta();
        write(meta.getPersistentDataContainer(), type, s.id(), 0, false);
        item.setItemMeta(meta); return item;
    }

    public Settings.Supply readSupply(ItemStack item, String type) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer p = item.getItemMeta().getPersistentDataContainer();
        String value = p.get(id, PersistentDataType.STRING);
        if (value == null || !valid(p, type, value, 0, false)) return null;
        Settings.Supply s = (type.equals("bait") ? settings.get().baits : settings.get().tackles).get(value);
        return s != null && item.getType() == s.material() ? s : null;
    }

    public boolean custom(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        if (item.getItemMeta().getPersistentDataContainer().has(kind, PersistentDataType.STRING)) return true;
        Rod rod = rod(item);
        return rod.level() > 0 || !rod.tackle().isEmpty();
    }

    public Rod rod(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD) return new Rod(null, 0, "");
        PersistentDataContainer p = item.getItemMeta().getPersistentDataContainer();
        String uuid = p.get(rodId, PersistentDataType.STRING), tackle = p.getOrDefault(rodTackle, PersistentDataType.STRING, "");
        int level = p.getOrDefault(rodLevel, PersistentDataType.INTEGER, 0);
        if (uuid == null || level < 0 || level > 3 || !signer.verify("rod|" + uuid + "|" + level + "|" + tackle, p.get(rodSignature, PersistentDataType.STRING))) return new Rod(null, 0, "");
        try { return new Rod(UUID.fromString(uuid), level, tackle); }
        catch (IllegalArgumentException e) { return new Rod(null, 0, ""); }
    }

    public UUID identifyRod(ItemStack rod) {
        Rod data = rod(rod);
        if (data.id() != null) return data.id();
        UUID uuid = UUID.randomUUID(); modifyRod(rod, new Rod(uuid, 0, "")); return uuid;
    }

    public void modifyRod(ItemStack item, Rod data) {
        if (item.getType() != Material.FISHING_ROD) throw new IllegalArgumentException("请在主手持有鱼竿");
        UUID uuid = data.id() == null ? UUID.randomUUID() : data.id();
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer p = meta.getPersistentDataContainer();
        p.set(rodId, PersistentDataType.STRING, uuid.toString()); p.set(rodLevel, PersistentDataType.INTEGER, data.level());
        p.set(rodTackle, PersistentDataType.STRING, data.tackle());
        p.set(rodSignature, PersistentDataType.STRING, signer.sign("rod|" + uuid + "|" + data.level() + "|" + data.tackle()));
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        int old = lore.indexOf(ROD_HEADER);
        if (old >= 0) lore.subList(old, lore.size()).clear();
        lore.add(ROD_HEADER);
        Settings.Supply tackle = settings.get().tackles.get(data.tackle());
        Settings.Upgrade upgrade = settings.get().upgrades.get(data.level());
        lore.add(Text.color("&7改造等级：&e" + data.level() + "/3"));
        lore.add(Text.color("&7吊具：&b" + (tackle == null ? "未镶嵌" : tackle.name())));
        double luck = (tackle == null ? 0 : tackle.luck()) + (upgrade == null ? 0 : upgrade.luck());
        double size = (tackle == null ? 0 : tackle.lengthBonus()) + (upgrade == null ? 0 : upgrade.lengthBonus());
        lore.add(Text.color("&7珍稀加成：&a+" + Math.round(luck * 100) + "% &7长度加成：&b+" + Math.round(size * 100) + "%"));
        if (tackle != null && tackle.lava()) lore.add(Text.color("&6可在地狱岩浆中垂钓"));
        meta.setLore(lore); item.setItemMeta(meta);
    }

    private void write(PersistentDataContainer p, String type, String value, int size, boolean state) {
        p.set(kind, PersistentDataType.STRING, type); p.set(id, PersistentDataType.STRING, value);
        p.set(length, PersistentDataType.INTEGER, size); p.set(cooked, PersistentDataType.INTEGER, state ? 1 : 0);
        p.set(signature, PersistentDataType.STRING, signer.sign(type + "|" + value + "|" + size + "|" + state));
    }
    private boolean valid(PersistentDataContainer p, String type, String value, int size, boolean state) {
        return type.equals(p.get(kind, PersistentDataType.STRING))
                && signer.verify(type + "|" + value + "|" + size + "|" + state, p.get(signature, PersistentDataType.STRING));
    }
    public static String buffName(String effect) {
        return switch (effect) {
            case "SPEED" -> "迅捷"; case "LUCK" -> "幸运"; case "HASTE" -> "急迫";
            case "WATER_BREATHING" -> "水下呼吸"; case "FIRE_RESISTANCE" -> "抗火";
            case "NIGHT_VISION" -> "夜视"; case "REGENERATION" -> "生命恢复"; default -> "饱和";
        };
    }
}
