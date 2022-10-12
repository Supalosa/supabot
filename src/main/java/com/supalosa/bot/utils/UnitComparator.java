package com.supalosa.bot.utils;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import org.immutables.value.Value;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

@Value.Immutable
public abstract class UnitComparator implements Comparator<UnitInPool> {

    public abstract Optional<Point2d> distanceToPoint();
    public abstract Optional<UnitInPool> distanceToUnitInPool();
    public abstract Optional<Unit> distanceToUnit();

    public abstract boolean ascending();

    public static ImmutableUnitComparator.Builder builder() {
        return ImmutableUnitComparator.builder();
    }

    @Override
    public int compare(UnitInPool o1, UnitInPool o2) {
        if (!(distanceToPoint().isPresent() ^ distanceToUnitInPool().isPresent() ^ distanceToUnit().isPresent())) {
            throw new IllegalArgumentException("Only one distance target (Point2d, UnitInPool, Unit) can be set.");
        }
        int compare = distanceToPoint()
                .map(compareDistanceToPoint(o1, o2))
                .or(() -> distanceToUnitInPool().map(compareDistanceToUnitInPool(o1, o2)))
                .or(() -> distanceToUnit().map(compareDistanceToUnit(o1, o2)))
                .get();
        if (ascending() == false) {
            compare = -compare;
        }
        return compare;
    }

    private Function<Point2d, Integer> compareDistanceToPoint(UnitInPool o1, UnitInPool o2) {
        return point -> Double.compare(o1.unit().getPosition().toPoint2d().distance(point), o2.unit().getPosition().toPoint2d().distance(point));
    }

    private Function<UnitInPool, Integer> compareDistanceToUnitInPool(UnitInPool o1, UnitInPool o2) {
        return uip -> Double.compare(o1.unit().getPosition().toPoint2d().distance(uip.unit().getPosition().toPoint2d()), o2.unit().getPosition().toPoint2d().distance(uip.unit().getPosition().toPoint2d()));
    }

    private Function<Unit, Integer> compareDistanceToUnit(UnitInPool o1, UnitInPool o2) {
        return u -> Double.compare(o1.unit().getPosition().toPoint2d().distance(u.getPosition().toPoint2d()), o2.unit().getPosition().toPoint2d().distance(u.getPosition().toPoint2d()));
    }
}
