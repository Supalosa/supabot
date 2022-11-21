package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Upgrade;
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
import com.supalosa.bot.task.TaskVisitor;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public abstract class DefaultArmyTask<A,D,R,I> extends DefaultTaskWithUnits implements ArmyTask, TaskWithParent {

    private static final long NORMAL_UPDATE_INTERVAL = 11;
    private final String armyName;
    private final String armyKey;
    private final Map<Tag, Float> rememberedUnitHealth = new HashMap<>();
    private final ThreatCalculator threatCalculator;

    // Whether the army wants units as they are produced. Typically will be false for the main attacking army (as we
    // use child armies to reinforce) and child armies (same reason).
    private boolean acceptingUnits = true;

    private Optional<Point2d> targetPosition = Optional.empty();
    private Optional<Point2d> retreatPosition = Optional.empty();
    private Optional<Point2d> centreOfMass = Optional.empty();
    private Optional<Double> dispersion;

    private AggressionState aggressionState = AggressionState.REGROUPING;

    // The region corresponding to the centreOfMass.
    private Optional<RegionData> currentRegion = Optional.empty();

    // The region that we want to advance towards (corresponding to targetPostion).
    private Optional<RegionData> targetRegion = Optional.empty();

    // The region that we want to retreat towards (corresponding to retreatPosition).
    private Optional<RegionData> retreatRegion = Optional.empty();

    // If the targetRegion is set, the region that we want to advance to next to get there.
    private Optional<RegionData> nextRegion = Optional.empty();

    // If the retreatRegion is set, the region we want to retreat to next to get there.
    private Optional<RegionData> nextRetreatRegion = Optional.empty();

    // List of waypoints towards the target region.
    private List<Region> targetRegionWaypoints = new ArrayList<>();

    // List of waypoints towards the target region.
    private List<Region> retreatRegionWaypoints = new ArrayList<>();

    // Time to prevent super-frequent region changes.
    private long enteredCurrentRegionAt = 0L;

    private long waypointsCalculatedAt = 0L;
    private long nextArmyLogicUpdateAt = 0L;
    private MapAwareness.PathRules pathRules = MapAwareness.PathRules.AVOID_KILL_ZONE;
    // These are used for observing if we're winning or losing a fight.
    private Optional<Army> previousEnemyArmyObservation = Optional.empty();
    private Map<UnitType, Integer> previousComposition = new HashMap<>();
    private FightPerformance currentFightPerformance = FightPerformance.STABLE;
    private AggressionLevel aggressionLevel = AggressionLevel.BALANCED;
    private double cumulativeThreatDelta = 0.0;
    private double cumulativePowerDelta = 0.0;
    private long cumulativeThreatAndPowerCalculatedAt = 0L;

    private boolean isComplete = false;

    // This is another army task that exists, that this army will try to merge into by moving close to it.
    private Optional<DefaultArmyTask<A,D,R,I>> parentArmy = Optional.empty();
    // This a list of all (live) child armies that have this army set as its parent.
    private List<DefaultArmyTask> childArmies = new ArrayList<>();
    // This is an army that this army is delegated to, and will defer production queries to. This is used so that the
    // 'reserve' army will take the desired composition of the 'main' army.
    private Optional<DefaultArmyTask<A,D,R,I>> productionDelegateArmy = Optional.empty();

    private final DefaultArmyTaskBehaviour<A,D,R,I> behaviour;

    private boolean shouldMoveFromRegion = true;

    private Set<Upgrade> upgrades = new HashSet<>();

    private List<ArmyTaskListener> listeners = new ArrayList<>();

    // Tracking of engagement events.
    private boolean isEngaging = false;
    private List<UnitInPool> engagementStartUnits = new ArrayList<>();
    private double engagementStartPower = 0.0;
    private long engagementEndingAt = 0L;

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
    public final DefaultArmyTask<A,D,R,I> createChildArmy() {
        DefaultArmyTask<A,D,R,I> childArmy = createChildArmyImpl();
        // Inherit some settings from this army.
        childArmy.setAcceptingUnits(this.acceptingUnits);
        childArmy.setAggressionLevel(this.aggressionLevel);
        childArmies.add(childArmy);
        return childArmy;
    }

    protected abstract DefaultArmyTask<A,D,R,I> createChildArmyImpl();

    @Override
    public String getArmyName() {
        return this.armyName;
    }

    @Override
    public void setRetreatPosition(Optional<Point2d> retreatPosition) {
        this.retreatPosition = retreatPosition;
    }

    @Override
    public int getSize() {
        return this.getAssignedUnits().size();
    }

    @Override
    public void onStepImpl(TaskManager taskManager, AgentWithData agentWithData) {
        this.upgrades = agentWithData.observation().getUpgrades().stream().collect(Collectors.toUnmodifiableSet());
        List<Unit> allUnits = new ArrayList<>();
        for (Tag tag : getAssignedUnits()) {
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
                } else if (parentArmy.get().getAssignedUnits().size() == 0) {
                    parentArmy.get().takeAllFrom(agentWithData.taskManager(), agentWithData.observation(), this);
                    markComplete();
                }
                if (getAssignedUnits().size() == 0) {
                    markComplete();
                }
            }
        }
        if (productionDelegateArmy.isPresent()) {
            if (productionDelegateArmy.get().isComplete() || !taskManager.hasTask(productionDelegateArmy.get())) {
                productionDelegateArmy = Optional.empty();
            }
        }

        // Remove child armies that are dead.
        childArmies = childArmies.stream()
                .filter(task -> task.isComplete() == false)
                .filter(task -> taskManager.hasTask(task))
                .collect(Collectors.toList());

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
        // Calculate path every time. TODO: probably don't need to do this, cut down in the future.

        final AtomicBoolean pathDirty = new AtomicBoolean(false);
        generatePathTowardsGoal(agentWithData, pathDirty, targetRegion).ifPresent(pathToTargetRegion -> {
            targetRegionWaypoints = new ArrayList<>(pathToTargetRegion);
            // Remove the head of the path if we're already in that region.
            // Isn't the head always going to be the current region?
            if (pathToTargetRegion.size() > 0 &&
                    currentRegion.map(RegionData::region).map(Region::regionId).get().equals(pathToTargetRegion.get(0).regionId())) {
                targetRegionWaypoints.remove(0);
            }
            pathDirty.set(true);
        });
        generatePathTowardsGoal(agentWithData, pathDirty, retreatRegion).ifPresent(pathToRetreatRegion -> {
            retreatRegionWaypoints = new ArrayList<>(pathToRetreatRegion);
            // Remove the head of the path if we're already in that region.
            // Isn't the head always going to be the current region?
            if (pathToRetreatRegion.size() > 0 &&
                    currentRegion.map(RegionData::region).map(Region::regionId).get().equals(pathToRetreatRegion.get(0).regionId())) {
                retreatRegionWaypoints.remove(0);
            }
            pathDirty.set(true);
        });

        if (pathDirty.get()) {
            updateCurrentRegions(agentWithData);
        }
    }

    private Optional<List<Region>> generatePathTowardsGoal(AgentWithData agentWithData,
                                                           AtomicBoolean pathDirty,
                                                           Optional<RegionData> goalRegion) {
        if (currentRegion.isPresent() && goalRegion.isPresent() && !currentRegion.equals(goalRegion)) {
            Optional<RegionGraphPath> maybePath = agentWithData
                    .mapAwareness()
                    .generatePath(currentRegion.map(RegionData::region).get(), goalRegion.get().region(), pathRules);
            return maybePath.flatMap(path -> {
                List<Region> regionList = path.getPath();
                return Optional.of(regionList);
            });
        }
        return Optional.empty();
    }

    private void updateCurrentRegions(AgentWithData agentWithData) {
        if (agentWithData.observation().getGameLoop() < enteredCurrentRegionAt + 22L) {
            return;
        }
        targetRegion = targetPosition.flatMap(position ->
                agentWithData.mapAwareness().getRegionDataForPoint(position));
        retreatRegion = retreatPosition.flatMap(position ->
                agentWithData.mapAwareness().getRegionDataForPoint(position));
        Optional<RegionData> previousCurrentRegion = currentRegion;
        Optional<RegionData> currentRegionData = centreOfMass.flatMap(centre -> agentWithData.mapAwareness().getRegionDataForPoint(centre));

        if (!currentRegionData.equals(previousCurrentRegion)) {
            //System.out.println("Region changed for " + this.armyName);
            enteredCurrentRegionAt = agentWithData.observation().getGameLoop();
        }
        currentRegion = currentRegionData;
        if (currentRegion.isEmpty() && centreOfMass.isPresent()) {
            // Never let the current region be empty unless we have no units.
            //currentRegion = previousCurrentRegion;
            currentRegion = Optional.of(agentWithData.mapAwareness().getNearestNormalRegion(centreOfMass.get()));
        }

        long timeSpentInRegion = agentWithData.observation().getGameLoop() - enteredCurrentRegionAt;
        this.shouldMoveFromRegion = currentRegion
                .map(region -> getBehaviourHandlerForState().shouldMoveFromRegion(
                    agentWithData,
                    region,
                    nextRegion, dispersion, childArmies, timeSpentInRegion, this)).orElse(false);

        if (currentRegion.isPresent() && targetRegionWaypoints.size() > 0 && (
                currentRegion.get().equals(targetRegionWaypoints.get(0)) ||
                        // Iff the next waypoint isn't the target, then optionally skip over it if the distance < 7.5
                        (targetRegionWaypoints.size() > 1 && centreOfMass.isPresent() && targetRegionWaypoints.get(0).centrePoint().distance(centreOfMass.get()) < 7.5f))
                && shouldMoveFromRegion) {
            // Arrived at the head waypoint.
            targetRegionWaypoints.remove(0);
        }
        if (currentRegion.isPresent() && retreatRegionWaypoints.size() > 0 && (
                currentRegion.get().equals(retreatRegionWaypoints.get(0)) ||
                        // Iff the next waypoint isn't the target, then optionally skip over it if the distance < 7.5
                        (retreatRegionWaypoints.size() > 1 && centreOfMass.isPresent() && retreatRegionWaypoints.get(0).centrePoint().distance(centreOfMass.get()) < 7.5f))
                && shouldMoveFromRegion) {
            // Arrived at the head waypoint.
            retreatRegionWaypoints.remove(0);
        }
    }

    private Pair<List<Unit>, List<Unit>> calculateUnitProximityToPoint(ObservationInterface observationInterface,
                                                                       float searchRadius, Point2d com) {
        List<Unit> far = new ArrayList<>();
        List<Unit> near = observationInterface.getUnits(unitInPool ->
                        getAssignedUnits().contains(unitInPool.getTag())).stream()
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

        // Calculated the root mean squared distance.
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

        // Determine which region to move to and retreat to next.
        if (targetRegionWaypoints.size() > 0) {
            nextRegion = targetRegionWaypoints.stream().findFirst().flatMap(region ->
                    agentWithData.mapAwareness().getRegionDataForId(region.regionId()));
        } else if (centreOfMass.isPresent()) {
            nextRegion = Optional.empty();
        }
        if (retreatRegionWaypoints.size() > 0) {
            nextRetreatRegion = retreatRegionWaypoints.stream().findFirst().flatMap(region ->
                    agentWithData.mapAwareness().getRegionDataForId(region.regionId()));
        } else if (centreOfMass.isPresent()) {
            nextRetreatRegion = Optional.empty();
        }

        float enemyArmySearchRadius = 20f;
        List<Army> armyList = centreOfMass
                .map(point2d -> agentWithData.enemyAwareness().getMaybeEnemyArmies(point2d, enemyArmySearchRadius))
                .orElse(Collections.emptyList());
        Army virtualArmy = Army.toVirtualArmy(armyList);

        // Analyse our performance in the fight.
        currentFightPerformance = calculateFightPerformance(
                agentWithData.observation().getGameLoop(),
                previousEnemyArmyObservation,
                virtualArmy,
                previousComposition,
                this.getCurrentCompositionCache());
        FightPerformance predictedFightPerformance = this.predictFightAgainst(virtualArmy);

        // Handle dispatching of events.
        handleEngagementDispatch(agentWithData, virtualArmy, predictedFightPerformance);

        AggressionState newAggressionState = aggressionState;
        Optional<RegionData> maybeNextRegion = shouldMoveFromRegion ? nextRegion : Optional.empty();
        ImmutableBaseArgs baseArgs = ImmutableBaseArgs.of(
                this,
                agentWithData,
                allUnits,
                virtualArmy,
                centreOfMass,
                currentFightPerformance,
                predictedFightPerformance,
                targetPosition,
                retreatPosition,
                currentRegion,
                nextRetreatRegion,
                maybeNextRegion,
                targetRegion,
                retreatRegion,
                agentWithData.gameData().enemyArmyUnitMap());
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
            // Reset power tracking after each state change.
            resetFightPerformance();
        }

        previousEnemyArmyObservation = Optional.of(virtualArmy);
        previousComposition = new HashMap<>(this.getCurrentCompositionCache());
        return aggressionState.getUpdateInterval();
    }

    private void handleEngagementDispatch(AgentWithData agentWithData, Army virtualArmy, FightPerformance predictedFightPerformance) {
        if (isEngaging == false) {
            if (virtualArmy.threat() > 0) {
                isEngaging = true;
                engagementStartUnits = this.getAssignedUnits().stream()
                        .map(tag -> agentWithData.observation().getUnit(tag))
                        .filter(tag -> tag != null)
                        .collect(Collectors.toList());
                engagementStartPower = this.getPower();
                this.listeners.forEach(listener -> listener.onEngagementStarted(this, virtualArmy, predictedFightPerformance));
            }
        } else {
            long gameLoop = agentWithData.observation().getGameLoop();
            if (virtualArmy.threat() > 0) {
                // If no threat in 10 seconds, the engagement is over.
                engagementEndingAt = gameLoop + 224L;
            } else if (gameLoop > engagementEndingAt) {
                isEngaging = false;
                double powerLost = this.getPower() - engagementStartPower;
                Set<Tag> remainingUnits = new HashSet<>(this.getAssignedUnits());
                List<UnitType> engagementUnitsLost = engagementStartUnits.stream()
                        .filter(unit -> !remainingUnits.contains(unit.getTag()))
                        .map(UnitInPool::unit)
                        .map(Unit::getType)
                        .collect(Collectors.toList());
                this.listeners.forEach(listener -> listener.onEngagementEnded(this, powerLost, engagementUnitsLost));
            }
        }
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
            Army currentEnemyObservation,
            Map<UnitType, Integer> previousComposition,
            Map<UnitType, Integer> currentComposition) {
        double currentEnemyThreat = currentEnemyObservation.threat();
        double threatDelta = currentEnemyThreat -
                previousEnemyObservation.map(Army::threat).orElse(0.0);
        double currentPower = threatCalculator.calculatePower(currentComposition, this.upgrades);
        double powerDelta = currentPower -
                threatCalculator.calculatePower(previousComposition, this.upgrades);
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
            return FightPerformance.WINNING;
        } else if (currentEnemyThreat > currentPower && relativeDelta < -(currentPower)) {
            return FightPerformance.BADLY_LOSING;
        } else if (currentEnemyThreat > currentPower && relativeDelta < 0) {
            return FightPerformance.SLIGHTLY_LOSING;
        } else {
            return FightPerformance.STABLE;
        }
    }

    private void resetFightPerformance() {
        cumulativeThreatDelta = 0;
        cumulativePowerDelta = 0;
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
        if (centreOfMass.isPresent() && targetRegionWaypoints.size() > 0) {
            Point2d startPoint = centreOfMass.get();
            Point lastPoint = Point.of(startPoint.getX(), startPoint.getY(), agent.observation().terrainHeight(startPoint)+1f);
            for (Region waypoint : targetRegionWaypoints) {
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
                " (" + getAssignedUnits().size() + ") - " + aggressionState + " - " + currentFightPerformance;
    }

    @Override
    public Optional<List<Region>> getWaypoints() {
        return Optional.of(this.targetRegionWaypoints);
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
        double currentPower = threatCalculator.calculatePower(this.getCurrentCompositionCache(), this.upgrades);
        if (currentPower > currentEnemyThreat * 1.25) {
            return FightPerformance.WINNING;
        } else if (currentPower > currentEnemyThreat * 1.10) {
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

    @Override
    public double getPower() {
        return threatCalculator.calculatePower(this.getCurrentCompositionCache(), this.upgrades);
    }

    public void setAcceptingUnits(boolean acceptingUnits) {
        this.acceptingUnits = acceptingUnits;
    }

    @Override
    public boolean wantsUnit(Unit unit) {
        // Check if the production delegate wants it, otherwise check if we want it.
        // We have to bypass the `acceptingUnits` check for the delegate.
        return productionDelegateArmy
                .map(delegate -> delegate.wantsUnitIgnoringOverride(unit) || super.wantsUnit(unit))
                .orElseGet(() -> acceptingUnits && wantsUnitIgnoringOverride(unit));
    }

    private boolean wantsUnitIgnoringOverride(Unit unit) {
        return super.wantsUnit(unit);
    }

    public void setProductionDelegateArmy(DefaultArmyTask<A, D, R, I> productionDelegateArmy) {
        this.productionDelegateArmy = Optional.of(productionDelegateArmy);
    }

    @Override
    public Optional<? extends Task> getParentTask() {
        return parentArmy;
    }

    @Override
    public void accept(TaskVisitor visitor) {
        visitor.visit(this);
        this.childArmies.forEach(visitor::visit);
    }

    public void addArmyListener(ArmyTaskListener listener) {
        this.listeners.add(listener);
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
