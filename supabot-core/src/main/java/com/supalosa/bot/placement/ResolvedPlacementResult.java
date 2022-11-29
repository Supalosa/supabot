package com.supalosa.bot.placement;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.google.common.base.Preconditions;
import org.immutables.value.Value;

import java.util.Optional;

/**
 * The result of a placement rule result.
 */
@Value.Immutable
public interface ResolvedPlacementResult {

    Optional<Point2d> point2d();

    Optional<Unit> unit();

    /**
     * Returns the position that this result corresponds to, even if it's pointing to a unit.
     */
    default Point2d asPoint2d() {
        return point2d().or(() -> unit().map(unit -> unit.getPosition().toPoint2d())).get();
    }

    @Value.Check
    default void check() {
        Preconditions.checkState(point2d().isPresent() ^ unit().isPresent(),
                "Only one of point2d or unit should be provided.");
    }

    static ResolvedPlacementResult point2d(Point2d point2d) {
        return ImmutableResolvedPlacementResult.builder().point2d(point2d).build();
    }

    static ResolvedPlacementResult unit(Unit unit) {
        return ImmutableResolvedPlacementResult.builder().unit(unit).build();
    }
}
