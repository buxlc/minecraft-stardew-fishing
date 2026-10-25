package cn.bucheng.stardewfishing;

import java.util.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Validates complete configuration before a reload replaces the running settings. */
public final class Settings {
    public record Supply(String id, String name, Material material, double luck, double lengthBonus,
                         double price, boolean lava, double catchWeight, int tier) { }
    public record Upgrade(double price, double luck, double lengthBonus) { }
    public record BaitRecipe(String id, int amount, List<String> ingredients) { }
    public record Region(String id, String name, String world, List<Integer> min, List<Integer> max, int priority) {
        boolean contains(Location l) {
            return l.getWorld().getName().equals(world) && l.getX() >= min.get(0) && l.getX() <= max.get(0)
                    && l.getY() >= min.get(1) && l.getY() <= max.get(1) && l.getZ() >= min.get(2) && l.getZ() <= max.get(2);
        }
    }
    public final boolean defaultEnabled;
    public final Set<String> disabledWorlds;
    public final int lavaMinWait, lavaMaxWait, lavaBiteWindow, rawBuffSeconds, cookedBuffSeconds, kingBuffSeconds, buffCooldown;
    public final double kingChance, kingCooldownHours, maxLuck, lengthPower, baitChance, cookedMultiplier;
    public final Map<String, FishSpecies> fish;
    public final Map<String, Supply> baits, tackles;
    public final Map<Integer, Upgrade> upgrades;
    public final List<BaitRecipe> recipes;
    public final List<Region> regions;
    private final java.util.function.Predicate<Material> itemMaterial;

    public Settings(FileConfiguration config, FileConfiguration species) {
        this(config, species, Material::isItem);
    }

    Settings(FileConfiguration config, FileConfiguration species, java.util.function.Predicate<Material> itemMaterial) {
        this.itemMaterial = itemMaterial;
        defaultEnabled = config.getBoolean("fishing.enabled-by-default", true);
        disabledWorlds = Set.copyOf(config.getStringList("fishing.disabled-worlds"));
        lavaMinWait = integer(config, "fishing.lava-min-wait-ticks", 240, 20, 72000);
        lavaMaxWait = integer(config, "fishing.lava-max-wait-ticks", 600, lavaMinWait, 72000);
        lavaBiteWindow = integer(config, "fishing.lava-bite-window-ticks", 70, 10, 400);
        kingChance = number(config, "fishing.king-chance", .0015, 0, .05);
        kingCooldownHours = number(config, "fishing.king-cooldown-hours", 24, 0, 8760);
        maxLuck = number(config, "fishing.maximum-luck-bonus", 2, 0, 10);
        lengthPower = number(config, "fishing.length-distribution-power", 2, 1, 10);
        baitChance = number(config, "fishing.bait-catch-chance", .06, 0, .5);
        cookedMultiplier = number(config, "economy.cooked-multiplier", 1.5, 1, 10);
        rawBuffSeconds = integer(config, "food.raw-buff-seconds", 8, 1, 3600);
        cookedBuffSeconds = integer(config, "food.cooked-buff-seconds", 25, 1, 3600);
        kingBuffSeconds = integer(config, "food.king-buff-seconds", 45, 1, 3600);
        buffCooldown = integer(config, "food.buff-cooldown-seconds", 30, 1, 3600);
        baits = supplies(config, "baits", true);
        tackles = supplies(config, "tackles", false);
        LinkedHashMap<Integer, Upgrade> upgradeMap = new LinkedHashMap<>();
        for (int i = 1; i <= 3; i++) {
            ConfigurationSection s = section(config, "upgrades." + i);
            upgradeMap.put(i, new Upgrade(number(s, "price", 0, 0, 1000000), number(s, "luck", 0, 0, 10), number(s, "length-bonus", 0, 0, .5)));
        }
        upgrades = Collections.unmodifiableMap(upgradeMap);
        List<Region> regionList = new ArrayList<>();
        ConfigurationSection rs = section(config, "regions");
        for (String id : rs.getKeys(false)) {
            validateId(id);
            ConfigurationSection r = section(rs, id);
            List<Integer> min = r.getIntegerList("min"), max = r.getIntegerList("max");
            if (min.size() != 3 || max.size() != 3) throw invalid("渔区 " + id + " 的 min/max 必须各有三个坐标");
            for (int i = 0; i < 3; i++) if (min.get(i) > max.get(i)) throw invalid("渔区 " + id + " 最小坐标不能大于最大坐标");
            regionList.add(new Region(id, r.getString("name", id), required(r, "world"), List.copyOf(min), List.copyOf(max), r.getInt("priority")));
        }
        regionList.sort(Comparator.comparingInt(Region::priority).reversed());
        regions = List.copyOf(regionList);
        List<BaitRecipe> recipeList = new ArrayList<>();
        ConfigurationSection rc = section(config, "recipes");
        for (String id : rc.getKeys(false)) {
            if (!baits.containsKey(id)) throw invalid("配方鱼饵不存在: " + id);
            ConfigurationSection r = section(rc, id);
            List<String> ingredients = r.getStringList("ingredients");
            if (ingredients.isEmpty() || ingredients.size() > 9) throw invalid("鱼饵配方必须有1~9格材料: " + id);
            for (String input : ingredients) {
                if (input.startsWith("bait:")) {
                    if (!baits.containsKey(input.substring(5))) throw invalid("配方引用了不存在的鱼饵: " + input);
                } else material(input);
            }
            recipeList.add(new BaitRecipe(id, integer(r, "amount", 1, 1, 64), List.copyOf(ingredients)));
        }
        recipes = List.copyOf(recipeList);
        LinkedHashMap<String, FishSpecies> fishMap = new LinkedHashMap<>();
        ConfigurationSection fs = section(species, "fish");
        for (String id : fs.getKeys(false)) {
            validateId(id);
            ConfigurationSection f = section(fs, id);
            Set<World.Environment> envs = new LinkedHashSet<>();
            for (String env : f.getStringList("environments")) envs.add(World.Environment.valueOf(env));
            if (envs.isEmpty()) throw invalid("鱼种 " + id + " 缺少 environments");
            Set<String> biomes = Set.copyOf(f.getStringList("biomes"));
            for (String biome : biomes) {
                if (Arrays.stream(Biome.class.getFields()).noneMatch(field -> field.getType() == Biome.class && field.getName().equals(biome)))
                    throw invalid("未知群系: " + biome);
            }
            Set<String> areaIds = Set.copyOf(f.getStringList("regions"));
            for (String area : areaIds) if (regions.stream().noneMatch(r -> r.id().equals(area))) throw invalid("鱼种引用未知渔区: " + area);
            int minY = integer(f, "min-y", -64, -2048, 2048);
            double minLength = number(f, "min-length", 10, .1, 10000);
            double maxLength = number(f, "max-length", 50, minLength + .1, 10000);
            double minPrice = number(f, "min-price", 20, 0, 1000000);
            String weather = f.getString("weather", "ANY").toUpperCase(Locale.ROOT);
            if (!Set.of("ANY", "CLEAR", "RAIN", "THUNDER").contains(weather)) throw invalid("未知天气: " + weather);
            String buff = f.getString("buff", "SATURATION");
            if (!Set.of("SATURATION", "SPEED", "LUCK", "WATER_BREATHING", "FIRE_RESISTANCE", "NIGHT_VISION", "HASTE", "REGENERATION").contains(buff)) throw invalid("未知增益: " + buff);
            Material fishMaterial = material(f.getString("material", "COD"));
            if (fishMaterial != Material.COD && fishMaterial != Material.SALMON) throw invalid("鱼类外观仅支持 COD 或 SALMON，确保烤制与食用一致");
            fishMap.put(id, new FishSpecies(id, required(f, "name"), fishMaterial, FishSpecies.Rarity.valueOf(required(f, "rarity")),
                    FishSpecies.Habitat.valueOf(f.getString("habitat", "WATER")), Set.copyOf(envs), biomes,
                    Set.copyOf(f.getStringList("worlds")), areaIds, minY, integer(f, "max-y", 320, minY, 2048),
                    integer(f, "start-time", 0, 0, 23999), integer(f, "end-time", 23999, 0, 23999), weather,
                    integer(f, "min-bait", 0, 0, baits.size()), minLength, maxLength, minPrice,
                    number(f, "max-price", 50, minPrice, 1000000), number(f, "weight", 1, .0001, 100000), buff,
                    required(f, "location")));
        }
        if (fishMap.isEmpty()) throw invalid("鱼种表不能为空");
        fish = Collections.unmodifiableMap(fishMap);
    }

