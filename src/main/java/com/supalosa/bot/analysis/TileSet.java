package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TileSet {
    Set<Point2d> getTiles();
    Optional<Set<Point2d>> getBorderTiles();

    static Optional<Pair<Point2d, Point2d>> calculateBounds(List<Point2d> tiles) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
        if (tiles.isEmpty()) {
            return Optional.empty();
        }
        for (Point2d tile : tiles) {
            minX = Math.min(minX, tile.getX());
            minY = Math.min(minY, tile.getY());
            maxX = Math.max(maxX, tile.getX());
            maxY = Math.max(maxX, tile.getY());
        }
        return Optional.of(Pair.of(Point2d.of(minX, minY), Point2d.of(maxX, maxY)));
    }
}
