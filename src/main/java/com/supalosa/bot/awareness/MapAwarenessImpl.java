package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.*;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.UnitAttribute;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.observation.spatial.ImageData;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.Expansion;
import com.supalosa.bot.Expansions;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.engagement.ThreatCalculator;
import com.supalosa.bot.pathfinding.GraphUtils;
import com.supalosa.bot.pathfinding.RegionGraph;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.NotImplementedException;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MapAwarenessImpl implements MapAwareness {

    private Optional<Point2d> startPosition;
    private final List<Point2d> knownEnemyBases;
    private Optional<Point2d> knownEnemyStartLocation = Optional.empty();
    private Set<Point2d> unscoutedLocations = new HashSet<>();
    private Set<Tag> scoutingWith = new HashSet<>();
    private long scoutResetLoopTime = 0;
    private final Map<Expansion, Long> expansionLastAttempted = new HashMap<>();
    LinkedHashSet<Expansion> validExpansionLocations = new LinkedHashSet<>();
    private Optional<List<Expansion>> expansionLocations = Optional.empty();

    private long expansionsValidatedAt = 0L;

    // Temporary 'binary' enemy positions.
    private Optional<Point2d> maybeEnemyPositionNearEnemy = Optional.empty();
    private Optional<Point2d> maybeEnemyPositionNearBase = Optional.empty();

    private final long myDefendableStructuresCalculatedAt = 0L;
    private List<Unit> myDefendableStructures = new ArrayList<>();

    private long maybeEnemyArmyCalculatedAt = 0L;
    private Optional<ImmutableArmy> maybeLargestEnemyArmy = Optional.empty();
    private Map<Point, ImmutableArmy> enemyClusters = new HashMap<>();

    private Optional<ImageData> cachedCreepMap = Optional.empty();
    private Optional<Float> creepCoveragePercentage = Optional.empty();
    private long creepMapUpdatedAt = 0L;

    private Map<Integer, RegionData> regionData = new HashMap<>();
    private long regionDataCalculatedAt = 0L;

    private Optional<RegionGraph> normalGraph = Optional.empty();
    private Optional<RegionGraph> avoidArmyGraph = Optional.empty();
    private Optional<RegionGraph> avoidKillzoneGraph = Optional.empty();

    private Optional<AnalysisResults> mapAnalysisResults = Optional.empty();

    private ThreatCalculator threatCalculator;

    public MapAwarenessImpl(ThreatCalculator threatCalculator) {
        this.startPosition = Optional.empty();
        this.knownEnemyBases = new ArrayList<>();
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
    public Optional<List<Region>> generatePath(Region startRegion, Region endRegion, PathRules rules) {
        switch (rules) {
            case AVOID_ENEMY_ARMY:
                return avoidArmyGraph.flatMap(graph -> graph.findPath(startRegion, endRegion));
            case AVOID_KILL_ZONE:
                return avoidKillzoneGraph.flatMap(graph -> graph.findPath(startRegion, endRegion));
            case NORMAL:
            default:
                return normalGraph.flatMap(graph -> graph.findPath(startRegion, endRegion));
        }
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
    public Optional<Point2d> getStartPosition() {
        return startPosition;
    }

    @Override
    public List<Point2d> getKnownEnemyBases() {
        throw new NotImplementedException("Not ready yet");
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
        expansionLastAttempted.put(expansion, whenGameLoop);
    }

    @Override
    public void onStep(AgentData data, S2Agent agent) {
        manageScouting(data, agent.observation(), agent.actions(), agent.query());
        updateValidExpansions(agent.observation(), agent.query());
        updateMyDefendableStructures(data, agent.observation());

        analyseCreep(data, agent);
        updateRegionData(data, agent);

        if (agent.observation().getGameLoop() > maybeEnemyArmyCalculatedAt + 22L) {
            maybeEnemyArmyCalculatedAt = agent.observation().getGameLoop();
            List<UnitInPool> enemyArmy = agent.observation().getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.ENEMY)
                            .unitTypes(Constants.ARMY_UNIT_TYPES)
                            .build());
            Map<Point,List<UnitInPool>> clusters = Expansions.cluster(enemyArmy, 10f);
            // Army threat decays slowly
            if (maybeLargestEnemyArmy.isPresent() && agent.observation().getVisibility(maybeLargestEnemyArmy.get().position()) == Visibility.VISIBLE) {
                maybeLargestEnemyArmy = maybeLargestEnemyArmy.map(army -> army
                        .withSize(army.size() * 0.5f - 1.0f)
                        .withThreat(army.threat() * 0.5f - 1.0f));
            } else {
                maybeLargestEnemyArmy = maybeLargestEnemyArmy.map(army -> army
                        .withSize(army.size() * 0.999f)
                        .withThreat(army.threat() * 0.999f));
            }
            maybeLargestEnemyArmy = maybeLargestEnemyArmy.filter(army -> army.size() > 1.0);
            if (clusters.size() > 0) {
                int biggestArmySize = Integer.MIN_VALUE;
                this.enemyClusters = new HashMap();
                Point biggestArmy = null;
                for (Map.Entry<Point, List<UnitInPool>> entry : clusters.entrySet()) {
                    Point point = entry.getKey();
                    List<UnitInPool> units = entry.getValue();
                    Collection<UnitType> composition = getComposition(units);
                    double threat = threatCalculator.calculateThreat(composition);
                    enemyClusters.put(point, ImmutableArmy.builder()
                            .position(point.toPoint2d())
                            .size(units.size())
                            .composition(composition)
                            .threat(threat)
                            .build());
                    int size = units.size();
                    if (size > biggestArmySize) {
                        biggestArmySize = size;
                        biggestArmy = point;
                    }
                }
                if (biggestArmy != null) {
                    if (this.maybeLargestEnemyArmy.isEmpty() || biggestArmySize > this.maybeLargestEnemyArmy.get().size()) {
                        this.maybeLargestEnemyArmy = Optional.of(enemyClusters.get(biggestArmy));
                    }
                }
            }
        }

        lockedScoutTarget = lockedScoutTarget.filter(point2d ->
                agent.observation().getVisibility(point2d) != Visibility.VISIBLE);

        this.maybeEnemyPositionNearEnemy = findEnemyPosition(agent.observation(), true);
        this.maybeEnemyPositionNearBase = findEnemyPosition(agent.observation(), false);
    }

    private void updateRegionData(AgentData data, S2Agent agent) {
        long gameLoop = agent.observation().getGameLoop();
        if (data.mapAnalysis().isPresent() && gameLoop > regionDataCalculatedAt + 33L) {
            regionDataCalculatedAt = gameLoop;
            AnalysisResults analysisResults = data.mapAnalysis().get();

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
                    if (tile.regionId > 0) {
                        if (isEnemy) {
                            regionIdToEnemyUnits.put(tile.regionId, unit);
                        } else if (isSelf) {
                            regionIdToSelfUnits.put(tile.regionId, unit);
                        }
                    }
                });
            });
            double maxEnemyThreat = 0;
            Map<Integer, Double> regionToEnemyThreat = new HashMap<>();
            Map<Integer, Double> regionToVisibility = new HashMap<>();
            Map<Integer, Double> regionToDecayingVisibility = new HashMap<>();

            for (Region region : analysisResults.getRegions()) {
                Optional<RegionData> previousData = Optional.ofNullable(regionData.get(region.regionId()));
                // Calculation and decay of region visibility.
                double currentVisibility = calculateVisibilityOfRegion(agent.observation(), region, 0.33);
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
                if (threatValue > maxEnemyThreat) {
                    maxEnemyThreat = threatValue;
                }
            }

            double finalMaxEnemyThreat = maxEnemyThreat;
            analysisResults.getRegions().forEach(region -> {
                double currentSelfThreat = threatCalculator.calculatePower(
                        regionIdToSelfUnits.get(region.regionId()).stream()
                                .map(unitInPool -> unitInPool.unit().getType())
                                .collect(Collectors.toUnmodifiableList()));
                double enemyThreat = regionToEnemyThreat.get(region.regionId());
                // Killzone threat is the sum of threat on regions on high ground of this one.
                Optional<Double> killzoneThreat = region.onLowGroundOfRegions().stream().map(highGroundRegionId ->
                   regionToEnemyThreat.get(highGroundRegionId)
                ).reduce(Double::sum);
                // Diffuse threat is the sum of the threat of all other regions, modulated by the distance to those
                // regions. To be honest there's probably a JGraphT algorithm for this which would be better.
                Optional<Double> diffuseThreat = getDiffuseEnemyThreatForRegion(
                        analysisResults,
                        regionToEnemyThreat,
                        region);
                // For ramps only, detect if they are blocked.
                boolean isRampAndBlocked = false;
                if (mapAnalysisResults.isPresent() && region.getRampId().isPresent()) {
                    isRampAndBlocked = mapAnalysisResults.map(analysis -> analysis
                            .getRamp(region.getRampId().get()).calculateIsBlocked(agent.observation())).orElse(false);
                }
                double visibilityPercent = regionToVisibility.get(region.regionId());
                double decayingVisibilityPercent = regionToDecayingVisibility.get(region.regionId());
                double enemyArmyFactor = 1.0f + enemyThreat / Math.max(1.0, finalMaxEnemyThreat);
                double killzoneFactor = 1.0f + killzoneThreat.map(threat -> threat / Math.max(1.0, finalMaxEnemyThreat/2)).orElse(0.0);

                ImmutableRegionData.Builder builder = ImmutableRegionData.builder()
                        .region(region)
                        .weight(1.0) // TODO what to do with this?
                        .isBlocked(isRampAndBlocked)
                        .enemyArmyFactor(enemyArmyFactor)
                        .killzoneFactor(killzoneFactor)
                        .enemyThreat(enemyThreat)
                        .playerThreat(currentSelfThreat)
                        .visibilityPercent(visibilityPercent)
                        .decayingVisibilityPercent(decayingVisibilityPercent)
                        .diffuseEnemyThreat(diffuseThreat.orElse(0.0));

               regionData.put(region.regionId(), builder.build());
            });

            normalGraph = Optional.of(GraphUtils.createGraph(analysisResults, regionData,
                    (sourceRegion, destinationRegion) -> destinationRegion.weight()));

            // Edges are weighted by the diffuse enemy threat.
            avoidArmyGraph = Optional.of(GraphUtils.createGraph(analysisResults, regionData,
                    (sourceRegion, destinationRegion) -> destinationRegion.diffuseEnemyThreat()));

            avoidKillzoneGraph = Optional.of(GraphUtils.createGraph(analysisResults, regionData,
                    (sourceRegion, destinationRegion) -> destinationRegion.killzoneFactor() < 10.0f ? destinationRegion.killzoneFactor() : null));
        }
    }

    private Optional<Double> getDiffuseEnemyThreatForRegion(AnalysisResults analysisResults,
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
            double distanceFactor = 5.0 / Math.max(1.0, distance);
            diffuseThreat += regionToEnemyThreat.getOrDefault(head.regionId(), 0.0) * distanceFactor;
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
                    if (observation.getVisibility(point) == Visibility.VISIBLE) {
                        visibleTiles++;
                    }
                    ++sampledTiles;
                }
            }
        }
        return visibleTiles / Math.max(1.0, sampledTiles);
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

    private Collection<UnitType> getComposition(List<UnitInPool> unitInPools) {
        return unitInPools.stream().map(unitInPool -> unitInPool.unit().getType()).collect(Collectors.toList());
    }

    private void updateMyDefendableStructures(AgentData data, ObservationInterface observation) {
        long gameLoop = observation.getGameLoop();
        if (gameLoop > myDefendableStructuresCalculatedAt + 22L * 4) {
            myDefendableStructures.clear();
            List<Unit> structures = observation.getUnits(Alliance.SELF, unitInPool ->
                data.gameData().isStructure(unitInPool.unit().getType())
            ).stream().map(unitInPool -> unitInPool.unit()).collect(Collectors.toList());
            myDefendableStructures.addAll(structures);
        }
    }

    @Override
    public Optional<Point2d> getMaybeEnemyPositionNearEnemy() {
        return maybeEnemyPositionNearEnemy;
    }

    @Override
    public Optional<Point2d> getMaybeEnemyPositionNearBase() {
        return maybeEnemyPositionNearBase;
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
    public Optional<Army> getMaybeEnemyArmy(Point2d point2d) {
        Point closest = null;
        double closestDistance = Float.MAX_VALUE;
        for (Map.Entry<Point, ImmutableArmy> entry : this.enemyClusters.entrySet()) {
            Point point = entry.getKey();
            ImmutableArmy army = entry.getValue();
            double distance = point2d.distance(point.toPoint2d());
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = point;
            }
        }
        return Optional.ofNullable(this.enemyClusters.get(closest));
    }

    @Override
    public Optional<Army> getLargestEnemyArmy() {
        return maybeLargestEnemyArmy.map(Function.identity());
    }

    private void updateValidExpansions(ObservationInterface observationInterface, QueryInterface queryInterface) {
        long gameLoop = observationInterface.getGameLoop();
        if (this.expansionLocations.isPresent() && gameLoop > expansionsValidatedAt + 44L) {
            expansionsValidatedAt = gameLoop;
            // ExpansionLocations is ordered by distance to start point.
            this.validExpansionLocations = new LinkedHashSet<>();
            List<UnitInPool> minerals = observationInterface.getUnits(UnitFilter.builder()
                    .alliance(Alliance.NEUTRAL)
                    .unitTypes(Constants.MINERAL_TYPES).build());
            for (Expansion expansion : this.expansionLocations.get()) {
                if (!observationInterface.isPlacable(expansion.position().toPoint2d())) {
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
                    if (gameLoop > expansionLastAttempted.getOrDefault(expansion, 0L) + (15 * 22L)) {
                        this.validExpansionLocations.add(expansion);
                    }
                }
            }
        }
    }

    // Finds a worthwhile enemy position to move units towards.
    private Optional<Point2d> findEnemyPosition(ObservationInterface observationInterface, boolean nearEnemyBase) {
        Point startLocation = observationInterface.getStartLocation();
        Comparator<UnitInPool> comparator =
                Comparator.comparing(unit -> unit.unit().getPosition().distance(startLocation));
        if (nearEnemyBase && knownEnemyStartLocation.isPresent()) {
            comparator =
                    Comparator.comparing(unit -> unit.unit().getPosition().toPoint2d().distance(knownEnemyStartLocation.get()));
        }
        List<UnitInPool> enemyUnits = observationInterface.getUnits(Alliance.ENEMY);
        if (enemyUnits.size() > 0) {
            // Move towards the closest to our base (for now)
            return enemyUnits.stream()
                    .filter(unitInPool -> unitInPool.unit().getType() != Units.ZERG_LARVA)
                    .min(comparator)
                    .map(minUnit -> minUnit.unit().getPosition().toPoint2d())
                    .or(() -> findRandomEnemyPosition());
        } else {
            return findRandomEnemyPosition();
        }
    }

    private Optional<Point2d> lockedScoutTarget = Optional.empty();

    // Tries to find a random location that can be pathed to on the map.
    // Returns Point2d if a new, random location has been found that is pathable by the unit.
    private Optional<Point2d> findRandomEnemyPosition() {
        if (lockedScoutTarget.isEmpty()) {
            if (unscoutedLocations.size() > 0) {
                lockedScoutTarget = Optional.of(new ArrayList<>(unscoutedLocations)
                        .get(ThreadLocalRandom.current().nextInt(unscoutedLocations.size())));
            } else {
                return Optional.empty();
            }
        }
        return lockedScoutTarget;
    }

    private void manageScouting(
            AgentData data,
            ObservationInterface observationInterface,
            ActionInterface actionInterface,
            QueryInterface queryInterface) {

        if (knownEnemyStartLocation.isEmpty()) {
            Optional<Point2d> randomEnemyPosition = findRandomEnemyPosition();
            observationInterface.getGameInfo().getStartRaw().ifPresent(startRaw -> {
                // Note: startRaw.getStartLocations() is actually potential `enemy` locations.
                // If there's only one enemy location, the opponent is there.
                Set<Point2d> enemyStartLocations = startRaw.getStartLocations();
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

        //debug().debugIgnoreMineral();

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
            }
        }
        if (observationInterface.getGameLoop() > scoutResetLoopTime) {
            observationInterface.getGameInfo().getStartRaw().ifPresent(startRaw -> {
                unscoutedLocations = new HashSet<>(startRaw.getStartLocations());
            });
            expansionLocations.ifPresent(locations ->
                    locations.forEach(expansion -> unscoutedLocations.add(expansion.position().toPoint2d())));
            scoutResetLoopTime = observationInterface.getGameLoop() + 22 * 180;
        }
        if (unscoutedLocations.size() > 0) {
            unscoutedLocations = unscoutedLocations.stream()
                    .filter(point -> observationInterface.getVisibility(point) != Visibility.VISIBLE)
                    .collect(Collectors.toSet());
        }
    }

    @Override
    public void debug(S2Agent agent) {
        this.enemyClusters.forEach((point, units) -> {
            agent.debug().debugSphereOut(point, units.size(), Color.RED);
        });
        maybeLargestEnemyArmy.ifPresent(army -> {
            float z = agent.observation().terrainHeight(army.position());
            Point point = Point.of(army.position().getX(), army.position().getY(), z);
            agent.debug().debugSphereOut(point, army.size(), Color.RED);
            agent.debug().debugTextOut("[" + army.size() + ", " + army.threat() + "]", point, Color.WHITE, 10);
        });
    }

    @Override
    public Optional<Point2d> getNextScoutTarget() {
        return findRandomEnemyPosition();
    }

    @Override
    public void setMapAnalysisResults(AnalysisResults analysis) {
        this.mapAnalysisResults = Optional.of(analysis);
    }
}
