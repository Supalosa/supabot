package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
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
import java.util.stream.Collectors;

public abstract class DefaultArmyTask<A,D,R,I> extends DefaultTaskWithUnits implements ArmyTask {

    private static final long NORMAL_UPDATE_INTERVAL = 11;
    private final String armyName;
    private final String armyKey;
    private final Map<Tag, Float> rememberedUnitHealth = new HashMap<>();
    private final ThreatCalculator threatCalculator;

    // This is another army task that exists, that this army will try to merge into by moving close to it.
    private Optional<DefaultArmyTask<A,D,R,I>> parentArmy = Optional.empty();
    private Optional<Point2d> targetPosition = Optional.empty();
    private Optional<Point2d> retreatPosition = Optional.empty();
    private Optional<Point2d> centreOfMass = Optional.empty();
    private Optional<Double> dispersion;

    private AggressionState aggressionState = AggressionState.REGROUPING;
    private Optional<RegionData> currentRegion = Optional.empty();
    private Optional<RegionData> nextRegion = Optional.empty();
    private Optional<RegionData> targetRegion = Optional.empty();
    private Optional<RegionData> previousRegion = Optional.empty();
    private Optional<RegionData> retreatRegion = Optional.empty();

    private List<Region> regionWaypoints = new ArrayList<>();
    private Optional<Region> waypointsCalculatedFrom = Optional.empty();
    private Optional<Region> waypointsCalculatedTo = Optional.empty();

    private long waypointsCalculatedAt = 0L;
    private long nextArmyLogicUpdateAt = 0L;
    private MapAwareness.PathRules pathRules = MapAwareness.PathRules.AVOID_KILL_ZONE;
    // These are used for observing if we're winning or losing a fight.
    private Optional<Army> previousEnemyArmyObservation = Optional.empty();
    private Map<UnitType, Integer> previousComposition = currentComposition;
    private FightPerformance currentFightPerformance = FightPerformance.STABLE;
    private AggressionLevel aggressionLevel = AggressionLevel.BALANCED;
    private double cumulativeThreatDelta = 0.0;
    private double cumulativePowerDelta = 0.0;
    private long cumulativeThreatAndPowerCalculatedAt = 0L;

    private boolean isComplete = false;

    private final DefaultArmyTaskBehaviour<A,D,R,I> behaviour;

    public DefaultArmyTask(String armyName,
                           int basePriority,
                           ThreatCalculator threatCalculator,
                           DefaultArmyTaskBehaviour<A,D,R,I> behaviour) {
        this(armyName, armyName, basePriority, threatCalculator, behaviour, Optional.empty());
    }

    public DefaultArmyTask(String armyName,
                           String armyKey,
                           int basePriority,
                           ThreatCalculator threatCalculator,
                           DefaultArmyTaskBehaviour<A,D,R,I> behaviour,
                           Optional<DefaultArmyTask<A,D,R,I>> parentArmy) {
        super(basePriority);
        this.armyName = armyName;
        this.armyKey = armyKey;
        this.threatCalculator = threatCalculator;
        this.behaviour = behaviour;
        this.parentArmy = parentArmy;
    }

    private DefaultArmyTaskBehaviourStateHandler<?> getBehaviourHandlerForState() {
        switch (this.aggressionState) {
            case ATTACKING:
                return behaviour.getAttackHandler();
            case RETREATING:
                return behaviour.getDisengagingHandler();
            case REGROUPING:
                return behaviour.getRegroupingHandler();
            case IDLE:
                return behaviour.getIdleHandler();
        }
        throw new IllegalStateException("Unsupported aggression state: " + this.aggressionState);
    }

    /**
     * This is not what it says on the tin...
     *
     * @return A new army of this type, that has its parent set to this army.
     */
    public abstract DefaultArmyTask<A,D,R,I> createChildArmy();

