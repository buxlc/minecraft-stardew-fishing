package cn.bucheng.stardewfishing;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;

final class SettingsFixture {
    static YamlConfiguration yaml(String resource) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        try (var reader = new InputStreamReader(SettingsFixture.class.getResourceAsStream("/" + resource), StandardCharsets.UTF_8)) { y.load(reader); }
        return y;
    }
    static Settings parse(YamlConfiguration config, YamlConfiguration fish) { return new Settings(config, fish, m -> true); }
    static Settings defaults() throws Exception { return parse(yaml("config.yml"), yaml("fish.yml")); }
}
