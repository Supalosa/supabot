package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.Region;
import org.immutables.value.Value;

import java.util.*;

/**
 * Represents the dynamic data of a region (calculated periodically).
 */
@Value.Immutable
public abstract class RegionData {

    public abstract Region region();

    /**
     * A value from 0.0 to infinity affecting the cost of entering this region.
     * 1.0 is the default.
     */
    @Value.Default
    public double weight() {
        return 1.0;
    }

    /**
     * A value from 0.0 to infinity indicating how much of a killzone this is.
     * A killzone has siege tanks in it or in range of it, or is at the bottom of the ramp and the enemy
     * is on the highground.
     */
    @Value.Default
    public double killzoneFactor() {
        return 1.0;
    }

    /**
     * A value from 0.0 to infinity indicating how much of the enemy army is here.
     * A value of 1.0 is default.
     */
    @Value.Default
    public double enemyArmyFactor() {
        return 1.0;
    }

    /**
     * A scalar figure indicating the enemy threat of this region. It decays slowly over time
     * (or faster if we have visibility on the region)
     */
    @Value.Default
    public double enemyThreat() {
        return 0.0;
    }

    /**
     * A scalar figure indicating the enemy threat of this region and all neighbouring regions.
     */
    @Value.Default
    public double nearbyEnemyThreat() {
        return 0.0;
    }

    /**
     * A scalar figure indicating how much enemy threat is _near_ this region. Named as such because
     * the threat value diffuses through the graph.
     */
    @Value.Default
    public double diffuseEnemyThreat() { return 0.0; }

    /**
     * A scalar figure indicating the player's threat in this region.
     */
    @Value.Default
    public double playerThreat() {
        return 0.0;
    }

    @Value.Default
    public boolean isBlocked() {
        return false;
    }

    /**
     * Approximate (sampled) visibility percentage for this region.
     * This is a snapshot of the current visibility.
     */
    @Value.Default
    public double visibilityPercent() {
        return 0.0;
    }

    /**
     * Approximate (sampled) visibility percentage for this region.
     * This is value decays slowly so as not to drop to zero as soon as we leave a region.
     */
    @Value.Default
    public double decayingVisibilityPercent() {
        return 0.0;
    }

    @Value.Default
    public boolean hasEnemyBase() {
        return false;
    }

    @Value.Default
    public boolean isPlayerBase() { return false; }

    @Value.Default
    public double estimatedCreepPercentage() { return 0.0; }

    /**
     * Returns the borders of this region that face towards the enemy.
     */
    @Value.Default
    public Set<Point2d> borderTilesTowardsEnemy() {
        return new HashSet<>();
    }

    /**
     * Returns the best tile that faces towards the enemy.
     */
    public abstract Optional<Point2d> bestTileTowardsEnemy();

    @Override
    public boolean equals(Object object) {
        if (object instanceof RegionData) {
            return this.region().regionId() == ((RegionData)object).region().regionId();
        } else {
            return false;
        }
    }
}
