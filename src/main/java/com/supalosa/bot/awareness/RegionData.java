package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.protocol.data.Upgrade;
import com.github.ocraft.s2client.protocol.data.Upgrades;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.Region;
import org.apache.commons.lang3.NotImplementedException;
import org.immutables.value.Value;

import java.util.*;
import java.util.stream.Collectors;

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
     * Returns the most defendable tile in this region.
     */
    public abstract Optional<Point2d> getDefenceRallyPoint();

    /**
     * An unbounded value of control for the region that accumulates based on enemy and your own forces.
     * Used as an input for {@code controlFactor}. This value can grow to a very large (or negative) number, so may not
     * be useful.
     */
    public abstract double cumulativeControl();

    /**
     * A value that represents how well controlled the region is. Although technically infinite, the value will
     * be bounded to around -10 to 10 in practise.
     */
    public abstract double controlFactor();

    /**
     * Returns true if this region is controlled by the player (as determined by {@link RegionData#controlFactor()}).
     */
    public boolean isPlayerControlled() {
        return controlFactor() > 1.0;
    }
    /**
     * Returns true if this region is controlled by the enemy (as determined by {@link RegionData#controlFactor()}).
     */
    public boolean isEnemyControlled() {
        return controlFactor() < -1.0;
    }

    /**
     * Returns the RegionData of all regions that neighbour this one.
     *
     * @param mapAwareness Map awareness to query.
     */
    public List<RegionData> getNeighbouringRegions(MapAwareness mapAwareness) {
        List<RegionData> result = region().connectedRegions()
                .stream()
                .map(mapAwareness::getRegionDataForId)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
        return result;
    }

    @Override
    public final String toString() {
        StringBuilder builder = new StringBuilder("[Region " + region().regionId() + "]");
        if (isPlayerControlled()) {
            builder.append(" PlayerControlled");
        } else if (isEnemyControlled()) {
            builder.append(" EnemyControlled");
        } else {
            builder.append(" Neutral");
        }
        if (hasEnemyBase()) {
            builder.append(" EnemyBase");
        }
        if (isPlayerBase()) {
            builder.append(" PlayerBase");
        }
        return builder.toString();
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(this.region().regionId());
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof RegionData) {
            return this.region().regionId() == ((RegionData)object).region().regionId();
        } else {
            return false;
        }
    }
}
