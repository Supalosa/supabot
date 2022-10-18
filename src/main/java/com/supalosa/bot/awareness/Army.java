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
    float size();
    double threat();
    List<UnitType> composition();
    Set<Tag> unitTags();

    default double calculateThreat(ThreatCalculator threatCalculator) {
        return threatCalculator.calculateThreat(this.composition());
    }

    /**
     * Returns this army plus another army. Note that the position will be unknown.
     */
    default Army plus(Army other) {
        return ImmutableArmy.builder()
                .addAllComposition(other.composition())
                .addAllUnitTags(other.unitTags())
                .size(this.size() + other.size())
                .threat(this.threat() + other.threat())
                .position(Optional.empty())
                .build();
    }

    default Map<UnitType, Integer> compositionAsMap() {
        Map<UnitType, Integer> result = new HashMap<>();
        composition().forEach(unitType -> {
            result.put(unitType, result.getOrDefault(unitType, 0) + 1);
        });
        return result;
    }
}
