package com.supalosa.bot.pathfinding;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.Tile;
import com.supalosa.bot.analysis.utils.Grid;

import java.util.*;
import java.util.function.Function;

public class BreadthFirstSearch {

    public static Optional<Point2d> getFirstPoint(Point2d start, Grid<Tile> grid, Function<Tile, Boolean> predicate) {
        List<Point2d> result = bfsSearch(start, grid, predicate);
        if (result.size() > 0) {
            // return the _last_ entry in the search, which is the first tile matching Predicate.
            return Optional.of(result.get(result.size() - 1));
        } else {
            return Optional.empty();
        }
    }

    public static List<Point2d> bfsSearch(Point2d start, Grid<Tile> grid, Function<Tile, Boolean> predicate) {
        Queue<Point2d> openQueue = new LinkedList<>();
        Set<Point2d> closedSet = new HashSet<>();
        Map<Point2d, Point2d> directionSet = new HashMap<>();
        openQueue.add(start);
        closedSet.add(start);
        // Special handling for starting in an unpathable tile.
        boolean canPathThroughUnpathable = true;

        while (!openQueue.isEmpty()) {
            Point2d head = openQueue.poll();
            int x = (int)head.getX(), y = (int)head.getY();
            Tile t = grid.get(x, y);
            if (predicate.apply(t)) {
                List<Point2d> result = new ArrayList<>();
                Point2d ptr = head;
                while (!ptr.equals(start)) {
                    Point2d previous = directionSet.get(ptr);
                    result.add(ptr);
                    ptr = previous;
                }
                result.add(ptr);
                Collections.reverse(result);
                return result;
            }
            if (t.pathable && canPathThroughUnpathable) {
                canPathThroughUnpathable = false;
            }
            if (!t.pathable && !canPathThroughUnpathable) {
                continue;
            }
            for (int dx = -1; dx <= 1; ++dx) {
                for (int dy = -1; dy <= 1; ++dy) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    if (!grid.isInBounds(x + dx, y + dy)) {
                        continue;
                    }
                    Point2d neighbourPoint = Point2d.of(x + dx, y + dy);
                    if (closedSet.contains(neighbourPoint)) {
                        continue;
                    }
                    directionSet.put(neighbourPoint, head);
                    openQueue.add(neighbourPoint);
                    closedSet.add(neighbourPoint);
                }
            }
        }

        return Collections.emptyList();
    }
}
