package com.supalosa.bot.utils;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.Constants;

import java.util.*;

public class Utils {

    public static Optional<Unit> findNearestMineralPatch(ObservationInterface observationInterface, Point2d start) {
        List<UnitInPool> units = observationInterface.getUnits(Alliance.NEUTRAL);
        double distance = Double.MAX_VALUE;
        Unit target = null;
        for (UnitInPool unitInPool : units) {
            Unit unit = unitInPool.unit();
            if (Constants.MINERAL_TYPES.contains(unit.getType())) {
                double d = unit.getPosition().toPoint2d().distance(start);
                if (d < distance) {
                    distance = d;
                    target = unit;
                }
            }
        }
        return Optional.ofNullable(target);
    }

    public static Map<Point2d, List<Point2d>> clusterPoints(List<Point2d> points, double distanceApart) {
        Map<Point2d, List<Point2d>> clusters = new LinkedHashMap<>();
        for (Point2d point : points) {
            double distance = Double.MAX_VALUE;
            Map.Entry<Point2d, List<Point2d>> targetCluster = null;
            // Find the cluster this mineral patch is closest to.
            for (Map.Entry<Point2d, List<Point2d>> cluster : clusters.entrySet()) {
                double d = point.distance(cluster.getKey());
                if (d < distance) {
                    distance = d;
                    targetCluster = cluster;
                }
            }

            // If the target cluster is some distance away don't use it.
            if (targetCluster == null || distance > distanceApart) {
                ArrayList<Point2d> pointsInCluster = new ArrayList<>();
                pointsInCluster.add(point);
                clusters.put(point, pointsInCluster);
                continue;
            }

            // Otherwise append to that cluster and update it's center of mass.
            if (targetCluster.getValue() == null) {
                targetCluster.setValue(new ArrayList<>());
            }
            targetCluster.getValue().add(point);

            int size = targetCluster.getValue().size();
            Point2d centerOfMass = targetCluster.getKey().mul(size - 1.0f).add(point).div(size);
            clusters.put(centerOfMass, clusters.remove(targetCluster.getKey()));
        }

        return clusters;
    }

    /**
     * Returns the position a certain distance away from the enemy.
     * @param myPosition The position of the current unit.
     * @param enemyPosition The position of the enemy unit.
     * @param distance The distance to path back to.
     * @return
     */
    public static Point2d getRetreatPosition(Point2d myPosition, Point2d enemyPosition, float distance) {
        float dx = enemyPosition.getX() - myPosition.getX();
        float dy = enemyPosition.getY() - myPosition.getY();
        Point2d normalised = Point2d.of(dx, dy).div((float)Math.max(1.0, myPosition.distance(enemyPosition)));
        return myPosition.sub(normalised.mul(distance));
    }
    /**
     * Returns the position to retreat to, but biased towards a certain position. We path along a line halfway 'away' from
     * the enemy and halfway towards the bias position.
     *
     * @param myPosition The position of the current unit.
     * @param enemyPosition The position of the enemy unit.
     * @param biasPosition The position to bias toward.
     * @param distance The distance to path back to.
     * @return
     */
    public static Point2d getBiasedRetreatPosition(Point2d myPosition, Point2d enemyPosition, Point2d biasPosition, float distance) {
        Point2d retreatPosition = getRetreatPosition(myPosition, enemyPosition, distance);
        float dx = myPosition.getX() - enemyPosition.getX();
        float dy = myPosition.getY() - enemyPosition.getY();
        Point2d normalisedFromEnemy = Point2d.of(dx, dy).div((float)Math.max(1.0, myPosition.distance(enemyPosition)));
        dx = biasPosition.getX() - myPosition.getX();
        dy = biasPosition.getY() - myPosition.getY();
        Point2d normalisedToBias = Point2d.of(dx, dy).div((float)Math.max(1.0, myPosition.distance(biasPosition)));
        Point2d normalised = normalisedFromEnemy.add(normalisedToBias);
        float normalisedDistance = (float) normalised.distance(Point2d.of(0f, 0f));
        return myPosition.add(normalised.mul(distance).div(normalisedDistance));
    }/**
     * Returns the position to retreat to, but biased towards a certain position. We path along a line halfway 'away' from
     * the enemy and halfway towards the bias position.
     *
     * @param myPosition The position of the current unit.
     * @param enemyPosition The position of the enemy unit.
     * @param biasPosition The optional position to bias toward.
     * @param distance The distance to path back to.
     * @return
     */
    public static Point2d getBiasedRetreatPosition(Point2d myPosition, Point2d enemyPosition, Optional<Point2d> biasPosition, float distance) {
        if (biasPosition.isPresent()) {
            return getBiasedRetreatPosition(myPosition, enemyPosition, biasPosition.get(), distance);
        } else {
            return getRetreatPosition(myPosition, enemyPosition, distance);
        }
    }
}
