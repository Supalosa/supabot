package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.engagement.ThreatCalculator;
import org.immutables.value.Value;

import java.util.*;

/**
 * Virtual representation of an enemy army.
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

    /**
     * Returns this army plus another army. Note that the position will be unknown.
     */
    default Army plus(Army other) {
        ImmutableArmy.Builder builder = ImmutableArmy.builder().from(this)
                .addAllUnitTags(other.unitTags())
                .size(this.size() + other.size())
                .threat(this.threat() + other.threat())
                .position(Optional.empty());
        other.composition().forEach((unitType, amount) -> {
            builder.putComposition(unitType, composition().getOrDefault(unitType, 0) + amount);
        });
        return builder.build();
    }

    static Map<UnitType, Integer> createComposition(Iterable<UnitType> compositionList) {
        Map<UnitType, Integer> result = new HashMap<>();
        compositionList.forEach(unitType -> {
            result.put(unitType, result.getOrDefault(unitType, 0) + 1);
        });
        return result;
    }
}
