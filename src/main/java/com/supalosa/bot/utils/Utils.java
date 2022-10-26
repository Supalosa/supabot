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
     * @param myPosition
     * @param enemyPosition
     * @param distance
     * @return
     */
    public static Point2d getRetreatPosition(Point2d myPosition, Point2d enemyPosition, float distance) {
        float dx = enemyPosition.getX() - myPosition.getX();
        float dy = enemyPosition.getY() - myPosition.getY();
        Point2d normalised = Point2d.of(dx, dy).div((float)Math.max(1.0, myPosition.distance(enemyPosition)));
        return myPosition.sub(normalised.mul(distance));
    }
}
