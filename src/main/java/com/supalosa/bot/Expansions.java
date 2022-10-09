package com.supalosa.bot;

import com.github.ocraft.s2client.bot.gateway.*;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.query.QueryBuildingPlacement;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import org.immutables.value.Value;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

public class Expansions {
    public static List<Expansion> processExpansions(
            QueryInterface queryInterface,
            Point2d startLocation,
            Point2d opponentLocation,
            List<Point> calculatedExpansions) {
        List<Expansion> orderedExpansionLocations = calculatedExpansions
                .stream()
                .map(point -> {
                    return ImmutableExpansion.builder()
                            .position(point)
                            .distanceToStart(queryInterface.pathingDistance(startLocation, point.toPoint2d()))
                            .distanceToOpponent(queryInterface.pathingDistance(opponentLocation, point.toPoint2d()))
                            .build();
                }).collect(Collectors.toList());

        orderedExpansionLocations.sort((p1, p2) -> {
            int d1 = p1.distanceToStart().intValue();
            int d2 = p2.distanceToStart().intValue();
            if (d1 == 0) {
                d1 = Integer.MAX_VALUE;
            }
            if (d2 == 0) {
                d2 = Integer.MAX_VALUE;
            }
            return d1 - d2;
        });
        System.out.println("start = " + startLocation);
        orderedExpansionLocations.forEach(expansion -> {
            System.out.println("expansion = " + expansion + ", distance = " + expansion.distanceToStart());
        });
        return orderedExpansionLocations;
    }

    /**
     * Modified implementation of built-in calculateExpansionLocations;
     * @param observation
     * @param query
     * @param debug
     * @param parameters
     * @return
     */
    public static List<Point> calculateExpansionLocations(
            ObservationInterface observation, QueryInterface query, DebugInterface debug, ExpansionParameters parameters) {
        List<UnitInPool> resources = observation.getUnits(unitInPool -> {
            Set<UnitType> fields = new HashSet<>(asList(
                    Units.NEUTRAL_MINERAL_FIELD, Units.NEUTRAL_MINERAL_FIELD750,
                    Units.NEUTRAL_RICH_MINERAL_FIELD, Units.NEUTRAL_RICH_MINERAL_FIELD750,
                    Units.NEUTRAL_PURIFIER_MINERAL_FIELD, Units.NEUTRAL_PURIFIER_MINERAL_FIELD750,
                    Units.NEUTRAL_PURIFIER_RICH_MINERAL_FIELD, Units.NEUTRAL_PURIFIER_RICH_MINERAL_FIELD750,
                    Units.NEUTRAL_LAB_MINERAL_FIELD, Units.NEUTRAL_LAB_MINERAL_FIELD750,
                    Units.NEUTRAL_BATTLE_STATION_MINERAL_FIELD, Units.NEUTRAL_BATTLE_STATION_MINERAL_FIELD750,
                    Units.NEUTRAL_VESPENE_GEYSER, Units.NEUTRAL_PROTOSS_VESPENE_GEYSER,
                    Units.NEUTRAL_SPACE_PLATFORM_GEYSER, Units.NEUTRAL_PURIFIER_VESPENE_GEYSER,
                    Units.NEUTRAL_SHAKURAS_VESPENE_GEYSER, Units.NEUTRAL_RICH_VESPENE_GEYSER
            ));
            return fields.contains(unitInPool.unit().getType());
        });

        List<Point> expansionLocations = new ArrayList<>();
        Map<Point, List<UnitInPool>> clusters = cluster(resources, parameters.getClusterDistance());

        Map<Point, Integer> querySize = new LinkedHashMap<>();
        List<QueryBuildingPlacement> queries = new ArrayList<>();
        for (Map.Entry<Point, List<UnitInPool>> cluster : clusters.entrySet()) {
            if (debug != null) {
                for (double r : parameters.getRadiuses()) {
                    debug.debugSphereOut(cluster.getKey(), (float) r, Color.GREEN);
                }
            }

            // Get the required queries for this cluster.
            int queryCount = 0;
            for (double r : parameters.getRadiuses()) {
                List<QueryBuildingPlacement> calculatedQueries = calculateQueries(
                        r, cluster.getKey().toPoint2d());
                queries.addAll(calculatedQueries);
                queryCount += calculatedQueries.size();
            }

            querySize.put(cluster.getKey(), queryCount);
        }

        List<Boolean> results = query.placement(queries);
        int startIndex = 0;
        for (Map.Entry<Point, List<UnitInPool>> cluster : clusters.entrySet()) {
            double distance = Double.MAX_VALUE;
            Point2d closest = null;

            // For each query for the cluster minimum distance location that is valid.
            for (int j = startIndex, e = startIndex + querySize.get(cluster.getKey()); j < e; ++j) {
                if (!results.get(j)) {
                    continue;
                }

                Point2d p = queries.get(j).getTarget();

                double d = p.distance(cluster.getKey().toPoint2d());
                if (d < distance) {
                    distance = d;
                    closest = p;
                }
            }

            if (closest != null) {
                Point expansion = Point.of(
                        closest.getX(),
                        closest.getY(),
                        cluster.getValue().get(0).unit().getPosition().getZ());
                if (debug != null) {
                    debug.debugSphereOut(expansion, 0.35f, Color.RED);
                }

                expansionLocations.add(expansion);
            }

            startIndex += querySize.get(cluster.getKey());
        }

        return expansionLocations;
    }

    static Map<Point, List<UnitInPool>> cluster(List<UnitInPool> units, double distanceApart) {
        Map<Point, List<UnitInPool>> clusters = new LinkedHashMap<>();
        for (UnitInPool u : units) {
            double distance = Double.MAX_VALUE;
            Map.Entry<Point, List<UnitInPool>> targetCluster = null;
            // Find the cluster this mineral patch is closest to.
            for (Map.Entry<Point, List<UnitInPool>> cluster : clusters.entrySet()) {
                double d = u.unit().getPosition().distance(cluster.getKey());
                if (d < distance) {
                    distance = d;
                    targetCluster = cluster;
                }
            }

            // If the target cluster is some distance away don't use it.
            if (targetCluster == null || distance > distanceApart) {
                ArrayList<UnitInPool> unitsInCluster = new ArrayList<>();
                unitsInCluster.add(u);
                clusters.put(u.unit().getPosition(), unitsInCluster);
                continue;
            }

            // Otherwise append to that cluster and update it's center of mass.

            if (targetCluster.getValue() == null) {
                targetCluster.setValue(new ArrayList<>());
            }
            targetCluster.getValue().add(u);

            int size = targetCluster.getValue().size();
            Point centerOfMass = targetCluster.getKey().mul(size - 1.0f).add(u.unit().getPosition()).div(size);
            clusters.put(centerOfMass, clusters.remove(targetCluster.getKey()));
        }

        return clusters;
    }

    private static List<QueryBuildingPlacement> calculateQueries(double radius, Point2d center) {
        List<QueryBuildingPlacement> queries = new ArrayList<>();

        // Find a buildable location on the circumference of the sphere
        int x = (int)center.getX(), y = (int)center.getY();
        for (int dx = -8; dx <= 8; ++dx) {
            for (int dy = -8; dy <= 8; ++dy) {
                Point2d point = center.add(dx, dy);
                QueryBuildingPlacement query = QueryBuildingPlacement
                        .placeBuilding()
                        .useAbility(Abilities.BUILD_COMMAND_CENTER)
                        .on(point)
                        .build();
                queries.add(query);
            }
        }

        return queries;
    }
}
