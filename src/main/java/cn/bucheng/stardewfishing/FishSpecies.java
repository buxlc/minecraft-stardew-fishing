package cn.bucheng.stardewfishing;

import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;

/** A configured species; prices interpolate between its length endpoints. */
public record FishSpecies(String id, String name, Material material, Rarity rarity,
        Habitat habitat, Set<World.Environment> environments, Set<String> biomes,
        Set<String> worlds, Set<String> regions, int minY, int maxY, int startTime, int endTime,
        String weather, int minBait, double minLength, double maxLength,
        double minPrice, double maxPrice, double weight, String buff, String locationHint) {
    public enum Habitat { WATER, LAVA }
    public enum Rarity {
        COMMON("普通", "&f"), UNCOMMON("优良", "&a"), RARE("稀有", "&9"), EPIC("珍奇", "&d"), KING("鱼王", "&6");
        public final String title;
        public final String color;
        Rarity(String title, String color) { this.title = title; this.color = color; }
    }

    public boolean matches(FishingContext c) {
        return habitat == c.habitat() && environments.contains(c.environment())
                && (biomes.isEmpty() || biomes.contains(c.biome()))
                && (worlds.isEmpty() || worlds.contains(c.world()))
                && (regions.isEmpty() || regions.contains(c.region()))
                && c.y() >= minY && c.y() <= maxY && c.baitTier() >= minBait
                && timeMatches(c.time()) && switch (weather) {
                    case "RAIN" -> c.raining();
                    case "THUNDER" -> c.thundering();
                    case "CLEAR" -> !c.raining();
                    default -> true;
                };
    }

    public boolean timeMatches(long time) {
        long normalized = Math.floorMod(time, 24000);
        return startTime <= endTime ? normalized >= startTime && normalized <= endTime
                : normalized >= startTime || normalized <= endTime;
    }

    public double price(double length, boolean cooked, double cookedMultiplier) {
        double fraction = Math.clamp((length - minLength) / (maxLength - minLength), 0, 1);
        return Math.round((minPrice + (maxPrice - minPrice) * fraction) * (cooked ? cookedMultiplier : 1) * 100) / 100.0;
    }
}
