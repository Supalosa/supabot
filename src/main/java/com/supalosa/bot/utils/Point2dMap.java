package com.supalosa.bot.utils;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import org.danilopianini.util.FlexibleQuadTree;
import org.danilopianini.util.SpatialIndex;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Point2dMap<T>  {

    private final class ItemWithDistance {
        private T item;
        private double distance;
        private ItemWithDistance(T item, double distance) {
            this.item = item;
            this.distance = distance;
        }
    }

    private final SpatialIndex<T> index;
    private final Function<T, Point2d> extractor;

    public Point2dMap(Function<T, Point2d> extractor) {
        this.index = new FlexibleQuadTree<>();
        this.extractor = extractor;
    }

    public void insert(T item, Point2d point2d) {
        index.insert(item, new double[]{point2d.getX(), point2d.getY()});
    }

    public void insert(T item) {
        Point2d point = extractor.apply(item);
        index.insert(item, new double[]{point.getX(), point.getY()});
    }

    private Stream<ItemWithDistance> internalGetStreamInRadius(Point2d point, double radius) {
        Collection<T> initialResults = index.query(new double[]{point.getX() - radius,
                        point.getY() - radius},
                new double[]{point.getX() + radius,
                        point.getY() + radius});
        return initialResults.stream()
                .map(result -> new ItemWithDistance(result, extractor.apply(result).distance(point)))
                .filter(result -> result.distance <= radius);
    }

    public Collection<T> getInRadius(Point2d point, double radius) {
        return internalGetStreamInRadius(point, radius)
                .map(result -> result.item)
                .collect(Collectors.toUnmodifiableList());
    }

    public Optional<T> getNearestInRadius(Point2d point, double radius) {
        return internalGetStreamInRadius(point, radius)
                .min(Comparator.comparing(result -> result.distance))
                .map(result -> result.item);
    }
}