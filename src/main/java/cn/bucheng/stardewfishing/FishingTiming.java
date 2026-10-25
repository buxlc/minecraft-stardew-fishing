package cn.bucheng.stardewfishing;

/** Tick-based waiting and bite window for a lava bobber; advancing never grants a catch. */
final class FishingTiming {
    enum Transition { NONE, BITE, MISSED }
    private final int waitTicks;
    private final int windowTicks;
    private int elapsed;
    private boolean biting;

    FishingTiming(int waitTicks, int windowTicks) {
        if (waitTicks <= 0 || windowTicks <= 0) throw new IllegalArgumentException("Fishing timings must be positive");
        this.waitTicks = waitTicks;
        this.windowTicks = windowTicks;
    }

    Transition tick() {
        elapsed++;
        if (!biting && elapsed >= waitTicks) {
            biting = true;
            elapsed = 0;
            return Transition.BITE;
        }
        if (biting && elapsed >= windowTicks) {
            biting = false;
            elapsed = 0;
            return Transition.MISSED;
        }
        return Transition.NONE;
    }

    boolean canCatch() { return biting; }

    void rejectBite() {
        biting = false;
        elapsed = 0;
    }
}
