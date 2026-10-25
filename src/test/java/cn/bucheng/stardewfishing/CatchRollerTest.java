package cn.bucheng.stardewfishing;

import java.util.*;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatchRollerTest {
    private final FishingContext river = new FishingContext(FishSpecies.Habitat.WATER, World.Environment.NORMAL, "RIVER", "world", "", 63, 15000, true, false, 4);
    @Test void longSimulationPreservesRareKingRateAndLengthBounds() throws Exception {
        Settings s = SettingsFixture.defaults(); CatchRoller r = new CatchRoller(s, new Random(300));
        int kings = 0, lengthSum = 0;
        for (int i = 0; i < 50000; i++) {
            var c = r.roll(river, 0, 0, f -> true);
            assertNotNull(c); assertTrue(c.species().matches(river));
            assertTrue(c.lengthTenths() >= c.species().minLength() * 10 && c.lengthTenths() <= c.species().maxLength() * 10);
            if (c.species().rarity() == FishSpecies.Rarity.KING) kings++;
            if (c.species().id().equals("carp")) lengthSum++;
        }
        assertTrue(kings >= 40 && kings <= 110, "absolute 0.15% king probability: " + kings);
        assertTrue(lengthSum > 5000);
    }
    @Test void kingCooldownAndRequiredBaitCannotBeBypassedByLuck() throws Exception {
        Settings s = SettingsFixture.defaults(); CatchRoller r = new CatchRoller(s, new Random(5));
        FishingContext noBait = new FishingContext(river.habitat(), river.environment(), "RIVER", "world", "", 63, 15000, true, false, 0);
        for (int i = 0; i < 5000; i++) {
            assertNotEquals(FishSpecies.Rarity.KING, r.roll(river, 100, 0, f -> false).species().rarity());
            assertNotEquals(FishSpecies.Rarity.KING, r.roll(noBait, 100, 0, f -> true).species().rarity());
        }
    }
    @Test void gearRaisesRarityAndLengthWithoutPassingConfiguredMaximum() throws Exception {
        Settings s = SettingsFixture.defaults(); CatchRoller baseline = new CatchRoller(s, new Random(10)), improved = new CatchRoller(s, new Random(10));
        int rareBase = 0, rareBetter = 0; double lengthBase = 0, lengthBetter = 0;
        for (int i = 0; i < 15000; i++) {
            var a = baseline.roll(river, 0, 0, f -> false); var b = improved.roll(river, 2, .25, f -> false);
            if (a.species().rarity().ordinal() >= 2) rareBase++;
            if (b.species().rarity().ordinal() >= 2) rareBetter++;
            lengthBase += (a.lengthTenths() / 10.0 - a.species().minLength()) / (a.species().maxLength() - a.species().minLength());
            lengthBetter += (b.lengthTenths() / 10.0 - b.species().minLength()) / (b.species().maxLength() - b.species().minLength());
        }
        assertTrue(rareBetter > rareBase * 1.5); assertTrue(lengthBetter > lengthBase * 1.5);
    }
    @Test void highQualityBaitDropsRemainMuchRarer() throws Exception {
        CatchRoller r = new CatchRoller(SettingsFixture.defaults(), new Random(9)); Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 20000; i++) counts.merge(r.baitDrop().id(), 1, Integer::sum);
        assertTrue(counts.get("basic") > counts.get("fine") * 4);
        assertTrue(counts.get("fine") > counts.get("premium") * 4);
        assertTrue(counts.get("premium") > counts.get("master") * 4);
    }
}
