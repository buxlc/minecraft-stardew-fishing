package cn.bucheng.stardewfishing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.World;

class SettingsTest {
    @Test void defaultsHaveThirtySpeciesFiveKingsAndAffordableOrdinaryFish() throws Exception {
        Settings s = SettingsFixture.defaults();
        assertEquals(30, s.fish.size());
        assertEquals(5, s.fish.values().stream().filter(f -> f.rarity() == FishSpecies.Rarity.KING).count());
        for (FishSpecies f : s.fish.values()) {
            if (f.rarity() == FishSpecies.Rarity.KING) assertTrue(f.minPrice() >= 1000);
            else { assertTrue(f.minPrice() >= 18); assertTrue(f.maxPrice() <= 90); assertTrue(f.price(f.maxLength(), true, s.cookedMultiplier) <= 135); }
        }
        assertEquals(4, s.recipes.size());
        assertTrue(s.tackles.get("lava").lava());
    }
    @Test void invalidReloadValuesAreRejectedBeforeReplacement() throws Exception {
        var c = SettingsFixture.yaml("config.yml"); var f = SettingsFixture.yaml("fish.yml");
        c.set("fishing.king-chance", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> SettingsFixture.parse(c, f));
        c.set("fishing.king-chance", .0015); c.set("fishing.lava-min-wait-ticks", 6000);
        assertThrows(IllegalArgumentException.class, () -> SettingsFixture.parse(c, f));
    }
    @Test void unknownBiomesAndMissingRegionsFailConfiguration() throws Exception {
        var c = SettingsFixture.yaml("config.yml"); var f = SettingsFixture.yaml("fish.yml");
        f.set("fish.carp.biomes", java.util.List.of("MADE_UP_BIOME"));
        assertThrows(IllegalArgumentException.class, () -> SettingsFixture.parse(c, f));
        f.set("fish.carp.biomes", null); f.set("fish.carp.regions", java.util.List.of("unknown"));
        assertThrows(IllegalArgumentException.class, () -> SettingsFixture.parse(c, f));
    }
    @Test void recipesCannotReferenceUnknownBait() throws Exception {
        var c = SettingsFixture.yaml("config.yml"); var f = SettingsFixture.yaml("fish.yml");
        c.set("recipes.master.ingredients", java.util.List.of("bait:does_not_exist"));
        assertThrows(IllegalArgumentException.class, () -> SettingsFixture.parse(c, f));
    }
    @Test void speciesMatchHookBiomeTimeRainAltitudeAndBait() throws Exception {
        Settings s = SettingsFixture.defaults(); FishSpecies king = s.fish.get("river_king");
        FishingContext good = new FishingContext(FishSpecies.Habitat.WATER, World.Environment.NORMAL, "RIVER", "world", "", 63, 14000, true, false, 3);
        assertTrue(king.matches(good));
        assertFalse(king.matches(new FishingContext(good.habitat(), good.environment(), good.biome(), good.world(), "", 63, 14000, false, false, 3)));
        assertFalse(king.matches(new FishingContext(good.habitat(), good.environment(), good.biome(), good.world(), "", 63, 14000, true, false, 2)));
        assertFalse(s.fish.get("cave_king").matches(good));
        assertFalse(s.fish.get("ash_minnow").matches(good));
        assertTrue(s.fish.get("ash_minnow").matches(new FishingContext(FishSpecies.Habitat.LAVA, World.Environment.NETHER, "NETHER_WASTES", "world_nether", "", 32, 5000, false, false, 0)));
    }
    @Test void priceIsMonotonicAndCookingCannotCompound() throws Exception {
        Settings s = SettingsFixture.defaults(); FishSpecies f = s.fish.get("carp");
        assertEquals(22, f.price(f.minLength(), false, 1.5));
        assertEquals(40, f.price(f.maxLength(), false, 1.5));
        assertEquals(60, f.price(f.maxLength(), true, 1.5));
        assertEquals(22, f.price(-100, false, 1.5));
        for (int length = 160; length < 420; length++) assertTrue(f.price(length / 10.0, false, 1.5) <= f.price((length + 1) / 10.0, false, 1.5));
    }
}
