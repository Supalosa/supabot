package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.*;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.UnitAttribute;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.observation.spatial.ImageData;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.Expansion;
import com.supalosa.bot.Expansions;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.engagement.ThreatCalculator;
import com.supalosa.bot.pathfinding.GraphUtils;
import com.supalosa.bot.pathfinding.RegionGraph;
import com.supalosa.bot.pathfinding.RegionGraphPath;
import com.supalosa.bot.utils.UnitFilter;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class MapAwarenessImpl implements MapAwareness {

    private Optional<Point2d> startPosition;
    private Optional<RegionData> mainBaseRegion;
    private final List<Point2d> knownEnemyBases;
    private Optional<Point2d> knownEnemyStartLocation = Optional.empty();
    // A map of locations that should be scouted, and when they should be scouted next.
    private final Map<Point2d, Long> scoutableLocationsToNextScoutTime = new HashMap<>();
    private final Set<Point2d> scoutableLocations = new HashSet<>();
    private static final long RESCOUT_TIME = 180L * 22L;
    private final Map<Expansion, Long> expansionNextValidAt = new HashMap<>();
    private static final long EXPANSION_BACKOFF_TIME = (15 * 22L);
    LinkedHashSet<Expansion> validExpansionLocations = new LinkedHashSet<>();
    private Optional<List<Expansion>> expansionLocations = Optional.empty();
    private Optional<Point2d> defenceLocation = Optional.empty();

    private long expansionsValidatedAt = 0L;

    // Temporary 'binary' enemy positions.
    private Optional<Point2d> maybeEnemyPositionNearEnemy = Optional.empty();
    private Optional<Point2d> maybeEnemyPositionNearBase = Optional.empty();

    private final long myDefendableStructuresCalculatedAt = 0L;
    private List<Unit> myDefendableStructures = new ArrayList<>();

    private Optional<ImageData> cachedCreepMap = Optional.empty();
    private Optional<Float> creepCoveragePercentage = Optional.empty();
    private long creepMapUpdatedAt = 0L;

    private Map<Integer, RegionData> regionData = new HashMap<>();
    private long regionDataCalculatedAt = 0L;

    private Optional<RegionGraph> normalGraph = Optional.empty();
    private Optional<RegionGraph> avoidArmyGraph = Optional.empty();
    private Optional<RegionGraph> avoidKillzoneGraph = Optional.empty();
    private Optional<RegionGraph> airAvoidArmyGraph = Optional.empty();

    private Optional<AnalysisResults> mapAnalysisResults = Optional.empty();

    private ThreatCalculator threatCalculator;

    private final RegionDataCalculator regionDataCalculator;

    public MapAwarenessImpl(ThreatCalculator threatCalculator) {
        this.startPosition = Optional.empty();
        this.mainBaseRegion = Optional.empty();
        this.knownEnemyBases = new ArrayList<>();
        this.regionDataCalculator = new RegionDataCalculator(threatCalculator);
        this.threatCalculator = threatCalculator;
    }

    @Override
    public Optional<RegionData> getRegionDataForPoint(Point2d point) {
        return mapAnalysisResults.flatMap(analysis -> {
            Optional<Integer> maybeRegionId = analysis.getTile((int)point.getX(), (int)point.getY()).map(tile -> tile.regionId);
            if (maybeRegionId.isPresent()) {
                return Optional.ofNullable(regionData.get(maybeRegionId.get()));
            } else {
                return Optional.empty();
            }
        });
    }

    @Override
    public Optional<RegionData> getRegionDataForId(int regionId) {
        if (regionData.containsKey(regionId)) {
            return Optional.of(regionData.get(regionId));
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RegionGraph> getPathingGraph(PathRules rules) {
        switch (rules) {
            case AVOID_ENEMY_ARMY:
                return avoidArmyGraph;
            case AVOID_KILL_ZONE:
                return avoidKillzoneGraph;
            case AIR_AVOID_ENEMY_ARMY:
                return airAvoidArmyGraph;
            case NORMAL:
            default:
                return normalGraph;
        }
    }

    @Override
    public Optional<RegionGraphPath> generatePath(Region startRegion, Region endRegion, PathRules rules) {
        return getPathingGraph(rules).flatMap(graph -> graph.findPath(startRegion, endRegion));
    }

    @Override
    public Collection<RegionData> getAllRegionData() {
        return regionData.values();
    }

    @Override
    public void setStartPosition(Point2d startPosition) {
        this.startPosition = Optional.of(startPosition);
    }

    @Override
    public List<Point2d> getKnownEnemyBases() {
        return knownEnemyBases;
    }

    @Override
    public Optional<RegionData> getRandomPlayerBaseRegion() {
        List<RegionData> playerBaseRegions = regionData.values().stream()
                .filter(RegionData::isPlayerBase)
                .collect(Collectors.toList());
        if (playerBaseRegions.size() > 0) {
            int returnIndex = ThreadLocalRandom.current().nextInt(playerBaseRegions.size());
            return Optional.of(playerBaseRegions.get(returnIndex));
        } else {
            return Optional.empty();
        }
    }

    /**
     * Returns a list of all expansion locations if applicable.
     *
     * @return List of all expansions on the map, or empty if not calculated or empty.
     */
    @Override
    public Optional<List<Expansion>> getExpansionLocations() {
        return expansionLocations.flatMap(locations -> locations.isEmpty() ? Optional.empty() : Optional.of(locations));
    }

    /**
     * Returns a list of viable expansion locations if applicable.
     *
     * @return List of expansions we should try expanding to, or empty if not calculated or empty.
     */
    @Override
    public Optional<List<Expansion>> getValidExpansionLocations() {
        return Optional.of(validExpansionLocations.stream().collect(Collectors.toList()))
                .flatMap(locations -> locations.isEmpty() ? Optional.empty() : Optional.of(locations));
    }

    /**
     * Mark the specified expansion as attempted on the given game loop.
     *
     * @param expansion
     * @param whenGameLoop
     */
    @Override
    public void onExpansionAttempted(Expansion expansion, long whenGameLoop) {
        expansionNextValidAt.put(expansion, whenGameLoop + EXPANSION_BACKOFF_TIME);
    }

    @Override
    public void onStep(AgentData data, S2Agent agent) {
        manageScouting(data, agent.observation(), agent.actions(), agent.query());
        updateExpansionsAndBases(agent.observation(), agent.query());
        updateMyDefendableStructures(data, agent.observation());

        analyseCreep(data, agent);
        updateRegionData(data, agent);

        this.maybeEnemyPositionNearEnemy = findEnemyPositionNearPoint(agent.observation(), true);
        this.maybeEnemyPositionNearBase = findEnemyPositionNearPoint(agent.observation(), false);
    }

    private void updateRegionData(AgentData data, S2Agent agent) {
        long gameLoop = agent.observation().getGameLoop();
        if (data.mapAnalysis().isPresent() && gameLoop > regionDataCalculatedAt + 33L) {
            regionDataCalculatedAt = gameLoop;
            AnalysisResults analysisResults = data.mapAnalysis().get();
            regionData = regionDataCalculator.calculateRegionData(agent, analysisResults, regionData, knownEnemyBases);

            normalGraph = Optional.of(GraphUtils.createGraph(analysisResults, Region::connectedRegions, regionData,
                    (sourceRegion, destinationRegion) -> destinationRegion.weight()));

            // Edges are weighted by the diffuse enemy threat.
            avoidArmyGraph = Optional.of(GraphUtils.createGraph(analysisResults, Region::connectedRegions, regionData,
                    (sourceRegion, destinationRegion) -> destinationRegion.diffuseEnemyThreat()));

            avoidKillzoneGraph = Optional.of(GraphUtils.createGraph(analysisResults, Region::connectedRegions, regionData,
                    (sourceRegion, destinationRegion) -> destinationRegion.killzoneFactor() < 10.0f ? destinationRegion.killzoneFactor() : null));

            airAvoidArmyGraph = Optional.of(GraphUtils.createGraph(analysisResults, Region::nearbyRegions, regionData,
                    (sourceRegion, destinationRegion) -> destinationRegion.diffuseEnemyThreat()));
        }
    }

    private void analyseCreep(AgentData data, S2Agent agent) {
        if (agent.observation().getGameLoop() > creepMapUpdatedAt + 22L * 10) {
            creepMapUpdatedAt = agent.observation().getGameLoop();
            cachedCreepMap = agent.observation().getRawObservation().getRaw().map(raw -> raw.getMapState().getCreep());
            cachedCreepMap.ifPresent(creepData -> {
                data.mapAnalysis().ifPresent(mapAnalysis -> {
                    int pixelsWithCreep = 0;
                    for (int x = 0; x < creepData.getSize().getX(); ++x) {
                        for (int y = 0; y < creepData.getSize().getY(); ++y) {
                            boolean creepAtPoint = (creepData.sample(Point2d.of(x, y), ImageData.Origin.BOTTOM_LEFT) > 0);
                            if (creepAtPoint) {
                                ++pixelsWithCreep;
                            }
                        }
                    }
                    float creepPercentage = pixelsWithCreep / (float)mapAnalysis.getPathableTiles();
                    creepCoveragePercentage = Optional.of(creepPercentage);
                });
            });
        }
    }

    @Override
    public Optional<Float> getObservedCreepCoverage() {
        return creepCoveragePercentage;
    }

    private void updateMyDefendableStructures(AgentData data, ObservationInterface observation) {
        long gameLoop = observation.getGameLoop();
        if (gameLoop > myDefendableStructuresCalculatedAt + 22L * 4) {
            myDefendableStructures.clear();
            List<Unit> structures = observation.getUnits(Alliance.SELF, unitInPool ->
                data.gameData().isStructure(unitInPool.unit().getType())
            ).stream().map(unitInPool -> unitInPool.unit()).collect(Collectors.toList());
            myDefendableStructures.addAll(structures);
            // Defend the region with the majority of structures.
            Map<Integer, Integer> regionToStructureValue = new HashMap<>();
            myDefendableStructures.forEach(structure -> {
                getRegionDataForPoint(structure.getPosition().toPoint2d()).ifPresent(regionData -> {
                    regionToStructureValue.put(regionData.region().regionId(),
                            regionToStructureValue.getOrDefault(regionData.region().regionId(), 0) +
                                    data.gameData().getUnitMineralCost(structure.getType()).orElse(100));
                });
            });
            defenceLocation = regionToStructureValue.entrySet().stream()
                    .sorted(
                            Comparator.comparing((Map.Entry<Integer, Integer> entry) -> entry.getValue())
                                    .reversed())
                    .findFirst()
                    .flatMap(mostValuableRegionId -> getRegionDataForId(mostValuableRegionId.getKey()))
                    .flatMap(RegionData::getDefenceRallyPoint);
        }
    }

    @Override
    public boolean shouldDefendLocation(Point2d location) {
        for (Unit unit : myDefendableStructures) {
            if (location.distance(unit.getPosition().toPoint2d()) < 10f) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<Point2d> getMaybeEnemyPositionNearEnemyBase() {
        return maybeEnemyPositionNearEnemy;
    }

    @Override
    public Optional<Point2d> getMaybeEnemyPositionNearOwnBase() {
        return maybeEnemyPositionNearBase;
    }


    private void updateExpansionsAndBases(ObservationInterface observationInterface, QueryInterface queryInterface) {
        long gameLoop = observationInterface.getGameLoop();
        if (this.expansionLocations.isPresent() && gameLoop > expansionsValidatedAt + 44L) {
            expansionsValidatedAt = gameLoop;

            // Calculate the region which represents our main base.
            mainBaseRegion = startPosition.flatMap(this::getRegionDataForPoint);

            // ExpansionLocations is ordered by distance to start point.
            this.validExpansionLocations = new LinkedHashSet<>();
            List<UnitInPool> minerals = observationInterface.getUnits(UnitFilter.builder()
                    .alliance(Alliance.NEUTRAL)
                    .unitTypes(Constants.MINERAL_TYPES).build());
            for (Expansion expansion : this.expansionLocations.get()) {
                if (!observationInterface.isPlacable(expansion.position().toPoint2d())) {
                    continue;
                }
                // Only expand if the region is not controlled by the enemy.
                Optional<RegionData> region = getRegionDataForPoint(expansion.position().toPoint2d());
                if (region.isPresent() && region.get().isEnemyControlled()) {
                    continue;
                }
                int remainingMinerals = expansion.resourcePositions().stream().mapToInt(point2d -> {
                    Optional<UnitInPool> maybeMineral = minerals.stream()
                            .filter(mineral -> mineral.unit().getPosition().toPoint2d().equals(point2d))
                            .findFirst();
                    // We use '100' for unknown mineral content (placeholder for snapshots)
                    return maybeMineral.map(unitInPool -> unitInPool.unit().getMineralContents().orElse(100)).orElse(0);
                }).sum();
                //System.out.println("Expansion at " + expansion.position() + " has " + remainingMinerals + " remaining");
                if (remainingMinerals > 0 &&
                        queryInterface.placement(Abilities.BUILD_COMMAND_CENTER, expansion.position().toPoint2d())) {
                    if (gameLoop > expansionNextValidAt.getOrDefault(expansion, 0L)) {
                        this.validExpansionLocations.add(expansion);
                    }
                }
            }
            knownEnemyBases.clear();
            expansionLocations.ifPresent(expansions -> {
                expansions.forEach(expansion -> {
                    List<UnitInPool> units = observationInterface.getUnits(
                            UnitFilter.builder()
                                    .unitTypes(Constants.ALL_TOWN_HALL_TYPES)
                                    .alliance(Alliance.ENEMY)
                                    .inRangeOf(expansion.position().toPoint2d())
                                    .range(2.5f)
                                    .includeIncomplete(true)
                                    .build());
                    if (units.size() > 0) {
                        knownEnemyBases.add(expansion.position().toPoint2d());
                    }
                });
            });
        }
    }

    // Finds a worthwhile enemy position to move units towards.
    private Optional<Point2d> findEnemyPositionNearPoint(ObservationInterface observationInterface, boolean nearEnemyBase) {
        if (nearEnemyBase && knownEnemyStartLocation.isPresent()) {
            return findEnemyPositionNearPoint(observationInterface, knownEnemyStartLocation.get());
        } else if (startPosition.isPresent()) {
            return findEnemyPositionNearPoint(observationInterface, startPosition.get());
        } else {
            throw new IllegalStateException("findEnemyPosition called before our start position is known.");
        }
    }

    @Override
    public Optional<Point2d> findEnemyPositionNearPoint(ObservationInterface observationInterface, Point2d point) {
        Comparator<UnitInPool> comparator =
                Comparator.comparing(unit -> unit.unit().getPosition().toPoint2d().distance(point));
        List<UnitInPool> enemyUnits = observationInterface.getUnits(Alliance.ENEMY);
        if (enemyUnits.size() > 0) {
            // Move towards the closest to our base (for now)
            return enemyUnits.stream()
                    .filter(unitInPool -> unitInPool.unit().getType() != Units.ZERG_LARVA)
                    .min(comparator)
                    .map(minUnit -> minUnit.unit().getPosition().toPoint2d());
        } else {
            return Optional.empty();
        }
    }

    /**
     * Returns a random, unscouted position on the map - either an expansion or a starting location
     * that we haven't seen yet.
     */
    private Optional<Point2d> findRandomUnscoutedLocation() {
        // We're somewhat reliant on the undefined ordering of the keys of a hashmap...
        return scoutableLocations.stream().findAny();
    }

    private void manageScouting(
            AgentData data,
            ObservationInterface observationInterface,
            ActionInterface actionInterface,
            QueryInterface queryInterface) {

        if (knownEnemyStartLocation.isEmpty()) {
            observationInterface.getGameInfo().getStartRaw().ifPresent(startRaw -> {
                // Note: startRaw.getStartLocations() is actually potential `enemy` locations.
                // If there's only one enemy location, the opponent is there.
                Set<Point2d> enemyStartLocations = startRaw.getStartLocations();
                if (scoutableLocationsToNextScoutTime.isEmpty()) {
                    enemyStartLocations.forEach(location ->
                            scoutableLocationsToNextScoutTime.put(location, Long.MIN_VALUE));
                }
                if (enemyStartLocations.size() == 1) {
                    knownEnemyStartLocation = enemyStartLocations.stream().findFirst();
                    System.out.println("Pre-determined enemy location at " + knownEnemyStartLocation.get());
                } else {
                    // Collect a list of all enemy structures and check if they are near a potential start location.
                    // If we find it, that's a valid start location.
                    List<Unit> enemyStructures = observationInterface.getUnits(
                                    unitInPool -> unitInPool.getUnit().filter(
                                            unit -> unit.getAlliance() == Alliance.ENEMY &&
                                                    data.gameData().getUnitTypeData(unit.getType())
                                                            .map(unitTypeData -> unitTypeData.getAttributes().contains(UnitAttribute.STRUCTURE))
                                                            .orElse(false)
                                    ).isPresent())
                            .stream()
                            .filter(unitInPool -> unitInPool.getUnit().isPresent())
                            .map(unitInPool -> unitInPool.getUnit().get())
                            .collect(Collectors.toList());
                    for (Unit enemyStructure : enemyStructures) {
                        Point2d position = enemyStructure.getPosition().toPoint2d();
                        for (Point2d enemyStartLocation : enemyStartLocations) {
                            if (position.distance(enemyStartLocation) < 10) {
                                System.out.println("Scouted enemy location at " + enemyStartLocation);
                                knownEnemyStartLocation = Optional.of(enemyStartLocation);
                                return;
                            }
                        }
                    }
                }
            });
        }

        // One-time heavyweight method to calculate and score expansions based on known enemy start location.
        if (expansionLocations.isEmpty() &&
                startPosition.isPresent() &&
                knownEnemyStartLocation.isPresent() &&
                observationInterface.getGameLoop() > 200) {
            ExpansionParameters parameters = ExpansionParameters.from(
                    List.of(6.4, 5.3, 5.1),
                    0.25,
                    15.0);
            expansionLocations = Optional.of(Expansions.processExpansions(
                    observationInterface,
                    queryInterface,
                    startPosition.get(),
                    knownEnemyStartLocation.get(),
                    Expansions.calculateExpansionLocations(observationInterface, queryInterface, parameters)));
            if (expansionLocations.isPresent()) {
                data.structurePlacementCalculator().ifPresent(spc -> spc.onExpansionsCalculated(expansionLocations.get()));
                expansionLocations.get().stream().map(Expansion::position).forEach(point ->
                        scoutableLocationsToNextScoutTime.put(point.toPoint2d(), 0L));
            }
        }
        long gameLoop = observationInterface.getGameLoop();
        scoutableLocations.clear();
        scoutableLocationsToNextScoutTime.entrySet().forEach(entry -> {
            Point2d location = entry.getKey();
            long lastSeenTime = entry.getValue();
            Visibility visibility = observationInterface.getVisibility(location);
            if (visibility == Visibility.VISIBLE) {
                lastSeenTime = gameLoop;
                scoutableLocationsToNextScoutTime.put(location, gameLoop);
            }
            if (gameLoop > lastSeenTime + RESCOUT_TIME) {
                scoutableLocations.add(location);
            }
        });
    }

    @Override
    public void debug(S2Agent agent) {
        this.regionData.values().forEach(regionData -> {
            regionData.getDefenceRallyPoint().ifPresent(defenceRallyPoint -> {
                float height = agent.observation().terrainHeight(defenceRallyPoint);
                Point point = Point.of(defenceRallyPoint.getX(), defenceRallyPoint.getY(), height);
                agent.debug().debugTextOut(regionData.region().regionId() + " DefPoint", point, Color.PURPLE, 10);
            });
        });
        final long gameLoop = agent.observation().getGameLoop();
        this.expansionNextValidAt.forEach((expansion, time) -> {
            long loopsLeft = (time - gameLoop);
            if (loopsLeft > 0) {
                agent.debug().debugTextOut("Ignoring: " + loopsLeft, expansion.position(), Color.RED, 10);
            }
        });
    }

    @Override
    public Optional<Point2d> getNextScoutTarget() {
        return findRandomUnscoutedLocation();
    }

    @Override
    public void setMapAnalysisResults(AnalysisResults analysis) {
        this.mapAnalysisResults = Optional.of(analysis);
    }

    @Override
    public Optional<Point2d> getDefenceLocation() {
        return defenceLocation;
    }

    @Override
    public Optional<RegionData> getMainBaseRegion() {
        return mainBaseRegion;
    }
}