    public FishingContext context(Location spot, boolean lava, int baitTier) {
        World world = Objects.requireNonNull(spot.getWorld());
        String region = regions.stream().filter(r -> r.contains(spot)).map(Region::id).findFirst().orElse("");
        return new FishingContext(lava ? FishSpecies.Habitat.LAVA : FishSpecies.Habitat.WATER, world.getEnvironment(),
                spot.getBlock().getBiome().name(), world.getName(), region, spot.getBlockY(), world.getTime(), world.hasStorm(), world.isThundering(), baitTier);
    }

    private Map<String, Supply> supplies(FileConfiguration c, String path, boolean bait) {
        LinkedHashMap<String, Supply> result = new LinkedHashMap<>();
        ConfigurationSection s = section(c, path);
        int tier = 0;
        for (String id : s.getKeys(false)) {
            validateId(id);
            ConfigurationSection v = section(s, id);
            result.put(id, new Supply(id, required(v, "name"), material(required(v, "material")), number(v, "luck", 0, 0, 10),
                    number(v, "length-bonus", 0, 0, .5), bait ? 0 : number(v, "price", 0, 0, 1000000),
                    !bait && v.getBoolean("lava", false), bait ? number(v, "catch-weight", 1, .0001, 100000) : 0, bait ? ++tier : 0));
        }
        if (result.isEmpty()) throw invalid(path + " 不能为空");
        return Collections.unmodifiableMap(result);
    }

    static double number(ConfigurationSection c, String path, double fallback, double min, double max) {
        Object raw = c.get(path);
        if (raw != null && !(raw instanceof Number)) throw invalid(c.getCurrentPath() + "." + path + " 必须是数字");
        double value = raw == null ? fallback : ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value < min || value > max) throw invalid(path + " 必须在 " + min + "~" + max);
        return value;
    }
    static int integer(ConfigurationSection c, String path, int fallback, int min, int max) {
        double value = number(c, path, fallback, min, max);
        if (value != Math.rint(value)) throw invalid(path + " 必须为整数");
        return (int) value;
    }
    static ConfigurationSection section(ConfigurationSection c, String path) {
        ConfigurationSection s = c.getConfigurationSection(path);
        if (s == null) throw invalid("缺少配置段: " + path);
        return s;
    }
    private static String required(ConfigurationSection c, String path) {
        String v = c.getString(path);
        if (v == null || v.isBlank()) throw invalid("缺少字段: " + c.getCurrentPath() + "." + path);
        return v;
    }
    private static void validateId(String id) {
        if (!id.matches("[a-z0-9_]+")) throw invalid("ID仅支持小写英文、数字与下划线: " + id);
    }
    private Material material(String name) {
        Material m = Material.matchMaterial(name);
        if (m == null || !itemMaterial.test(m) || Text.air(m)) throw invalid("无效物品: " + name);
        return m;
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
