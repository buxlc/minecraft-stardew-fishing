package cn.bucheng.stardewfishing;

import java.util.*;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/** King probability is absolute and cannot grow when a location has fewer common species. */
public final class CatchRoller {
    public record Catch(FishSpecies species, int lengthTenths) { }
    private final Settings settings;
    private final RandomGenerator random;
    public CatchRoller(Settings settings, RandomGenerator random) { this.settings = settings; this.random = random; }

    public Catch roll(FishingContext context, double luck, double lengthBonus, Predicate<FishSpecies> kingReady) {
        double bonus = Math.clamp(luck, 0, settings.maxLuck);
        List<FishSpecies> eligible = settings.fish.values().stream().filter(f -> f.matches(context)).toList();
        List<FishSpecies> kings = eligible.stream().filter(f -> f.rarity() == FishSpecies.Rarity.KING).filter(kingReady).toList();
        FishSpecies chosen;
        if (!kings.isEmpty() && random.nextDouble() < settings.kingChance * (1 + bonus)) chosen = weighted(kings, f -> f.weight());
        else {
            List<FishSpecies> normal = eligible.stream().filter(f -> f.rarity() != FishSpecies.Rarity.KING).toList();
            if (normal.isEmpty()) return null;
            // Average species in each rarity group, so adding common fish does not dilute every rare fish.
            Map<FishSpecies.Rarity, Long> counts = new EnumMap<>(FishSpecies.Rarity.class);
            normal.forEach(f -> counts.merge(f.rarity(), 1L, Long::sum));
            chosen = weighted(normal, f -> f.weight() * switch (f.rarity()) {
                case COMMON -> 70;
                case UNCOMMON -> 23 * (1 + bonus * .35);
                case RARE -> 6 * (1 + bonus);
                case EPIC -> 1 * (1 + bonus * 1.5);
                case KING -> 0;
            } / counts.get(f.rarity()));
        }
        double fraction = Math.clamp(Math.pow(random.nextDouble(), settings.lengthPower) + Math.clamp(lengthBonus, 0, .5), 0, 1);
        int length = (int) Math.round((chosen.minLength() + (chosen.maxLength() - chosen.minLength()) * fraction) * 10);
        length = Math.clamp(length, (int) Math.ceil(chosen.minLength() * 10), (int) Math.floor(chosen.maxLength() * 10));
        return new Catch(chosen, length);
    }

    public Settings.Supply baitDrop() { return weighted(new ArrayList<>(settings.baits.values()), Settings.Supply::catchWeight); }

    private <T> T weighted(List<T> values, java.util.function.ToDoubleFunction<T> weight) {
        double total = values.stream().mapToDouble(weight).sum();
        double point = random.nextDouble() * total;
        for (T value : values) { point -= weight.applyAsDouble(value); if (point < 0) return value; }
        return values.getLast();
    }
}