    public String getArmyName() {
        return this.armyName;
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
    public void onStep(TaskManager taskManager, AgentWithData agentWithData) {
        super.onStep(taskManager, agentWithData);
        List<Unit> allUnits = new ArrayList<>();
        for (Tag tag : armyUnits) {
            UnitInPool unit = agentWithData.observation().getUnit(tag);
            if (unit != null) {
                allUnits.add(unit.unit());
            }
        }

        long gameLoop = agentWithData.observation().getGameLoop();

        if (parentArmy.isPresent()) {
            if (parentArmy.get().isComplete()) {
                parentArmy = Optional.empty();
            } else {
                targetPosition = parentArmy.flatMap(army -> army.centreOfMass);

                if (centreOfMass.isPresent() && targetPosition.isPresent() && centreOfMass.get().distance(targetPosition.get()) < 5f) {
                    parentArmy.get().takeAllFrom(agentWithData.taskManager(), agentWithData.observation(), this);
                    markComplete();
                } else if (parentArmy.get().armyUnits.size() == 0) {
                    parentArmy.get().takeAllFrom(agentWithData.taskManager(), agentWithData.observation(), this);
                    markComplete();
                }
                if (armyUnits.size() == 0) {
                    markComplete();
                }
            }
        }

        if (gameLoop > nextArmyLogicUpdateAt) {
            nextArmyLogicUpdateAt = gameLoop + armyLogicUpdate(agentWithData, allUnits);
        }

        // Handle pathfinding.
        if (gameLoop > waypointsCalculatedAt + 44L) {
            waypointsCalculatedAt = gameLoop;
            calculateNewPath(agentWithData);
        }
        updateCurrentRegions(agentWithData);

    }

    protected void markComplete() {
        this.isComplete = true;
    }

    @Override
    public final boolean isComplete() {
        return this.isComplete;
    }

    private void calculateNewPath(AgentWithData agentWithData) {
        // Target has changed, clear pathfinding.
        if (!waypointsCalculatedTo.equals(targetRegion)) {
            waypointsCalculatedTo = Optional.empty();
            regionWaypoints.clear();
        }
        // Calculate path every time. TODO: probably don't need to do this, cut down in the future.
        final Optional<Region> destinationRegion = (aggressionState == AggressionState.RETREATING) ?
                retreatRegion.map(RegionData::region) :
                targetRegion.map(RegionData::region);
        if (currentRegion.isPresent() && destinationRegion.isPresent() && !currentRegion.equals(destinationRegion)) {
            Optional<RegionGraphPath> maybePath = agentWithData
                    .mapAwareness()
                    .generatePath(currentRegion.map(RegionData::region).get(), destinationRegion.get(), pathRules);
            maybePath.ifPresent(path -> {
                List<Region> regionList = path.getPath();
                regionWaypoints = new ArrayList<>(regionList);
                // TODO syntax fix here. Should be calling updateCurrentRegions immediately instead.
                // Remove the head of the path if we're already in that region.
                // Isn't the head always going to be the current region?
                if (regionList.size() > 0 &&
                        currentRegion.map(RegionData::region).map(Region::regionId).get().equals(regionList.get(0).regionId())) {
                    regionWaypoints.remove(0);
                }
                updateCurrentRegions(agentWithData);
                waypointsCalculatedFrom = currentRegion.map(RegionData::region);
                waypointsCalculatedTo = destinationRegion;
            });
        }
    }

    private void updateCurrentRegions(AgentWithData agentWithData) {
        targetRegion = targetPosition.flatMap(position ->
                agentWithData.mapAwareness().getRegionDataForPoint(position));
        retreatRegion = retreatPosition.flatMap(position ->
                agentWithData.mapAwareness().getRegionDataForPoint(position));
        // TODO if regrouping, is current region valid? It will end up thinking the unit is in a region halfway between
        // the clumps.
        Optional<RegionData> previousCurrentRegion = currentRegion;
        Optional<RegionData> currentRegionData = centreOfMass.flatMap(centre -> agentWithData.mapAwareness().getRegionDataForPoint(centre));
        currentRegion = currentRegionData;

        final boolean shouldMoveFromRegion = currentRegion
                .filter(region -> region.equals(regionWaypoints.stream().findFirst()))
                .map(region -> getBehaviourHandlerForState().shouldMoveFromRegion(
                    agentWithData,
                    region,
                    nextRegion, dispersion)).orElse(false);

        if (currentRegion.isPresent() && regionWaypoints.size() > 0 && (
                currentRegion.get().equals(regionWaypoints.get(0)) ||
                        (centreOfMass.isPresent() && regionWaypoints.get(0).centrePoint().distance(centreOfMass.get()) < 7.5f))
                && shouldMoveFromRegion) {
            // Arrived at the head waypoint.
            previousRegion = previousCurrentRegion;
            regionWaypoints.remove(0);
            if (regionWaypoints.size() > 0) {
                waypointsCalculatedFrom = Optional.of(regionWaypoints.get(0));
            }
        }
        if (currentRegion.equals(targetRegion) &&
                (currentRegionData.isEmpty() || shouldMoveFromRegion)) {
            // Finished path.
            waypointsCalculatedTo = Optional.empty();
            waypointsCalculatedFrom = Optional.empty();
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
    private long armyLogicUpdate(AgentWithData agentWithData, List<Unit> allUnits) {
        // Calculated weighted centre of mass based on the unit's maximum HP.
        // Note: Max is used here to avoid unwanted shifting when part of the army is low on HP.
        final float defaultHealth = 1f;
        OptionalDouble sumHp = allUnits.stream().mapToDouble(unit -> unit.getHealthMax().orElse(defaultHealth)).average();
        OptionalDouble averageX = allUnits.stream().mapToDouble(unit ->
                unit.getHealthMax().orElse(defaultHealth) * unit.getPosition().toPoint2d().getX()).average();
        OptionalDouble averageY = allUnits.stream().mapToDouble(unit ->
                unit.getHealthMax().orElse(defaultHealth) * unit.getPosition().toPoint2d().getY()).average();

        if (averageX.isPresent() && averageY.isPresent() && sumHp.isPresent()) {
            Point2d calculatedCentreOfMass = Point2d.of((float) averageX.getAsDouble(), (float) averageY.getAsDouble()).div(
                    (float)sumHp.getAsDouble());
            centreOfMass = Optional.of(calculatedCentreOfMass);
            double rmsSum = 0;
            rmsSum = allUnits.stream().mapToDouble(unit -> unit.getPosition().toPoint2d().distance(calculatedCentreOfMass)).sum();
            dispersion = Optional.of(Math.sqrt(rmsSum / allUnits.size()));
        } else {
            centreOfMass = Optional.empty();
            dispersion = Optional.empty();
        }
        // Calculated the root mean squared distance.
        if (waypointsCalculatedTo.isPresent() && regionWaypoints.size() > 0) {
            nextRegion = regionWaypoints.stream().findFirst().flatMap(region ->
                    agentWithData.mapAwareness().getRegionDataForId(region.regionId()));
        } else {
            nextRegion = Optional.empty();
        }
        Optional<Army> enemyArmy = centreOfMass.flatMap(point2d -> agentWithData.enemyAwareness().getMaybeEnemyArmy(point2d));
        List<Army> armyList = enemyArmy.map(army -> List.of(army)).orElse(Collections.emptyList());

        // Analyse our performance in the fight.
        currentFightPerformance = calculateFightPerformance(
                agentWithData.observation().getGameLoop(),
                previousEnemyArmyObservation,
                enemyArmy,
                previousComposition,
                currentComposition);

        AggressionState newAggressionState = aggressionState;
        ImmutableBaseArgs baseArgs = ImmutableBaseArgs.of(
                this,
                agentWithData,
                allUnits,
                armyList,
                centreOfMass,
                currentFightPerformance,
                targetPosition,
                currentRegion,
                previousRegion,
                nextRegion,
                targetRegion,
                retreatRegion);
        if (aggressionState == AggressionState.ATTACKING) {
            newAggressionState = handleState(behaviour.getAttackHandler(), allUnits, baseArgs);
        } else if (aggressionState == AggressionState.RETREATING) {
            newAggressionState = handleState(behaviour.getDisengagingHandler(), allUnits, baseArgs);
        } else if (aggressionState == AggressionState.REGROUPING) {
            newAggressionState = handleState(behaviour.getRegroupingHandler(), allUnits, baseArgs);
        } else if (aggressionState == AggressionState.IDLE) {
            newAggressionState = handleState(behaviour.getIdleHandler(), allUnits, baseArgs);
        } else {
            throw new IllegalStateException("Invalid aggression state " + aggressionState);
        }
        if (newAggressionState != aggressionState) {
            aggressionState = newAggressionState;
            getBehaviourHandlerForState().onEnterState(baseArgs);
        }

        previousEnemyArmyObservation = enemyArmy;
        previousComposition = new HashMap<>(currentComposition);
        // If the enemy army is near us, update more frequently.
        if (enemyArmy.flatMap(Army::position).isPresent() &&
                centreOfMass.isPresent() &&
                enemyArmy.flatMap(Army::position).get().distance(centreOfMass.get()) < 20.0) {
            return 2;
        }
        return 11;
    }

    private <T> AggressionState handleState(DefaultArmyTaskBehaviourStateHandler<T> handler,
                                            List<Unit> allUnits,
                                            ImmutableBaseArgs baseArgs) {
        AggressionState newAggressionState;
        T context = handler.onArmyStep(baseArgs);
        context = allUnits.stream().reduce(
                context,
                (unit, ctx) -> handler.onArmyUnitStep(unit, ctx, baseArgs),
                (oldContext, newContext) -> newContext);
        return handler.getNextState(context, baseArgs);
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
        return "Army." + armyKey;
    }

    @Override
    public void debug(S2Agent agent) {
        centreOfMass.ifPresent(point2d -> {
            float z = agent.observation().terrainHeight(point2d);
            Point point = Point.of(point2d.getX(), point2d.getY(), z);
            agent.debug().debugSphereOut(point, Math.max(1f, dispersion.orElse(1.0).floatValue()), Color.YELLOW);
            agent.debug().debugTextOut(this.armyName + " (Dispersion: " + dispersion.orElse(1.0) + ")", point, Color.WHITE, 8);
        });
        targetPosition.ifPresent(point2d -> {
            float z = agent.observation().terrainHeight(point2d);
            Point point = Point.of(point2d.getX(), point2d.getY(), z);
            agent.debug().debugSphereOut(point, 1f, Color.RED);
            agent.debug().debugTextOut(this.armyName, point, Color.WHITE, 8);
        });
        retreatPosition.ifPresent(point2d -> {
            float z = agent.observation().terrainHeight(point2d);
            Point point = Point.of(point2d.getX(), point2d.getY(), z);
            agent.debug().debugSphereOut(point, 1f, Color.YELLOW);
            agent.debug().debugTextOut(this.armyName, point, Color.WHITE, 8);
        });
        if (centreOfMass.isPresent() && regionWaypoints.size() > 0) {
            Point2d startPoint = centreOfMass.get();
            Point lastPoint = Point.of(startPoint.getX(), startPoint.getY(), agent.observation().terrainHeight(startPoint)+1f);
            for (Region waypoint : regionWaypoints) {
                Point2d nextPoint = waypoint.centrePoint();
                Point newPoint = Point.of(nextPoint.getX(), nextPoint.getY(), agent.observation().terrainHeight(nextPoint)+1f);

                agent.debug().debugSphereOut(newPoint, 1f, Color.WHITE);
                agent.debug().debugTextOut("Region " + waypoint.regionId(), newPoint, Color.TEAL, 12);
                agent.debug().debugLineOut(lastPoint, newPoint, Color.WHITE);
                lastPoint = newPoint;
            }
        }
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
    public Optional<Double> getDispersion() {
        return dispersion;
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
}
