package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import org.apache.commons.lang3.tuple.Pair;
import org.immutables.value.Value;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Represents the static data of a region (calculated at analysis time).
 */
@Value.Immutable
public interface Region extends TileSet {
    int regionId();
    List<Integer> connectedRegions();
    List<Integer> onLowGroundOfRegions();
    List<Integer> onHighGroundOfRegions();
    Optional<Integer> getRampId();
    Point2d centrePoint();
    /**
     * Returns a pair of points which represent the bottom left and top right positions
     * respectively.
     */
    Pair<Point2d, Point2d> regionBounds();
}
