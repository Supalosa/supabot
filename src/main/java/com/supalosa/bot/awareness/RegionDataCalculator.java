package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Upgrade;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.supalosa.bot.Constants;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.engagement.ThreatCalculator;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RegionDataCalculator {

    // This is the expected distance between each region. It is a bit of a magic number.
    static final double DIFFUSE_THREAT_CONSTANT = 5.0;

    // Number of structures per region before it's considered a (player) base.
    public static final int BASE_STRUCTURES_PER_REGION = 5;

    private final ThreatCalculator threatCalculator;

    public RegionDataCalculator(ThreatCalculator threatCalculator) {
        this.threatCalculator = threatCalculator;
    }

    public Map<Integer, RegionData> calculateRegionData(
            S2Agent agent,
            AnalysisResults analysisResults,
            Map<Integer, RegionData> previousRegionData,
            List<Point2d> knownEnemyBases) {
        // Used for region power calculation.
        Set<Upgrade> upgrades = agent.observation().getUpgrades().stream().collect(Collectors.toSet());
        Map<Integer, RegionData> result = new HashMap<>();

        List<UnitInPool> allUnits = agent.observation().getUnits();
        Multimap<Integer, UnitInPool> regionIdToEnemyUnits = ArrayListMultimap.create();
        Multimap<Integer, UnitInPool> regionIdToSelfUnits = ArrayListMultimap.create();
        allUnits.forEach(unit -> {
            Point2d point2d = unit.unit().getPosition().toPoint2d();
            boolean isEnemy = unit.unit().getAlliance() == Alliance.ENEMY;
            boolean isSelf = unit.unit().getAlliance() == Alliance.SELF;
            if (!isEnemy && !isSelf)
                return;
            analysisResults.getTile((int)point2d.getX(), (int)point2d.getY()).ifPresent(tile -> {
                if (tile.regionId >= 0) {
                    if (isEnemy) {
                        regionIdToEnemyUnits.put(tile.regionId, unit);
                    } else if (isSelf) {
                        regionIdToSelfUnits.put(tile.regionId, unit);
                    }
                }
            });
        });
        Set<Integer> regionIdsWithEnemyBases = new HashSet<>();
        knownEnemyBases.forEach(point2d -> {
            analysisResults.getTile((int)point2d.getX(), (int)point2d.getY()).ifPresent(tile -> {
                if (tile.regionId >= 0) {
                    regionIdsWithEnemyBases.add(tile.regionId);
                }
            });
        });
        Set<Integer> regionIdsWithPlayerBases = calculatePlayerBases(regionIdToSelfUnits);
        double maxEnemyThreat = 0;
        Map<Integer, Double> regionToEnemyThreat = new HashMap<>();
        Map<Integer, Double> regionToVisibility = new HashMap<>();
        Map<Integer, Double> regionToCreep = new HashMap<>();
        Map<Integer, Double> regionToDecayingVisibility = new HashMap<>();
        Map<Integer, Double> regionToPreviousDiffuseThreat = new HashMap<>();

        for (Region region : analysisResults.getRegions()) {
            Optional<RegionData> previousData = Optional.ofNullable(previousRegionData.get(region.regionId()));
            // Calculation and decay of region visibility.
            double currentVisibility = calculateVisibilityOfRegion(agent.observation(), region, 0.33);
            double currentCreep = calculateCreepCoverageOfRegion(agent.observation(), region, 0.33);
            double decayingVisibilityValue = previousData.isEmpty() ?
                    currentVisibility :
                    Math.max(currentVisibility, previousData.get().decayingVisibilityPercent() * 0.95 - 0.01);
            // Calculation and decay of region threat.
            double currentThreatValue = threatCalculator.calculateThreat(
                    regionIdToEnemyUnits.get(region.regionId()).stream()
                            .map(unitInPool -> unitInPool.unit().getType())
                            .collect(Collectors.toUnmodifiableList()));
            // Enemy threat decays faster if we have visibility. The more visible, the faster it decays.
            double visibilityDecay = 0.75 + 0.25 * (1.0 - currentVisibility);
            double threatValue = previousData.isEmpty() ?
                    currentThreatValue :
                    Math.max(currentThreatValue, previousData.get().enemyThreat() * visibilityDecay - 0.1);
            regionToEnemyThreat.put(region.regionId(), threatValue);
            regionToVisibility.put(region.regionId(), currentVisibility);
            regionToDecayingVisibility.put(region.regionId(), decayingVisibilityValue);
            regionToCreep.put(region.regionId(), currentCreep);
            // This is calculated later.
            regionToPreviousDiffuseThreat.put(region.regionId(), previousData.map(RegionData::diffuseEnemyThreat).orElse(0.0));
            if (threatValue > maxEnemyThreat) {
                maxEnemyThreat = threatValue;
            }
        }

        double finalMaxEnemyThreat = maxEnemyThreat;
        analysisResults.getRegions().forEach(region -> {
            Optional<RegionData> previousData = Optional.ofNullable(previousRegionData.get(region.regionId()));
            double currentPower = threatCalculator.calculatePower(
                    regionIdToSelfUnits.get(region.regionId()).stream()
                            .map(unitInPool -> unitInPool.unit().getType())
                            .collect(Collectors.toUnmodifiableList()), upgrades);
            double enemyThreat = regionToEnemyThreat.get(region.regionId());
            // Killzone threat is the sum of threat on regions on high ground of this one.
            Optional<Double> killzoneThreat = region.onLowGroundOfRegions().stream().map(highGroundRegionId ->
                    regionToEnemyThreat.get(highGroundRegionId)
            ).reduce(Double::sum);
            // Calculating neighbour threat.
            Optional<Double> neighbourThreat = region.connectedRegions().stream().map(connectedRegionId ->
                    regionToEnemyThreat.get(connectedRegionId)
            ).reduce(Double::sum).map(threat -> threat + enemyThreat);
            // Diffuse threat is the sum of the threat of all other regions, modulated by the distance to those
            // regions. To be honest there's probably a JGraphT algorithm for this which would be better.
            Optional<Double> diffuseThreat = getDiffuseEnemyThreatForRegion(
                    analysisResults,
                    regionToEnemyThreat,
                    region);
            // For ramps only, detect if they are blocked.
            boolean isRampAndBlocked = false;
            if (region.getRampId().isPresent()) {
                isRampAndBlocked = analysisResults
                        .getRamp(region.getRampId().get())
                        .calculateIsBlocked(agent.observation());
            }
            // The cumulative control swings depending on whether the player or enemy has more control.
            double powerDelta = currentPower - enemyThreat;
            double previousControl = previousData.map(RegionData::cumulativeControl).orElse(1.0);
            if (previousControl > 0 && powerDelta < 0) {
                // Losing control.
                powerDelta = powerDelta * Math.log(Math.abs(previousControl) + 1);
            } else if (previousControl < 0 && powerDelta > 0) {
                // Gaining control.
                powerDelta = powerDelta * Math.log(Math.abs(previousControl) + 1);
            }
            double cumulativeControl = previousControl + powerDelta;
            // https://www.wolframalpha.com/input?i=plot+y+%3D+ln%28abs%28x%29%2B1%29+*+sign%28x%29+from+-10+to+10
            double controlFactor = Math.log(Math.abs(cumulativeControl) + 1) * Math.signum(cumulativeControl);
            double visibilityPercent = regionToVisibility.get(region.regionId());
            double decayingVisibilityPercent = regionToDecayingVisibility.get(region.regionId());
            double enemyArmyFactor = 1.0f + enemyThreat / Math.max(1.0, finalMaxEnemyThreat);
            double killzoneFactor = 1.0f + killzoneThreat.map(threat -> threat / Math.max(1.0, finalMaxEnemyThreat/2)).orElse(0.0);
            double visibilityDecay = 0.75 + 0.25 * (1.0 - visibilityPercent);
            double decayingDefuseThreat = Math.max(
                    diffuseThreat.orElse(0.0),
                    regionToPreviousDiffuseThreat.get(region.regionId()) * 0.75 - 1.0 * visibilityDecay);
            double regionCreepPercentage = regionToCreep.get(region.regionId());
            Optional<Point2d> averageBorderTile = calculateAverageOfBorderTiles(region, previousRegionData);
            ImmutableRegionData.Builder builder = ImmutableRegionData.builder()
                    .region(region)
                    .weight(1.0) // TODO what to do with this?
                    .isBlocked(isRampAndBlocked)
                    .enemyArmyFactor(enemyArmyFactor)
                    .killzoneFactor(killzoneFactor)
                    .nearbyEnemyThreat(neighbourThreat.orElse(0.0))
                    .enemyThreat(enemyThreat)
                    .playerThreat(currentPower)
                    .visibilityPercent(visibilityPercent)
                    .decayingVisibilityPercent(decayingVisibilityPercent)
                    .diffuseEnemyThreat(decayingDefuseThreat)
                    .hasEnemyBase(regionIdsWithEnemyBases.contains(region.regionId()))
                    .isPlayerBase(regionIdsWithPlayerBases.contains(region.regionId()))
                    .estimatedCreepPercentage(regionCreepPercentage)
                    .defenceRallyPoint(averageBorderTile)
                    .controlFactor(controlFactor)
                    .cumulativeControl(cumulativeControl);

            result.put(region.regionId(), builder.build());
        });
        return result;
    }

    private Set<Integer> calculatePlayerBases(Multimap<Integer, UnitInPool> regionIdToSelfUnits) {
        Set<Integer> result = new HashSet<>();
        Map<Integer, Integer> regionIdToStructureCount = new HashMap<>();
        regionIdToSelfUnits.forEach((regionId, unit) -> {
            // Any region with a town hall is considered a base.
            if (Constants.ALL_TOWN_HALL_TYPES.contains(unit.unit().getType())) {
                result.add(regionId);
            }
            // Any region with more than 5 structures is considered a base.
            if (Constants.STRUCTURE_UNIT_TYPES.contains(unit.unit().getType())) {
                regionIdToStructureCount.compute(regionId, (k, v) -> v == null ? 1 : v + 1);
                if (regionIdToStructureCount.getOrDefault(regionId, 0) >= BASE_STRUCTURES_PER_REGION) {
                    result.add(regionId);
                }
            }
        });
        return result;
    }

    /**
     * Return the average point of all the border tiles facing outwards.
     * TODO: if this isn't dynamic anymore, it should be in the
     */
    private Optional<Point2d> calculateAverageOfBorderTiles(Region region, Map<Integer, RegionData> previousRegionData) {
        Set<Point2d> relevantBorderTiles = new HashSet<>();
        if (region.getBorderTiles().isPresent()) {
            // We're using the previous region data as a shortcut here.
            // Find the neighbouring region with the highest diffuse threat [on the previous update]
            // Note the 1.0 is to prevent returning anything until we actually see the threat.
            double maxDiffuseThreat = 1.0;
            int maxDiffuseThreatRegion = -1;
            for (Integer connectedRegionId : region.connectedRegions()) {
                RegionData neighbouringRegionData = previousRegionData.get(connectedRegionId);
                if (neighbouringRegionData == null) {
                    continue;
                }
                if (maxDiffuseThreatRegion < 0 || neighbouringRegionData.diffuseEnemyThreat() > maxDiffuseThreat) {
                    maxDiffuseThreat = neighbouringRegionData.diffuseEnemyThreat();
                    maxDiffuseThreatRegion = connectedRegionId;
                }
            }
            if (maxDiffuseThreatRegion > 0) {
                RegionData highThreatDirection = previousRegionData.get(maxDiffuseThreatRegion);
                if (highThreatDirection.region().getBorderTiles().isPresent()) {
                    Set<Point2d> myBorderTiles = region.getBorderTiles().get();
                    Set<Point2d> theirBorderTiles = highThreatDirection.region().getBorderTiles().get();
                    relevantBorderTiles.addAll(myBorderTiles.stream().filter(myBorderTile ->
                            theirBorderTiles.stream().anyMatch(theirBorderTile -> myBorderTile.distance(theirBorderTile) < 2f))
                            .collect(Collectors.toSet()));
                }
            }
        }
        OptionalDouble averageX = relevantBorderTiles.stream().mapToDouble(point -> point.getX()).average();
        OptionalDouble averageY = relevantBorderTiles.stream().mapToDouble(point -> point.getY()).average();
        Optional<Point2d> averageBorderTileTowardsEnemy = Optional.empty();
        if (averageX.isPresent() && averageY.isPresent()) {
            averageBorderTileTowardsEnemy = Optional.of(Point2d.of((float) averageX.getAsDouble(), (float) averageY.getAsDouble()));
        }

        return averageBorderTileTowardsEnemy;
    }

    Optional<Double> getDiffuseEnemyThreatForRegion(AnalysisResults analysisResults,
                                                            Map<Integer, Double> regionToEnemyThreat,
                                                            Region region) {
        // Diffuse threat is the sum of the threat of all other regions, modulated by the distance to those
        // regions. To be honest there's probably a JGraphT algorithm for this which would be better.
        double diffuseThreat = 0;
        Queue<Region> openSet = new LinkedList<>();
        Map<Integer, Double> distanceToRegion = new HashMap<>();
        Set<Integer> closedSet = new HashSet();
        openSet.add(region);
        distanceToRegion.put(region.regionId(), 0.0);
        while (openSet.size() > 0) {
            Region head = openSet.poll();
            closedSet.add(head.regionId());
            double distance = distanceToRegion.getOrDefault(head.regionId(), 0.0);
            double distanceFactor = DIFFUSE_THREAT_CONSTANT / Math.max(DIFFUSE_THREAT_CONSTANT, distance);
            diffuseThreat += regionToEnemyThreat.getOrDefault(head.regionId(), 0.0) * distanceFactor;

            //System.out.println("Region " + head.regionId() + " distance = " + distance + ", result " + diffuseThreat);
            head.connectedRegions().forEach(connectedRegionId -> {
                if (closedSet.contains(connectedRegionId)) {
                    return;
                }
                Region connectedRegion = analysisResults.getRegion(connectedRegionId);
                distanceToRegion.put(connectedRegionId, distance + head.centrePoint().distance(connectedRegion.centrePoint()));
                openSet.add(connectedRegion);
            });
        }
        return Optional.of(diffuseThreat);
    }

    /**
     * Calculate the visibility of a given region.
     *
     * @param observation Observation interface to use.
     * @param region Region to calculate.
     * @param resolution Approximate percentage of tiles to query. 1.0 for all tiles. 0.25 for quarter etc.
     */
    private double calculateVisibilityOfRegion(ObservationInterface observation, Region region, double resolution) {
        return calculateValueOverRegion(region, resolution, tile -> observation.getVisibility(tile) == Visibility.VISIBLE);
    }

    /**
     * Calculate the creep coverage of a given region.
     *
     * @param observation Observation interface to use.
     * @param region Region to calculate.
     * @param resolution Approximate percentage of tiles to query. 1.0 for all tiles. 0.25 for quarter etc.
     */
    private double calculateCreepCoverageOfRegion(ObservationInterface observation, Region region, double resolution) {
        return calculateValueOverRegion(region, resolution, point -> observation.hasCreep(point));
    }

    /**
     * Calculate a generalised boolean over a region.
     *
     * @param region Region to calculate.
     * @param resolution Approximate percentage of tiles to query. 1.0 for all tiles. 0.25 for quarter etc.
     * @param predicate Predicate to test on sampled tiles.
     */
    private double calculateValueOverRegion(Region region, double resolution, Function<Point2d, Boolean> predicate) {
        if (region.getTiles().size() == 0) {
            return 0.0;
        }
        double gapBetweenTiles = (1.0 / resolution);
        int visibleTiles = 0;
        int sampledTiles = 0;
        Point2d minBound = region.regionBounds().getLeft();
        Point2d maxBound = region.regionBounds().getRight();
        for (double x = minBound.getX(); x < maxBound.getX(); x += gapBetweenTiles) {
            for (double y = minBound.getY(); y < maxBound.getY(); y += gapBetweenTiles) {
                Point2d point = Point2d.of((int)x, (int)y);
                // TODO: maybe grid lookup is better for this test.
                if (region.getTiles().contains(point)) {
                    if (predicate.apply(point)) {
                        visibleTiles++;
                    }
                    ++sampledTiles;
                }
            }
        }
        return visibleTiles / Math.max(1.0, sampledTiles);
    }
}
