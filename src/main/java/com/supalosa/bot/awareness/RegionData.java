package com.supalosa.bot.awareness;

import com.supalosa.bot.analysis.Region;
import org.immutables.value.Value;

/**
 * Represents the dynamic data of a region (calculated periodically).
 */
@Value.Immutable
public interface RegionData {

    Region region();

    /**
     * A value from 0.0 to infinity affecting the cost of entering this region.
     * 1.0 is the default.
     */
    @Value.Default
    default double weight() {
        return 1.0;
    }

    /**
     * A value from 0.0 to infinity indicating how much of a killzone this is.
     * A killzone has siege tanks in it or in range of it, or is at the bottom of the ramp and the enemy
     * is on the highground.
     */
    @Value.Default
    default double killzoneFactor() {
        return 1.0;
    }

    /**
     * A value from 0.0 to infinity indicating how much of the enemy army is here.
     * A value of 1.0 is default.
     */
    @Value.Default
    default double enemyArmyFactor() {
        return 1.0;
    }

    /**
     * A scalar figure indicating the enemy threat of this region. It decays slowly over time
     * (or faster if we have visibility on the region)
     */
    @Value.Default
    default double enemyThreat() {
        return 0.0;
    }

    /**
     * A scalar figure indicating how much enemy threat is _near_ this region. Named as such because
     * the threat value diffuses through the graph.
     */
    @Value.Default
    default double diffuseEnemyThreat() { return 0.0; }

    /**
     * A scalar figure indicating the player's threat in this region.
     */
    @Value.Default
    default double playerThreat() {
        return 0.0;
    }

    @Value.Default
    default boolean isBlocked() {
        return false;
    }

    /**
     * Approximate (sampled) visibility percentage for this region.
     * This is a snapshot of the current visibility.
     */
    @Value.Default
    default double visibilityPercent() {
        return 0.0;
    }

    /**
     * Approximate (sampled) visibility percentage for this region.
     * This is value decays slowly so as not to drop to zero as soon as we leave a region.
     */
    @Value.Default
    default double decayingVisibilityPercent() {
        return 0.0;
    }

    @Value.Default
    default boolean hasEnemyBase() {
        return false;
    }
}
