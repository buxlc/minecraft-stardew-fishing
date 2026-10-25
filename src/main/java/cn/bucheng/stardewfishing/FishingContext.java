package cn.bucheng.stardewfishing;

import org.bukkit.World;

/** Conditions at the hook, rather than at the player's standing position. */
public record FishingContext(FishSpecies.Habitat habitat, World.Environment environment,
        String biome, String world, String region, int y, long time,
        boolean raining, boolean thundering, int baitTier) { }
