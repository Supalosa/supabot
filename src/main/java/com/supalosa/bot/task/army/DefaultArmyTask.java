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
import com.supalosa.bot.pathfinding.RegionGraphPath;
import com.supalosa.bot.task.*;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public abstract class DefaultArmyTask extends DefaultTaskWithUnits implements ArmyTask {

    private static final long NORMAL_UPDATE_INTERVAL = 5;
    private static final long FAST_UPDATE_INTERVAL = 2;
    protected final String armyName;
    protected final Map<Tag, Float> rememberedUnitHealth = new HashMap<>();
    protected final ThreatCalculator threatCalculator;
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
    protected MapAwareness.PathRules pathRules = MapAwareness.PathRules.AVOID_KILL_ZONE;
    // These are used for observing if we're winning or losing a fight.
    protected Optional<Army> previousEnemyArmyObservation = Optional.empty();
    protected Map<UnitType, Integer> previousComposition = currentComposition;
    private FightPerformance currentFightPerformance = FightPerformance.STABLE;
    private AggressionLevel aggressionLevel = AggressionLevel.BALANCED;
    private double cumulativeThreatDelta = 0.0;
    private double cumulativePowerDelta = 0.0;
    private long cumulativeThreatAndPowerCalculatedAt = 0L;
    public DefaultArmyTask(String armyName, int basePriority, ThreatCalculator threatCalculator) {
        super(basePriority);
        this.armyName = armyName;
        this.threatCalculator = threatCalculator;
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
        super.onStep(taskManager, data, agent);
        List<Point2d> armyPositions = new ArrayList<>();
        for (Tag tag : armyUnits) {
            UnitInPool unit = agent.observation().getUnit(tag);
            if (unit != null) {
                armyPositions.add(unit.unit().getPosition().toPoint2d());
            }
        }

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
            Optional<RegionGraphPath> maybePath = data
                    .mapAwareness()
                    .generatePath(currentRegion.get(), destinationRegion.get(), pathRules);
            maybePath.ifPresent(path -> {
                regionWaypoints = new ArrayList<>(path.getPath());
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
        Optional<RegionData> currentRegionData = centreOfMass.flatMap(centre -> data.mapAwareness().getRegionDataForPoint(centre));
        currentRegion = currentRegionData.map(RegionData::region);
        if (currentRegion.isPresent() && regionWaypoints.size() > 0 && (
                currentRegion.get().equals(regionWaypoints.get(0)) ||
                        (centreOfMass.isPresent() && regionWaypoints.get(0).centrePoint().distance(centreOfMass.get()) < 7.5f))
                && shouldMoveFromRegion(currentRegionData.get(), regionWaypoints)) {
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
     * Override this to decide if we should stay in the current region or not.
     */
    protected boolean shouldMoveFromRegion(RegionData current, List<Region> waypoints) {
        if (aggressionState == AggressionState.ATTACKING) {
            // Always stay if there's an enemy base here.
            return current.hasEnemyBase() == false;
        } else {
            return true;
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
     *
     * @return
     */
    private long armyLogicUpdate(AgentData data, S2Agent agent, List<Point2d> armyPositions) {
        OptionalDouble averageX = armyPositions.stream().mapToDouble(point -> point.getX()).average();
        OptionalDouble averageY = armyPositions.stream().mapToDouble(point -> point.getY()).average();
        centreOfMass = Optional.empty();
        if (averageX.isPresent() && averageY.isPresent()) {
            centreOfMass = Optional.of(Point2d.of((float) averageX.getAsDouble(), (float) averageY.getAsDouble()));
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
        // This is the point where the army should 'move' to between stutter step commands.
        final Optional<Point2d> suggestedRetreatMovePosition = (getFightPerformance() == FightPerformance.WINNING) ?
                suggestedAttackMovePosition :
                retreatPosition;
        Optional<Army> enemyArmy = centreOfMass.flatMap(point2d -> data.enemyAwareness().getMaybeEnemyArmy(point2d));
        switch (aggressionState) {
            case ATTACKING:
            default:
                aggressionState = attackCommand(
                        agent,
                        data,
                        centreOfMass,
                        suggestedAttackMovePosition,
                        suggestedRetreatMovePosition,
                        enemyArmy);
                break;
            case REGROUPING:
                aggressionState = regroupCommand(
                        agent,
                        data,
                        centreOfMass,
                        suggestedAttackMovePosition,
                        suggestedRetreatMovePosition,
                        enemyArmy);
                break;
            case RETREATING:
                aggressionState = retreatCommand(
                        agent,
                        data,
                        centreOfMass,
                        suggestedAttackMovePosition,
                        suggestedRetreatMovePosition,
                        enemyArmy);
        }
        // Overrides for the value returned by the respective command.
        // Retreat -> Regroup.
        // Analyse our performance in the fight and decide what to do next.
        currentFightPerformance = calculateFightPerformance(
                agent.observation().getGameLoop(),
                previousEnemyArmyObservation,
                enemyArmy,
                previousComposition,
                currentComposition);
        boolean shouldRetreat = (currentFightPerformance == FightPerformance.BADLY_LOSING);
        boolean isWinning = (currentFightPerformance == FightPerformance.WINNING);
        boolean shouldRegroup = shouldRegroup(agent.observation());
        switch (aggressionLevel) {
            case BALANCED:
                if (shouldRetreat && aggressionState != AggressionState.RETREATING) {
                    // System.out.println(armyName + ": Forced Retreat (Losing)");
                    aggressionState = AggressionState.RETREATING;
                } else if (shouldRegroup && aggressionState != AggressionState.REGROUPING) {
                    // System.out.println(armyName + ": Forced Regroup (Dispersed)");
                    aggressionState = AggressionState.REGROUPING;
                }
                break;
            case FULL_AGGRESSION:
                // never retreat or regroup!
                break;
            case FULL_RETREAT:
                if (shouldRetreat && aggressionState != AggressionState.RETREATING) {
                    // System.out.println(armyName + ": Forced Retreat (Losing)");
                    aggressionState = AggressionState.RETREATING;
                } else if (!isWinning && aggressionState != AggressionState.RETREATING) {
                    // System.out.println(armyName + ": Forced Retreat (Not winning)");
                    aggressionState = AggressionState.RETREATING;
                } else if (shouldRegroup && aggressionState != AggressionState.REGROUPING) {
                    // System.out.println(armyName + ": Forced Regroup (Dispersed)");
                    aggressionState = AggressionState.REGROUPING;
                }
                break;
        }
        previousEnemyArmyObservation = enemyArmy;
        previousComposition = new HashMap<>(currentComposition);
        // If the enemy army is near us, update more frequently.
        if (enemyArmy.flatMap(Army::position).isPresent() &&
                centreOfMass.isPresent() &&
                enemyArmy.flatMap(Army::position).get().distance(centreOfMass.get()) < 20.0) {
            return FAST_UPDATE_INTERVAL;
        }
        return NORMAL_UPDATE_INTERVAL;
    }

    protected FightPerformance getFightPerformance() {
        return currentFightPerformance;
    }

    /**
     * Returns how we are performing in the fight.
     */
    protected FightPerformance calculateFightPerformance(
            long gameLoop,
            Optional<Army> previousEnemyObservation,
            Optional<Army> currentEnemyObservation,
            Map<UnitType, Integer> previousComposition,
            Map<UnitType, Integer> currentComposition) {
        double currentEnemyThreat = currentEnemyObservation.map(Army::threat).orElse(0.0);
        double threatDelta = currentEnemyThreat -
                previousEnemyObservation.map(Army::threat).orElse(0.0);;
        double currentPower = threatCalculator.calculatePower(currentComposition);
        double powerDelta = currentPower -
                threatCalculator.calculatePower(previousComposition);
        if (Math.abs(cumulativeThreatDelta) < 0.5) {
            cumulativeThreatDelta = 0.0;
        }
        if (Math.abs(cumulativePowerDelta) < 0.5) {
            cumulativePowerDelta = 0.0;
        }
        double decayFactor =
                1.0 - 0.1 * (double) (gameLoop - cumulativeThreatAndPowerCalculatedAt) / (double) NORMAL_UPDATE_INTERVAL;
        cumulativeThreatDelta = (cumulativeThreatDelta * decayFactor) + threatDelta;
        cumulativePowerDelta = (cumulativePowerDelta * decayFactor) + powerDelta;
        cumulativeThreatAndPowerCalculatedAt = gameLoop;
        double relativeDelta = cumulativePowerDelta - cumulativeThreatDelta;
        if (currentPower > currentEnemyThreat && relativeDelta > 0) {
            if (currentFightPerformance != FightPerformance.WINNING) {
                // System.out.println(armyName + " is Winning [ourDelta: " + cumulativePowerDelta + ", theirDelta: " + cumulativeThreatDelta + "]");
            }
            return FightPerformance.WINNING;
        } else if (currentEnemyThreat > currentPower && relativeDelta < -(currentPower)) {
            if (currentFightPerformance != FightPerformance.BADLY_LOSING) {
                // System.out.println(armyName + " is Badly Losing [ourDelta: " + cumulativePowerDelta + ", theirDelta: "
                //        + cumulativeThreatDelta + "]");
            }
            return FightPerformance.BADLY_LOSING;
        } else if (currentEnemyThreat > currentPower && relativeDelta < 0) {
            if (currentFightPerformance != FightPerformance.BADLY_LOSING) {
                // System.out.println(armyName + " is Slightly Losing [ourDelta: " + cumulativePowerDelta + ", " +
                //        "theirDelta: " + cumulativeThreatDelta + "]");
            }
            return FightPerformance.SLIGHTLY_LOSING;
        } else {
            if (currentFightPerformance != FightPerformance.STABLE) {
                // System.out.println(armyName + " is Stable [ourDelta: " + cumulativePowerDelta + ", theirDelta: " + cumulativeThreatDelta + "]");
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
    protected AggressionState attackCommand(S2Agent agent,
                                            AgentData data,
                                            Optional<Point2d> centreOfMass,
                                            Optional<Point2d> suggestedAttackMovePosition,
                                            Optional<Point2d> suggestedRetreatMovePosition,
                                            Optional<Army> maybeEnemyArmy) {
        if (armyUnits.size() == 0) {
            return AggressionState.REGROUPING;
        } else {
            if (aggressionLevel != AggressionLevel.FULL_AGGRESSION && maybeEnemyArmy.isPresent() &&
                    predictFightAgainst(maybeEnemyArmy.get()) == FightPerformance.BADLY_LOSING) {
                return AggressionState.RETREATING;
            } else {
                return AggressionState.ATTACKING;
            }
        }
    }

    protected AggressionState regroupCommand(S2Agent agent,
                                             AgentData data,
                                             Optional<Point2d> centreOfMass,
                                             Optional<Point2d> suggestedAttackMovePosition,
                                             Optional<Point2d> suggestedRetreatMovePosition,
                                             Optional<Army> maybeEnemyArmy) {
        ObservationInterface observationInterface = agent.observation();
        ActionInterface actionInterface = agent.actions();
        if (armyUnits.size() > 0 && (centreOfMass.isEmpty() || !shouldRegroup(observationInterface))) {
            // System.out.println(armyName + " Regroup -> Attack");
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

    protected AggressionState retreatCommand(S2Agent agent,
                                             AgentData data,
                                             Optional<Point2d> centreOfMass,
                                             Optional<Point2d> suggestedAttackMovePosition,
                                             Optional<Point2d> suggestedRetreatMovePosition,
                                             Optional<Army> maybeEnemyArmy) {
        ObservationInterface observationInterface = agent.observation();
        ActionInterface actionInterface = agent.actions();
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
            if (aggressionLevel == AggressionLevel.FULL_RETREAT || currentFightPerformance == FightPerformance.BADLY_LOSING) {
                // Fully run away
                retreatPosition.ifPresent(point2d ->
                        actionInterface.unitCommand(armyUnits, Abilities.MOVE, finalRetreatPoint2d, false));
            } else {
                // Stutter step away from the enemy if we're not badly losing.
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
        if (maybeEnemyArmy.isEmpty() || predictFightAgainst(maybeEnemyArmy.get()) == FightPerformance.WINNING) {
            // System.out.println(armyName + " Retreat -> Attack");
            return AggressionState.ATTACKING;
        } else {
            return AggressionState.RETREATING;
        }
    }

    @Override
    public void setPathRules(MapAwareness.PathRules pathRules) {
        this.pathRules = pathRules;
    }

    @Override
    public Optional<TaskResult> getResult() {
        return Optional.empty();
    }

    @Override
    public String getKey() {
        return "Army." + armyName;
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

    @Override
    public void setTargetPosition(Optional<Point2d> targetPosition) {
        this.targetPosition = targetPosition;
    }

    public void setAggressionLevel(AggressionLevel aggressionLevel) {
        this.aggressionLevel = aggressionLevel;
    }

    @Override
    public FightPerformance predictFightAgainst(Army army) {
        double currentEnemyThreat = army.threat();
        double currentPower = threatCalculator.calculatePower(currentComposition);
        if (currentPower > currentEnemyThreat * 1.5) {
            return FightPerformance.WINNING;
        } else if (currentPower > currentEnemyThreat * 1.25) {
            return FightPerformance.STABLE;
        } else if (currentPower > currentEnemyThreat * 0.75) {
            return FightPerformance.SLIGHTLY_LOSING;
        } else {
            return FightPerformance.BADLY_LOSING;
        }
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
    }

    /**
     * Defines how this army will handle engagements.
     */
    public enum AggressionLevel {
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
}
