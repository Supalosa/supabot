package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.engagement.ThreatCalculator;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.TaskResult;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public abstract class AbstractDefaultArmyTask implements ArmyTask {

    private FightPerformance currentFightPerformance = FightPerformance.STABLE;

    /**
     * Defines how this army will handle engagements.
     */
    enum AggressionLevel {
        // Full aggression towards the enemy. Never retreats.
        FULL_AGGRESSION,

        // Holding ground in a fight. Retreat if losing.
        BALANCED,

        // Retreat without trying to fight back.
        FULL_RETREAT,
    }

    enum AggressionState {
        ATTACKING,
        REGROUPING,
        RETREATING
    }

    protected final String armyName;
    protected final Map<Tag, Float> rememberedUnitHealth = new HashMap<>();
    protected Set<Tag> armyUnits = new HashSet<>();
    protected Optional<Point2d> targetPosition = Optional.empty();
    protected Optional<Point2d> retreatPosition = Optional.empty();
    protected Optional<Point2d> centreOfMass = Optional.empty();
    protected AggressionState aggressionState = AggressionState.REGROUPING;
    protected Optional<Region> currentRegion = Optional.empty();
    protected Optional<Region> targetRegion = Optional.empty();
    protected Optional<Region> retreatRegion = Optional.empty();
    protected List<Region> regionWaypoints = new ArrayList<>();
    protected Optional<Region> waypointsCalculatedFrom = Optional.empty();
    protected Optional<Region> waypointsCalculatedTo = Optional.empty();
    protected long waypointsCalculatedAt = 0L;
    protected long nextArmyLogicUpdateAt = 0L;
    protected Map<UnitType, Integer> currentComposition = new HashMap<>();
    protected MapAwareness.PathRules pathRules = MapAwareness.PathRules.AVOID_KILL_ZONE;
    // These are used for observing if we're winning or losing a fight.
    protected Optional<Army> previousEnemyArmyObservation = Optional.empty();
    protected Map<UnitType, Integer> previousComposition = currentComposition;
    private AggressionLevel aggressionLevel = AggressionLevel.BALANCED;

    private double cumulativeThreatDelta = 0.0;
    private double cumulativePowerDelta = 0.0;
    private long cumulativeThreatAndPowerCalculatedAt = 0L;

    private static final long NORMAL_UPDATE_INTERVAL = 11;

    protected final ThreatCalculator threatCalculator;

    public AbstractDefaultArmyTask(String armyName, ThreatCalculator threatCalculator) {
        this.armyName = armyName;
        this.threatCalculator = threatCalculator;
    }

    @Override
    public void setTargetPosition(Optional<Point2d> targetPosition) {
        this.targetPosition = targetPosition;
    }

    @Override
    public void setRetreatPosition(Optional<Point2d> retreatPosition) {
        this.retreatPosition = retreatPosition;
    }

    @Override
    public int getSize() {
        return this.armyUnits.size();
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        List<Point2d> armyPositions = new ArrayList<>();
        currentComposition.clear();
        armyUnits = armyUnits.stream().filter(tag -> {
                    UnitInPool unit = agent.observation().getUnit(tag);
                    if (unit != null) {
                        armyPositions.add(unit.unit().getPosition().toPoint2d());
                        currentComposition.put(
                                unit.unit().getType(),
                                currentComposition.getOrDefault(unit.unit().getType(), 0) + 1);
                    }
                    return (unit != null && unit.isAlive());
                })
                .collect(Collectors.toSet());

        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > nextArmyLogicUpdateAt) {
            nextArmyLogicUpdateAt = gameLoop + armyLogicUpdate(data, agent, armyPositions);
        }

        // Handle pathfinding.
        updateCurrentRegions(data);

        if (gameLoop > waypointsCalculatedAt + 44L) {
            waypointsCalculatedAt = gameLoop;
            calculateNewPath(data);
        }
    }

    protected int getAmountOfUnit(UnitType type) {
        return currentComposition.getOrDefault(type, 0);
    }

    /**
     * Default implementation of wantsUnit that looks at the current and desired composition.
     */
    @Override
    public boolean wantsUnit(Unit unit) {
        return requestingUnitTypes().stream().anyMatch(request ->
                request.unitType().equals(unit.getType()) && getAmountOfUnit(unit.getType()) < request.amount());
    }

    private void calculateNewPath(AgentData data) {
        // Target has changed, clear pathfinding.
        if (!waypointsCalculatedTo.equals(targetRegion)) {
            waypointsCalculatedTo = Optional.empty();
            regionWaypoints.clear();
        }
        // Calculate path every time. TODO: probably don't need to do this, cut down in the future.
        final Optional<Region> destinationRegion = (aggressionState == AggressionState.RETREATING) ?
                retreatRegion :
                targetRegion;
        if (currentRegion.isPresent() && destinationRegion.isPresent() && !currentRegion.equals(destinationRegion)) {
            Optional<List<Region>> maybePath = data
                    .mapAwareness()
                    .generatePath(currentRegion.get(), destinationRegion.get(), pathRules);
            maybePath.ifPresent(path -> {
                regionWaypoints = path;
                waypointsCalculatedFrom = currentRegion;
                waypointsCalculatedTo = destinationRegion;
            });
        }
    }

    private void updateCurrentRegions(AgentData data) {
        targetRegion = targetPosition.flatMap(position ->
                data.mapAwareness().getRegionDataForPoint(position).map(RegionData::region));
        retreatRegion = retreatPosition.flatMap(position ->
                data.mapAwareness().getRegionDataForPoint(position).map(RegionData::region));
        // TODO if regrouping, is current region valid? It will end up thinking the unit is in a region halfway between
        // the clumps.
        currentRegion = centreOfMass.flatMap(centre ->
                data.mapAwareness().getRegionDataForPoint(centre).map(RegionData::region));
        if (currentRegion.isPresent() && regionWaypoints.size() > 0 && (
                currentRegion.get().equals(regionWaypoints.get(0)) ||
                        (centreOfMass.isPresent() && regionWaypoints.get(0).centrePoint().distance(centreOfMass.get()) < 2.5f)
        )) {
            // Arrived at the head waypoint.
            regionWaypoints.remove(0);
            if (regionWaypoints.size() > 0) {
                waypointsCalculatedFrom = Optional.of(regionWaypoints.get(0));
            } else {
                // Finished path.
                waypointsCalculatedTo = Optional.empty();
                waypointsCalculatedFrom = Optional.empty();
            }
        }
    }

    /**
     * Returns true if this army should regroup.
     */
    protected boolean shouldRegroup(ObservationInterface observationInterface) {
        if (centreOfMass.isEmpty()) {
            return false;
        }
        Pair<List<Unit>, List<Unit>> splitUnits = calculateUnitProximityToPoint(
                observationInterface,
                10f,
                centreOfMass.get());

        List<Unit> nearUnits = splitUnits.getLeft();
        List<Unit> farUnits = splitUnits.getRight();

        if (aggressionState == AggressionState.REGROUPING) {
            if (nearUnits.size() > armyUnits.size() * 0.85) {
                // 85% of units are near the CoM, regrouping is finished
                return false;
            } else {
                return true;
            }
        } else {
            // Less than 35% of units are near the CoM, should regroup/
            if (nearUnits.size() < armyUnits.size() * 0.35) {
                return true;
            } else {
                return false;
            }
        }
    }

    private Pair<List<Unit>, List<Unit>> calculateUnitProximityToPoint(ObservationInterface observationInterface,
                                                         float searchRadius, Point2d com) {
        List<Unit> far = new ArrayList<>();
        List<Unit> near = observationInterface.getUnits(unitInPool ->
                        armyUnits.contains(unitInPool.getTag())).stream()
                .map(uip -> uip.unit())
                .filter(unit -> {
                    if (unit.getPosition().toPoint2d().distance(com) < searchRadius) {
                        return true;
                    } else {
                        far.add(unit);
                        return false;
                    }
                })
                .collect(Collectors.toList());
        Pair<List<Unit>, List<Unit>> splitUnits = Pair.of(near, far);
        return splitUnits;
    }

    /**
     * Runs the actual army logic.
     * Return how many steps until we should update again. The idea is that armies that are actually
     * in fights should get more updates.
     * @return
     */
    private long armyLogicUpdate(AgentData data, S2Agent agent, List<Point2d> armyPositions) {
        OptionalDouble averageX = armyPositions.stream().mapToDouble(point -> point.getX()).average();
        OptionalDouble averageY = armyPositions.stream().mapToDouble(point -> point.getY()).average();
        centreOfMass = Optional.empty();
        if (averageX.isPresent() && averageY.isPresent()) {
            centreOfMass = Optional.of(Point2d.of((float)averageX.getAsDouble(), (float)averageY.getAsDouble()));
        }
        // Handle pathfinding. The suggestedAttackMovePosition is the position of either the next waypoint in the
        // path, or the targetPosition.
        Optional<Point2d> suggestedAttackMovePosition = targetPosition;
        if (waypointsCalculatedTo.isPresent() && regionWaypoints.size() > 0) {
            Region head = regionWaypoints.get(0);
            if (targetRegion.isPresent() && targetRegion.get().equals(head)) {
                // Arrived; attack the target.
                suggestedAttackMovePosition = targetPosition;
            } else {
                // Attack move to centre of the next region.
                suggestedAttackMovePosition = Optional.of(head.centrePoint());
            }
        }
        Optional<Army> enemyArmy = centreOfMass.flatMap(point2d -> data.mapAwareness().getMaybeEnemyArmy(point2d));
        switch (aggressionState) {
            case ATTACKING:
            default:
                aggressionState = attackCommand(
                        agent.observation(),
                        agent.actions(),
                        centreOfMass,
                        suggestedAttackMovePosition,
                        enemyArmy);
                break;
            case REGROUPING:
                aggressionState = regroupCommand(
                        agent.observation(),
                        agent.actions(),
                        centreOfMass,
                        enemyArmy);
                break;
            case RETREATING:
                aggressionState = retreatCommand(
                        agent.observation(),
                        agent.actions(),
                        centreOfMass,
                        enemyArmy);
        }
        // Overrides for the value returned by the respective command.
        // Retreat -> Regroup.
        // Analyse our performance in the fight and decide what to do next.
        if (previousEnemyArmyObservation.isPresent() && enemyArmy.isPresent()) {
            currentFightPerformance = calculateFightPerformance(
                    agent.observation().getGameLoop(),
                    previousEnemyArmyObservation.get(),
                    enemyArmy.get(),
                    previousComposition,
                    currentComposition);
            boolean shouldRetreat = (currentFightPerformance == FightPerformance.LOSING);
            boolean isWinning = (currentFightPerformance == FightPerformance.WINNING);
            boolean shouldRegroup = shouldRegroup(agent.observation());
            switch (aggressionLevel) {
                case BALANCED:
                    if (shouldRetreat && aggressionState != AggressionState.RETREATING) {
                        System.out.println(armyName + ": Forced Retreat (Losing)");
                        aggressionState = AggressionState.RETREATING;
                    } else if (shouldRegroup && aggressionState != AggressionState.REGROUPING) {
                        System.out.println(armyName + ": Forced Regroup (Dispersed)");
                        aggressionState = AggressionState.REGROUPING;
                    }
                    break;
                case FULL_AGGRESSION:
                    // never retreat or regroup!
                    break;
                case FULL_RETREAT:
                    if (shouldRetreat && aggressionState != AggressionState.RETREATING) {
                        System.out.println(armyName + ": Forced Retreat (Losing)");
                        aggressionState = AggressionState.RETREATING;
                    } else if (!isWinning && aggressionState != AggressionState.RETREATING) {
                        System.out.println(armyName + ": Forced Retreat (Not winning)");
                        aggressionState = AggressionState.RETREATING;
                    } else if (shouldRegroup && aggressionState != AggressionState.REGROUPING) {
                        System.out.println(armyName + ": Forced Regroup (Dispersed)");
                        aggressionState = AggressionState.REGROUPING;
                    }
                    break;
            }
        }
        previousEnemyArmyObservation = enemyArmy;
        previousComposition = new HashMap<>(currentComposition);
        // If the enemy army is near us, update more frequently.
        if (enemyArmy.isPresent() &&
                centreOfMass.isPresent() &&
                enemyArmy.get().position().distance(centreOfMass.get()) < 20.0) {
            return 11;
        }
        return NORMAL_UPDATE_INTERVAL;
    }

    protected enum FightPerformance {
        WINNING,
        STABLE,
        LOSING
    }

    protected FightPerformance getFightPerformance() {
        return currentFightPerformance;
    }

    /**
     * Returns how we are performing in the fight.
     */
    protected FightPerformance calculateFightPerformance(
            long gameLoop,
            Army previousEnemyObservation,
            Army currentEnemyObservation,
            Map<UnitType, Integer> previousComposition,
            Map<UnitType, Integer> currentComposition) {
        double currentEnemyThreat = currentEnemyObservation.threat();
        double threatDelta = currentEnemyThreat -
                previousEnemyObservation.threat();
        double currentPower = threatCalculator.calculatePower(currentComposition);
        double powerDelta = currentPower -
                threatCalculator.calculatePower(previousComposition);
        if (Math.abs(cumulativeThreatDelta) < 0.5) {
            cumulativeThreatDelta = 0.0;
        }
        if (Math.abs(cumulativePowerDelta) < 0.5) {
            cumulativePowerDelta = 0.0;
        }
        double decayFactor = 1.0 - 0.1 * (double)(gameLoop - cumulativeThreatAndPowerCalculatedAt) / (double)NORMAL_UPDATE_INTERVAL;
        cumulativeThreatDelta = (cumulativeThreatDelta * decayFactor) + threatDelta;
        cumulativePowerDelta = (cumulativePowerDelta * decayFactor) + powerDelta;
        cumulativeThreatAndPowerCalculatedAt = gameLoop;
        double relativeDelta = cumulativePowerDelta - cumulativeThreatDelta;
        if (currentPower > currentEnemyThreat && relativeDelta > 0) {
            if (currentFightPerformance != FightPerformance.WINNING) {
                System.out.println(armyName + " is Winning [ourDelta: " + cumulativePowerDelta + ", theirDelta: " + cumulativeThreatDelta + "]");
            }
            return FightPerformance.WINNING;
        } else if (currentEnemyThreat > currentPower && relativeDelta < 0) {
            if (currentFightPerformance != FightPerformance.LOSING) {
                System.out.println(armyName + " is Losing [ourDelta: " + cumulativePowerDelta + ", theirDelta: " + cumulativeThreatDelta + "]");
            }
            return FightPerformance.LOSING;
        } else {
            if (currentFightPerformance != FightPerformance.STABLE) {
                System.out.println(armyName + " is Stable [ourDelta: " + cumulativePowerDelta + ", theirDelta: " + cumulativeThreatDelta + "]");
            }
            return FightPerformance.STABLE;
        }
    }

    /**
     * Override this to handle how the army's units handle being told to attack or move.
     * It's basically a periodic 'update' command. Maybe I should rename it.
     *
     * @param suggestedAttackMovePosition The position of either the next waypoint in the path, or the targetPosition.
     * @return he state that the army should be in (aggressive, regroup, retreat etc).
     */
    protected AggressionState attackCommand(ObservationInterface observationInterface,
                                          ActionInterface actionInterface,
                                          Optional<Point2d> centreOfMass,
                                          Optional<Point2d> suggestedAttackMovePosition,
                                          Optional<Army> maybeEnemyArmy) {
        if (armyUnits.size() == 0) {
            return AggressionState.REGROUPING;
        } else {
            return AggressionState.ATTACKING;
        }
    }

    protected AggressionState regroupCommand(ObservationInterface observationInterface,
                                             ActionInterface actionInterface,
                                             Optional<Point2d> centreOfMass,
                                             Optional<Army> maybeEnemyArmy) {
        if (armyUnits.size() > 0 && (centreOfMass.isEmpty() || !shouldRegroup(observationInterface))) {
            System.out.println(armyName + " Regroup -> Attack");
            return AggressionState.ATTACKING;
        } else if (armyUnits.size() > 0) {
            Pair<List<Unit>, List<Unit>> splitUnits = calculateUnitProximityToPoint(
                    observationInterface,
                    aggressionState == AggressionState.REGROUPING ? 5f : 10f,
                    centreOfMass.get());

            List<Unit> nearUnits = splitUnits.getLeft();
            List<Unit> farUnits = splitUnits.getRight();
            // Units far away from the centre of mass should run there.
            if (farUnits.size() > 0) {
                // Bit of a hack but sometimes attacking and moving helps
                Ability ability = ThreadLocalRandom.current().nextBoolean() ? Abilities.ATTACK : Abilities.MOVE;
                centreOfMass.ifPresent(point2d ->
                        actionInterface.unitCommand(farUnits, ability, point2d, false));
            }
            // Units near the centre of mass can attack move.
            if (nearUnits.size() > 0) {
                targetPosition.ifPresent(point2d ->
                        actionInterface.unitCommand(nearUnits, Abilities.ATTACK, point2d, false));
            }
        }
        return AggressionState.REGROUPING;
    }

    protected AggressionState retreatCommand(ObservationInterface observationInterface,
                                             ActionInterface actionInterface,
                                             Optional<Point2d> centreOfMass,
                                             Optional<Army> maybeEnemyArmy) {
        if (!armyUnits.isEmpty() && retreatPosition.isPresent()) {
            Point2d retreatPoint2d = retreatPosition.get();
            // If we've plotted a path to the retreat region, start retreating in the direction of that path.
            if (waypointsCalculatedTo.isPresent() &&
                    waypointsCalculatedTo.equals(retreatRegion) &&
                    regionWaypoints.size() > 0) {
                Region head = regionWaypoints.get(0);
                retreatPoint2d = head.centrePoint();
            }
            Point2d finalRetreatPoint2d = retreatPoint2d;
            if (aggressionLevel == AggressionLevel.FULL_RETREAT) {
                // Fully run away
                retreatPosition.ifPresent(point2d ->
                        actionInterface.unitCommand(armyUnits, Abilities.MOVE, finalRetreatPoint2d, false));
            } else {
                // Stutter step away from the enemy.
                armyUnits.forEach(tag -> {
                    UnitInPool unit = observationInterface.getUnit(tag);
                    if (unit != null) {
                        if (unit.unit().getWeaponCooldown().isPresent() && unit.unit().getWeaponCooldown().get() < 0.01f) {
                            actionInterface.unitCommand(tag, Abilities.ATTACK, finalRetreatPoint2d, false);
                        } else {
                            actionInterface.unitCommand(tag, Abilities.MOVE, finalRetreatPoint2d, false);
                        }
                    }
                });
            }
        }
        // Temporary logic to go back into the ATTACKING state.
        if (maybeEnemyArmy.isEmpty() || maybeEnemyArmy.get().size() * 10 < armyUnits.size()) {
            System.out.println(armyName + " Retreat -> Attack");
            return AggressionState.ATTACKING;
        } else {
            return AggressionState.RETREATING;
        }
    }

    @Override
    public boolean addUnit(Tag unitTag) {
        return armyUnits.add(unitTag);
    }

    @Override
    public boolean hasUnit(Tag unitTag) {
        return armyUnits.contains(unitTag);
    }

    @Override
    public void onUnitIdle(UnitInPool unitTag) {

    }

    @Override
    public void setPathRules(MapAwareness.PathRules pathRules) {
        this.pathRules = pathRules;
    }

    /**
     * Take all units from the other army. The other army becomes an empty army.
     */
    public void takeAllFrom(TerranBioArmyTask otherArmy) {
        if (otherArmy == this) {
            return;
        }
        this.armyUnits.addAll(otherArmy.armyUnits);
        otherArmy.armyUnits.clear();
    }

    @Override
    public Optional<TaskResult> getResult() {
        return Optional.empty();
    }

    @Override
    public String getKey() {
        return "ATTACK." + armyName;
    }

    @Override
    public String getDebugText() {
        return "Army (" + armyName + ") " + targetPosition.map(point2d -> "T").orElse("!T") + " " +
                retreatPosition.map(point2d -> "R").orElse("!R") +
                " (" + armyUnits.size() + ") - " + aggressionState + " - " + currentFightPerformance;
    }

    @Override
    public Optional<List<Region>> getWaypoints() {
        return Optional.of(this.regionWaypoints);
    }

    @Override
    public Optional<Point2d> getCentreOfMass() {
        return centreOfMass;
    }

    @Override
    public Optional<Point2d> getTargetPosition() {
        return targetPosition;
    }

    public void setAggressionLevel(AggressionLevel aggressionLevel) {
        this.aggressionLevel = aggressionLevel;
    }
}
