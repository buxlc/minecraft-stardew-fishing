package cn.bucheng.stardewfishing;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FishingTimingTest {
    @Test void catchingRequiresWaitingUntilTheBiteAndStopsAtTheEndOfItsWindow() {
        FishingTiming timing = new FishingTiming(4, 3);
        for (int tick = 1; tick < 4; tick++) {
            assertEquals(FishingTiming.Transition.NONE, timing.tick());
            assertFalse(timing.canCatch());
        }
        assertEquals(FishingTiming.Transition.BITE, timing.tick());
        assertTrue(timing.canCatch());
        assertEquals(FishingTiming.Transition.NONE, timing.tick());
        assertEquals(FishingTiming.Transition.NONE, timing.tick());
        assertEquals(FishingTiming.Transition.MISSED, timing.tick());
        assertFalse(timing.canCatch());
        for (int tick = 1; tick < 4; tick++) assertEquals(FishingTiming.Transition.NONE, timing.tick());
        assertEquals(FishingTiming.Transition.BITE, timing.tick());
    }

    @Test void aProtectionPluginRejectingTheBiteRequiresAnotherFullWait() {
        FishingTiming timing = new FishingTiming(3, 2);
        timing.tick(); timing.tick(); timing.tick();
        assertTrue(timing.canCatch());
        timing.rejectBite();
        assertFalse(timing.canCatch());
        assertEquals(FishingTiming.Transition.NONE, timing.tick());
        assertEquals(FishingTiming.Transition.NONE, timing.tick());
        assertEquals(FishingTiming.Transition.BITE, timing.tick());
    }

    @Test void invalidTimingsFailInsteadOfAllowingInstantRewards() {
        assertThrows(IllegalArgumentException.class, () -> new FishingTiming(0, 20));
        assertThrows(IllegalArgumentException.class, () -> new FishingTiming(20, 0));
    }
}
