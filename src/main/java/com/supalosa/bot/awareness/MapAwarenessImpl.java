package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.*;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.UnitAttribute;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.Expansion;
import com.supalosa.bot.Expansions;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.NotImplementedException;
import org.checkerframework.checker.units.qual.A;

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

    private final long expansionsValidatedAt = 0L;

    // Temporary 'binary' enemy positions.
    private Optional<Point2d> maybeEnemyPositionNearEnemy = Optional.empty();
    private Optional<Point2d> maybeEnemyPositionNearBase = Optional.empty();

    private final long myDefendableStructuresCalculatedAt = 0L;
    private List<Unit> myDefendableStructures = new ArrayList<>();

    private long maybeEnemyArmyCalculatedAt = 0L;
    private Optional<ImmutableArmy> maybeEnemyArmy = Optional.empty();
    private Map<Point, List<UnitInPool>> enemyClusters = new HashMap<>();

    public MapAwarenessImpl() {
        this.startPosition = Optional.empty();
        this.knownEnemyBases = new ArrayList<>();
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

        if (agent.observation().getGameLoop() > maybeEnemyArmyCalculatedAt + 22L) {
            maybeEnemyArmyCalculatedAt = agent.observation().getGameLoop();
            List<UnitInPool> enemyArmy = agent.observation().getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.ENEMY)
                            .unitTypes(Constants.ARMY_UNIT_TYPES)
                            .build());
            this.enemyClusters = Expansions.cluster(enemyArmy, 20f);
            // Army threat decays slowly
            if (maybeEnemyArmy.isPresent() && agent.observation().getVisibility(maybeEnemyArmy.get().position()) == Visibility.VISIBLE) {
                maybeEnemyArmy = maybeEnemyArmy.map(army -> army
                        .withSize(army.size() * 0.5f - 1.0f)
                        .withThreat(army.threat() * 0.5f - 1.0f));
            } else {
                maybeEnemyArmy = maybeEnemyArmy.map(army -> army
                        .withSize(army.size() * 0.99f)
                        .withThreat(army.threat() * 0.99f));
            }
            maybeEnemyArmy = maybeEnemyArmy.filter(army -> army.size() > 1.0);
            if (this.enemyClusters.size() > 0) {
                // TODO: Threat calc instead of number of units
                int biggestArmySize = Integer.MIN_VALUE;
                Point biggestArmy = null;
                for (Map.Entry<Point, List<UnitInPool>> entry : this.enemyClusters.entrySet()) {
                    Point point = entry.getKey();
                    List<UnitInPool> units = entry.getValue();
                    int size = units.size();
                    if (size > biggestArmySize) {
                        biggestArmySize = size;
                        biggestArmy = point;
                    }
                }
                if (biggestArmy != null) {
                    if (this.maybeEnemyArmy.isEmpty() || biggestArmySize > this.maybeEnemyArmy.get().size()) {
                        Collection<UnitType> composition = getComposition(this.enemyClusters.get(biggestArmy));
                        double threat = calculateThreat(composition);
                        this.maybeEnemyArmy = Optional.of(
                                ImmutableArmy.builder()
                                        .position(biggestArmy.toPoint2d())
                                        .size(biggestArmySize)
                                        .composition(composition)
                                        .threat(threat)
                                        .build());
                    }
                }
            }
        }

        lockedScoutTarget = lockedScoutTarget.filter(point2d ->
                agent.observation().getVisibility(point2d) != Visibility.VISIBLE);

        this.maybeEnemyPositionNearEnemy = findEnemyPosition(agent.observation(), true);
        this.maybeEnemyPositionNearBase = findEnemyPosition(agent.observation(), false);
    }

    private Collection<UnitType> getComposition(List<UnitInPool> unitInPools) {
        return unitInPools.stream().map(unitInPool -> unitInPool.unit().getType()).collect(Collectors.toList());
    }

    private double calculateThreat(Collection<UnitType> composition) {
        double enemyThreat = composition.stream().mapToDouble(unitType -> {
            if (unitType instanceof Units) {
                switch ((Units)unitType) {
                    case TERRAN_SIEGE_TANK_SIEGED:
                    case TERRAN_SIEGE_TANK:
                    case ZERG_ULTRALISK:
                    case PROTOSS_MOTHERSHIP:
                    case PROTOSS_CARRIER:
                        return 10.0;
                    case TERRAN_THOR:
                    case ZERG_BROODLORD:
                    case PROTOSS_IMMORTAL:
                        return 5.0;
                    case ZERG_QUEEN:
                        return 3.0;
                    case TERRAN_MARAUDER:
                    case ZERG_ROACH:
                    case PROTOSS_ZEALOT:
                    case PROTOSS_STALKER:
                        return 2.0;
                    default:
                        return 1.5;
                }
            } else {
                return 1.5;
            }
        }).sum();
        return enemyThreat;
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
    public Optional<Army> getMaybeEnemyArmy() {
        return maybeEnemyArmy.map(Function.identity());
    }

    private void updateValidExpansions(ObservationInterface observationInterface, QueryInterface queryInterface) {
        long gameLoop = observationInterface.getGameLoop();
        if (this.expansionLocations.isPresent() && gameLoop > expansionsValidatedAt * 44L) {
            // ExpansionLocations is ordered by distance to start point.
            this.validExpansionLocations = new LinkedHashSet<>();
            for (Expansion expansion : this.expansionLocations.get()) {
                if (!observationInterface.isPlacable(expansion.position().toPoint2d())) {
                    continue;
                }
                if (queryInterface.placement(Abilities.BUILD_COMMAND_CENTER, expansion.position().toPoint2d())) {
                    if (expansionLastAttempted.getOrDefault(expansion, 0L) < gameLoop - (15 * 22L)) {
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
        maybeEnemyArmy.ifPresent(army -> {
            float z = agent.observation().terrainHeight(army.position());
            Point point = Point.of(army.position().getX(), army.position().getY(), z);
            agent.debug().debugSphereOut(point, army.size(), Color.RED);
            agent.debug().debugTextOut("[" + army.size() + ", " + army.threat() + "]", point, Color.WHITE, 10);
        });
    }
}
