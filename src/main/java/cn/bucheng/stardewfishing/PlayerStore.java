package cn.bucheng.stardewfishing;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Per-player discovery records, persisted before catches are awarded. Corrupt files are never overwritten. */
public final class PlayerStore {
    public record Record(long count, int longestTenths, long firstCaught, long lastKing) { }
    public record Profile(boolean enabled, Map<String, Record> records) { }
    private final Path directory;
    private final Map<UUID, Profile> cache = new HashMap<>();
    public PlayerStore(Path directory) { this.directory = directory; }

    public Profile get(UUID id, boolean defaultEnabled) {
        return cache.computeIfAbsent(id, key -> load(key, defaultEnabled));
    }

    public void toggle(UUID id, boolean defaultEnabled) {
        Profile old = get(id, defaultEnabled);
        commit(id, new Profile(!old.enabled(), old.records()));
    }

    public boolean caught(UUID id, boolean defaultEnabled, FishSpecies fish, int length, long now) {
        Profile profile = get(id, defaultEnabled);
        Record old = profile.records().get(fish.id());
        Map<String, Record> updated = new LinkedHashMap<>(profile.records());
        updated.put(fish.id(), new Record(old == null ? 1 : Math.addExact(old.count(), 1),
                Math.max(length, old == null ? 0 : old.longestTenths()), old == null ? now : old.firstCaught(),
                fish.rarity() == FishSpecies.Rarity.KING ? now : old == null ? 0 : old.lastKing()));
        commit(id, new Profile(profile.enabled(), Collections.unmodifiableMap(updated)));
        return old == null;
    }

    public boolean kingReady(UUID id, boolean defaultEnabled, FishSpecies fish, long now, double hours) {
        Record r = get(id, defaultEnabled).records().get(fish.id());
        return r == null || now - r.lastKing() >= hours * 3600000;
    }
    public void forget(UUID id) { cache.remove(id); }

    private Profile load(UUID id, boolean defaultEnabled) {
        Path path = directory.resolve(id + ".yml");
        if (!Files.exists(path)) return new Profile(defaultEnabled, Map.of());
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.load(path.toFile());
            if (!y.isBoolean("enabled")) throw new IllegalArgumentException("缺少开关状态");
            Map<String, Record> records = new LinkedHashMap<>();
            ConfigurationSection section = y.getConfigurationSection("fish");
            if (section == null) throw new IllegalArgumentException("缺少图鉴数据");
            for (String key : section.getKeys(false)) {
                ConfigurationSection r = Objects.requireNonNull(section.getConfigurationSection(key));
                long count = r.getLong("count", -1), first = r.getLong("first", -1), lastKing = r.getLong("last-king", -1);
                int longest = r.getInt("longest", -1);
                if (count < 1 || longest < 1 || first < 0 || lastKing < 0) throw new IllegalArgumentException("无效图鉴条目: " + key);
                records.put(key, new Record(count, longest, first, lastKing));
            }
            return new Profile(y.getBoolean("enabled"), Collections.unmodifiableMap(records));
        } catch (Exception e) { throw new IllegalStateException("玩家图鉴文件损坏，已保留原文件: " + path, e); }
    }

    private void commit(UUID id, Profile profile) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", profile.enabled());
        yaml.createSection("fish");
        profile.records().forEach((key, r) -> {
            String p = "fish." + key;
            yaml.set(p + ".count", r.count()); yaml.set(p + ".longest", r.longestTenths());
            yaml.set(p + ".first", r.firstCaught()); yaml.set(p + ".last-king", r.lastKing());
        });
        try { AtomicFiles.write(directory.resolve(id + ".yml"), yaml.saveToString()); }
        catch (IOException e) { throw new IllegalStateException("图鉴无法保存，本次操作已中止", e); }
        cache.put(id, profile);
    }
}
