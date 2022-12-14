package com.supalosa.bot.utils;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Unit;
import org.immutables.value.Value;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

@Value.Immutable
public abstract class UnitFilter implements Predicate<UnitInPool> {

    public abstract Optional<Alliance> alliance();
    public abstract Optional<UnitType> unitType();
    public abstract Optional<Set<UnitType>> unitTypes();
    public abstract Optional<Point2d> inRangeOf();
    public abstract Optional<Float> range();
    public abstract Optional<Predicate<Unit>> filter();

    @Value.Default
    public boolean includeIncomplete() {
        return false;
    }

    public static ImmutableUnitFilter.Builder builder() {
        return ImmutableUnitFilter.builder();
    }

    public static UnitFilter of(Predicate<Unit> predicate) {
        return ImmutableUnitFilter.builder().filter(predicate).build();
    }

    public static UnitFilter mine(UnitType unitType) {
        return ImmutableUnitFilter.builder().alliance(Alliance.SELF).unitType(unitType).build();
    }

    public static UnitFilter mine(Set<UnitType> unitTypes) {
        return ImmutableUnitFilter.builder().alliance(Alliance.SELF).unitTypes(unitTypes).build();
    }

    @Value.Check
    protected void validate() {
        if (unitType().isPresent() && unitTypes().isPresent()) {
            throw new IllegalArgumentException("Cannot use unitType and unitTypes at the same time.");
        }
        if (inRangeOf().isPresent() ^ range().isPresent()) {
            throw new IllegalArgumentException("Both range and inRangeOf need to be present at the same time.");
        }
    }

    @Override
    public boolean test(UnitInPool unitInPool) {
        if (alliance().isPresent()) {
            if (unitInPool.unit().getAlliance() != alliance().get()) {
                return false;
            }
        }
        if (unitType().isPresent()) {
            if (unitInPool.unit().getType() != unitType().get()) {
                return false;
            }
        } else if (unitTypes().isPresent()) {
            if (!unitTypes().get().contains(unitInPool.unit().getType())) {
                return false;
            }
        }
        if (inRangeOf().isPresent() && range().isPresent()) {
            if (unitInPool.unit().getPosition().toPoint2d().distance(inRangeOf().get()) > range().get()) {
                return false;
            }
        }
        if (filter().isPresent()) {
            if (!filter().get().test(unitInPool.unit())) {
                return false;
            }
        }
        if (!includeIncomplete() && unitInPool.unit().getBuildProgress() < 1.0) {
            return false;
        }
        return true;
    }
}
