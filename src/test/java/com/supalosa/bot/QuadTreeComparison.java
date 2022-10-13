package com.supalosa.bot;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import org.danilopianini.util.FlexibleQuadTree;
import org.danilopianini.util.SpatialIndex;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Comparison of QuadTree to naive lookup
 */
public class QuadTreeComparison {

    public static final int NUM_POINTS = 100000;
    public static final int NUM_SEARCHES = 1000;

    public static void main(String[] args) {
        List<Point2d> points = new ArrayList<>(NUM_POINTS);
        for (int i = 0; i < NUM_POINTS; ++i) {
            points.add(i, Point2d.of(200f, 200f));
        }
        points.clear();
        Random random = new Random(1234);
        long insertionTimeStart = System.currentTimeMillis();
        for (int i = 0; i < NUM_POINTS; ++i) {
            points.add(i, Point2d.of(random.nextFloat() * 200f, random.nextFloat() * 200f));
        }
        long insertionTimeEnd = System.currentTimeMillis();
        System.out.println("Insertion naive = " + (insertionTimeEnd - insertionTimeStart) + "ms");

        insertionTimeStart = System.currentTimeMillis();
        SpatialIndex<Boolean> index = new FlexibleQuadTree<>();
        random.setSeed(1234);
        for (int i = 0; i < NUM_POINTS; ++i) {
            Point2d element = Point2d.of(random.nextFloat() * 200f, random.nextFloat() * 200f);
            index.insert(true, element.getX(), element.getY());
        }
        insertionTimeEnd = System.currentTimeMillis();
        System.out.println("Insertion quadtree = " + (insertionTimeEnd - insertionTimeStart) + "ms");

        List<Point2d> searchPoints = new ArrayList<>();
        for (int i = 0; i < NUM_SEARCHES; ++i) {
            searchPoints.add((Point2d.of(random.nextFloat() * 200f, random.nextFloat() * 200f)));
        }

        long[] results1 = new long[NUM_SEARCHES];
        long[] results2 = new long[NUM_SEARCHES];

        long searchTimeStart, searchTimeEnd;
        searchTimeStart = System.currentTimeMillis();
        for (int i = 0; i < NUM_SEARCHES; ++i) {
            Point2d searchPoint = searchPoints.get(i);
            long neighbours = points.stream().filter(point -> {
                return Math.abs(point.getX() - searchPoint.getX()) <= 10.0 && Math.abs(point.getY() - searchPoint.getY()) <= 10.0;
            }).count();
            results1[i] = neighbours;
        }
        searchTimeEnd = System.currentTimeMillis();
        System.out.println("Search naive = " + (searchTimeEnd - searchTimeStart) + "ms");

        searchTimeStart = System.currentTimeMillis();
        for (int i = 0; i < NUM_SEARCHES; ++i) {
            Point2d searchPoint = searchPoints.get(i);
            long neighbours = index.query(new double[]{searchPoint.getX() - 10.0,
                    searchPoint.getY() - 10.0},
                    new double[]{searchPoint.getX() + 10.0,
                    searchPoint.getY() + 10.0}).size();
            results2[i] = neighbours;
        }
        searchTimeEnd = System.currentTimeMillis();
        System.out.println("Search quadtree = " + (searchTimeEnd - searchTimeStart) + "ms");
        int mismatches = 0;
        for (int i = 0; i < NUM_SEARCHES; ++i) {
            if (results1[i] != results2[i]) {
                ++mismatches;
            }
        }
        System.out.println("Mismatches: " + mismatches + " (" + (100f * (mismatches / (float)NUM_SEARCHES)) + "%)");
    }
}
