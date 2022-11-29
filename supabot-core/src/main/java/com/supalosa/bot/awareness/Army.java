package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.engagement.ThreatCalculator;
import org.immutables.value.Value;

import java.util.*;

/**
 * Representation of an enemy army.
 * TODO: split into two:
 *  - VirtualArmy, where the position is not known and the units are not necessarily real units.
 *  - Army, where the position is known and we have unitTags.
 */
@Value.Immutable
public interface Army {

    /**
     * The position of the army, if known.
     */
    Optional<Point2d> position();

    @Value.Default
    default float size() {
        return 0f;
    }

    @Value.Default
    default double threat() {
        return 0.0;
    }
    Map<UnitType, Integer> composition();
    Set<Tag> unitTags();

    default double calculateThreat(ThreatCalculator threatCalculator) {
        return threatCalculator.calculateThreat(this.composition());
    }

    default int getCount(UnitType type) {
        return composition().getOrDefault(type, 0);
    }

    default int getCount(Collection<UnitType> types) {
        return types.stream().reduce(0,
                (val, type) -> val + composition().getOrDefault(type, 0),
                (v1, v2) -> v1 + v2);
    }

    /**
     * Returns this army plus another army. Note that the position will be unknown.
     */
    default Army plus(Army other) {
        ImmutableArmy.Builder builder = ImmutableArmy.builder()
                .addAllUnitTags(other.unitTags())
                .size(this.size() + other.size())
                .threat(this.threat() + other.threat())
                .position(Optional.empty());
        Map<UnitType, Integer> composition = new HashMap<>(composition());
        other.composition().forEach((unitType, amount) -> {
            composition.put(unitType, composition.getOrDefault(unitType, 0) + amount);
        });
        return builder.putAllComposition(composition).build();
    }

    static Map<UnitType, Integer> createComposition(Iterable<UnitType> compositionList) {
        Map<UnitType, Integer> result = new HashMap<>();
        compositionList.forEach(unitType -> {
            result.put(unitType, result.getOrDefault(unitType, 0) + 1);
        });
        return result;
    }

    static Army empty() {
        return ImmutableArmy.builder().build();
    }

    /**
     * Convert a list of Army to a virtual army.
     */
    static Army toVirtualArmy(List<Army> armyList) {
        return armyList.stream().reduce(Army.empty(), (prev, next) -> prev.plus(next));
    }
}
