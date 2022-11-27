package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.action.ActionResult;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.github.ocraft.s2client.protocol.unit.UnitOrder;
import com.google.common.base.Preconditions;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.placement.PlacementRegion;
import com.supalosa.bot.placement.PlacementRules;
import com.supalosa.bot.placement.ResolvedPlacementResult;
import com.supalosa.bot.task.army.TerranWorkerRushDefenceTask;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class BuildStructureTask extends BaseTask {

    private static final long BUILD_ATTEMPT_INTERVAL = 22;
    private static final long MAX_BUILD_ATTEMPTS = 15;

    private final Ability ability;
    private final Optional<Integer> minimumMinerals;
    private final Optional<Integer> minimumVespene;
    private final UnitType targetUnitType;
    private Optional<ResolvedPlacementResult> resolvedPlacementResult;
    private final Optional<PlacementRules> placementRules;

    private long nextAssignedWorkerAttempt = 0L;
    private int numWorkersUsed = 0;

    private Optional<Tag> matchingUnitAtLocation = Optional.empty();
    // The tag of the `matchingUnitAtLocation` that we dispatched through `onStarted`. This is tracked so
    // that if the construction is started again, we know to dispatch the event again.
    private Optional<Tag> dispatchedMatchingUnitAtLocation = Optional.empty();
    private Optional<Tag> assignedWorker = Optional.empty();

    // Set of workers we're not going to try again.
    private Set<Tag> bannedWorkers = new HashSet<>();

    private final String taskKey;
    private long lastBuildAttempt = 0;
    private long buildAttempts = 0;
    private boolean isComplete = false;

    // Whether the order has been dispatched. This can go back to false if the worker dies or the construction site
    // is destroyed.
    private boolean isInProgress = false;

    private boolean isSuccess = false;
    private boolean aborted = false;

    private boolean isSafeToBuildStructure = true;

    public BuildStructureTask(Ability ability,
                              UnitType targetUnitType,
                              Optional<Point2d> location,
                              Optional<Unit> specificTarget,
                              Optional<Integer> minimumMinerals,
                              Optional<Integer> minimumVespene,
                              Optional<PlacementRules> placementRules) {
        Preconditions.checkArgument((location.isPresent() ^ specificTarget.isPresent()) ||
                        (location.isEmpty() && specificTarget.isEmpty()),
                "Only location or specificTarget should be set, or neither should be set.");
        this.ability = ability;
        this.targetUnitType = targetUnitType;
        if (location.isPresent()) {
            this.resolvedPlacementResult = Optional.of(ResolvedPlacementResult.point2d(location.get()));
        } else if (specificTarget.isPresent()) {
            this.resolvedPlacementResult = Optional.of(ResolvedPlacementResult.unit(specificTarget.get()));
        } else {
            this.resolvedPlacementResult = Optional.empty();
        }
        this.minimumMinerals = minimumMinerals;
        this.minimumVespene = minimumVespene;
        this.placementRules = placementRules;
        this.taskKey = targetUnitType.toString() + "." + UUID.randomUUID();
    }

    @Override
    public void onStep(TaskManager taskManager, AgentWithData agentWithData) {
        if (aborted) {
            isComplete = true;
            onFailure();
            // Cancel the construction.
            if (assignedWorker.isPresent()) {
                agentWithData.actions().unitCommand(assignedWorker.get(), Abilities.STOP, false);
            }
            return;
        }
        Optional<UnitInPool> worker = Optional.empty();
        long gameLoop = agentWithData.observation().getGameLoop();
        if (assignedWorker.isEmpty()) {
            // No worker for the job - find one.
            if (gameLoop > nextAssignedWorkerAttempt) {
                nextAssignedWorkerAttempt = gameLoop + 5L;
                assignedWorker = findWorker(taskManager, agentWithData, agentWithData, placementRules);
                // Resume the construction if applicable.
                assignedWorker.ifPresentOrElse(theWorker -> {
                    matchingUnitAtLocation.ifPresent(tag -> {
                        UnitInPool unit = agentWithData.observation().getUnit(tag);
                        if (unit != null) {
                            agentWithData.actions().unitCommand(theWorker, Abilities.SMART, unit.unit(), false);
                        }
                    });
                }, () -> {
                    //Reset the banned worker set if we couldn't find a worker.
                    bannedWorkers.clear();
                });
            }
        } else {
            // Validate the assigned worker or the action.
            // Check if the builder had an action error, then respond accordingly.
            agentWithData.observation().getActionErrors().stream().forEach(actionError -> {
                if (assignedWorker.isPresent() && actionError.getUnitTag().equals(assignedWorker)) {
                    System.out.println("Assigned builder had an action error: " + actionError.getActionResult());
                    if (actionError.getActionResult() == ActionResult.COULDNT_REACH_TARGET) {
                        ++buildAttempts;
                        bannedWorkers.add(assignedWorker.get());
                        assignedWorker = Optional.empty();
                        return;
                    }
                    if (actionError.getActionResult() == ActionResult.CANT_FIND_PLACEMENT_LOCATION ||
                            actionError.getActionResult() == ActionResult.CANT_BUILD_LOCATION_INVALID) {
                        resolvedPlacementResult = Optional.empty();
                        return;
                    }
                    if (actionError.getActionResult() == ActionResult.CANT_BUILD_ON_THAT) {
                        resolvedPlacementResult = Optional.empty();
                        return;
                    }
                }
            });
            // Check if the worker still exists (maybe it died).
            worker = assignedWorker.map(assignedWorkerTag ->
                    agentWithData.observation().getUnit(assignedWorkerTag));
            if (worker.isEmpty()) {
                // NOTE: a worker going into the vespene geyser also triggers this.
                if (assignedWorker.isPresent()) {
                    // Worker was assigned but not found - the worker probably died.
                    ++buildAttempts;
                    nextAssignedWorkerAttempt = gameLoop + (numWorkersUsed) * 22L;
                    ++numWorkersUsed;
                }
                assignedWorker = Optional.empty();
            }
        }
        if (worker.isPresent() && resolvedPlacementResult.isEmpty()) {
            // If we have a worker, find a location to place the structure.
            resolvedPlacementResult = resolveLocation(worker.get(), agentWithData);
            if (resolvedPlacementResult.isEmpty()) {
                // Try another worker.
                bannedWorkers.add(worker.get().getTag());
                taskManager.releaseUnit(worker.get().getTag(), this);
                worker = Optional.empty();
                assignedWorker = Optional.empty();
                ++numWorkersUsed;
            }
        }
        if (matchingUnitAtLocation.isEmpty()) {
            Optional<UnitInPool> finalWorker = worker;
            // Look in multiple places for a building under construction.
            // 1. The targeted unit (e.g. refinery on a geyser).
            final Optional<Point2d> locationToSearch = resolvedPlacementResult.flatMap(ResolvedPlacementResult::unit).map(Unit::getPosition).map(Point::toPoint2d)
                    // 2. If the worker is building something, where it is building it.
                    .or(() -> finalWorker.flatMap(this::getWorkerOrderTargetedWorldSpace))
                    // 3. If a location was specified for construction, that position.
                    .or(() -> resolvedPlacementResult.flatMap(ResolvedPlacementResult::point2d))
                    // 4. Finally, the worker's location.
                    .or(() -> finalWorker.map(w -> w.unit().getPosition().toPoint2d()));
            // Find any matching units within 1.5 range of target location
            List<UnitInPool> matchingUnits = agentWithData.observation().getUnits(unitInPool ->
                    unitInPool.getUnit().filter(unit -> unit.getAlliance() == Alliance.SELF &&
                            unit.getType().equals(targetUnitType) &&
                            unit.getBuildProgress() < 0.99 &&
                            locationToSearch.map(targetLocation -> unit.getPosition().toPoint2d().distance(targetLocation) < 1.5)
                                    .orElse(false)
                    ).isPresent()
            );
            matchingUnitAtLocation = matchingUnits.stream().findFirst().map(unitInPool -> unitInPool.getTag());
        } else {
            // Check the structure still exists.
            matchingUnitAtLocation = matchingUnitAtLocation.filter(tag -> agentWithData.observation().getUnit(tag) != null);
        }

        // Check if it's safe to build.
        if (resolvedPlacementResult.isPresent()) {
            Point2d point2d = resolvedPlacementResult.get().asPoint2d();
            // Should build structure only if the enemy doesn't control the region.
            Optional<RegionData> locationRegionData = agentWithData
                    .mapAwareness()
                    .getRegionDataForPoint(point2d);
            isSafeToBuildStructure = !locationRegionData.map(RegionData::isEnemyControlled).orElse(false);
            // Add an override for certain production buildings etc.
            if (Constants.CRITICAL_STRUCTURE_TYPES.contains(targetUnitType)) {
                isSafeToBuildStructure = true;
            }
        }

        if (buildAttempts > MAX_BUILD_ATTEMPTS) {
            System.out.println("Build task of " + targetUnitType + " failed, attempting cancellation.");
            // Cancel the construction if applicable.
            matchingUnitAtLocation.ifPresent(tag -> {
                agentWithData.actions().unitCommand(tag, Abilities.CANCEL, false);
            });
            if (matchingUnitAtLocation.isEmpty()) {
                agentWithData.actions().sendChat("Failed: " + getDebugText(), ActionChat.Channel.TEAM);
                isComplete = true;
                onFailure();
            }
            return;
        }
        if (worker.isPresent() &&
                resolvedPlacementResult.isPresent() &&
                !matchingUnitAtLocation.isPresent() &&
                minimumMinerals.map(minimum -> agentWithData.observation().getMinerals() >= minimum).orElse(true) &&
                minimumVespene.map(minimum -> agentWithData.observation().getVespene() >= minimum).orElse(true) &&
                !isWorkerOrderQueued(worker.get()) &&
                isSafeToBuildStructure) {
            if (gameLoop > lastBuildAttempt + BUILD_ATTEMPT_INTERVAL) {
                if (agentWithData.gameData().unitHasAbility(assignedWorker.get(), ability)) {
                    if (resolvedPlacementResult.get().unit().isPresent()) {
                        Unit specificTarget = resolvedPlacementResult.get().unit().get();
                        agentWithData.actions().unitCommand(assignedWorker.get(), ability, specificTarget, false);
                    } else {
                        Point2d point2d = resolvedPlacementResult.get().asPoint2d();
                        agentWithData.actions().unitCommand(assignedWorker.get(), ability, point2d, false);
                    }
                    lastBuildAttempt = gameLoop;
                    ++buildAttempts;
                    if (buildAttempts > MAX_BUILD_ATTEMPTS / 2) {
                        // Reset the location on the second half of attempts.
                        resolvedPlacementResult = Optional.empty();
                    }
                } else if (resolvedPlacementResult.isPresent()) {
                    // Construction is not available, but move to the target location anyway.
                    Point2d point2d = resolvedPlacementResult.get().asPoint2d();
                    agentWithData.actions().unitCommand(assignedWorker.get(), Abilities.MOVE, point2d, false);
                }
            }
        }
        if (matchingUnitAtLocation.isPresent()) {
            if (!dispatchedMatchingUnitAtLocation.equals(matchingUnitAtLocation)) {
                // Send an event to listeners saying that the structure has started.
                onStarted();
                dispatchedMatchingUnitAtLocation = matchingUnitAtLocation;
            }
            UnitInPool actualUnit = agentWithData.observation().getUnit(matchingUnitAtLocation.get());
            if (actualUnit != null) {
                // Move the target location to the location of the unit under construction.
                if (resolvedPlacementResult.isPresent() && resolvedPlacementResult.get().point2d().isPresent()) {
                    resolvedPlacementResult = Optional.of(ResolvedPlacementResult.point2d(actualUnit.unit().getPosition().toPoint2d()));
                }
                float buildProgress = actualUnit.unit().getBuildProgress();
                if (buildProgress > 0.99) {
                    isComplete = true;
                    isSuccess = true;
                    onComplete();
                    return;
                }
                // Cancel if low HP.
                float expectedHp = actualUnit.unit().getHealthMax().map(healthMax -> healthMax * buildProgress).orElse(1000f);
                if (actualUnit.unit().getHealth().map(health -> health < expectedHp * 0.25f).orElse(false)) {
                    matchingUnitAtLocation.ifPresent(tag -> {
                        agentWithData.actions().unitCommand(tag, Abilities.CANCEL, false);
                    });
                }
            }
        }
        if ((worker.isPresent() && matchingUnitAtLocation.isPresent()) ||
                worker.filter(this::isWorkerOrderQueued).isPresent()) {
            isInProgress = true;
        } else {
            isInProgress = false;
        }
    }

    private Optional<Tag> findWorker(TaskManager taskManager, S2Agent agent, AgentData data, Optional<PlacementRules> placementRules) {
        // This should probably be a predicate associated to the PlacementRules it itself.
        boolean nearBaseOnly = placementRules
                .filter(rule -> rule.regionType().filter(PlacementRegion::isPlayerBase).isPresent())
                .isPresent();
        if (resolvedPlacementResult.isPresent()) {
            // If location is known, find closest unit to that location.
            // Avoid using workers that are carrying minerals.
            Point2d targetPosition = resolvedPlacementResult.get().asPoint2d();
            return taskManager.findFreeUnitForTask(
                    this,
                    agent.observation(),
                    unitInPool -> unitInPool.unit() != null &&
                            Constants.WORKER_TYPES.contains(unitInPool.unit().getType()) &&
                            !bannedWorkers.contains(unitInPool.getTag()) &&
                            !UnitInPool.isCarryingMinerals().test(unitInPool) &&
                            !UnitInPool.isCarryingVespene().test(unitInPool),
                    Comparator.comparing((UnitInPool unitInPool) ->
                            unitInPool.unit().getPosition().toPoint2d().distance(targetPosition))
            ).map(unitInPool -> unitInPool.getTag());
        } else if (nearBaseOnly) {
            // If the placement rules require the structure in the base, choose a worker in a player base only.
            Optional<RegionData> playerBaseRegion = data.mapAwareness().getRandomPlayerBaseRegion();
            Optional<Point2d> baseLocation = playerBaseRegion.map(RegionData::region).map(Region::centrePoint);
            return baseLocation.flatMap(location -> taskManager.findFreeUnitForTask(
                    this,
                    agent.observation(),
                    unitInPool -> unitInPool.unit() != null &&
                            Constants.WORKER_TYPES.contains(unitInPool.unit().getType()) &&
                            !bannedWorkers.contains(unitInPool.getTag()) &&
                            !UnitInPool.isCarryingMinerals().test(unitInPool) &&
                            !UnitInPool.isCarryingVespene().test(unitInPool) &&
                            data.mapAwareness()
                                    .getRegionDataForPoint(unitInPool.unit().getPosition().toPoint2d())
                                    .map(RegionData::isPlayerBase).orElse(false),
                    Comparator.comparing((UnitInPool unitInPool) ->
                            unitInPool.unit().getPosition().toPoint2d().distance(location))))
                    .map(unitInPool -> unitInPool.getTag());
        } else {
            // Take any worker.
            return taskManager.findFreeUnitForTask(
                    this,
                    agent.observation(),
                    unitInPool -> unitInPool.unit() != null &&
                            Constants.WORKER_TYPES.contains(unitInPool.unit().getType())
            ).map(unitInPool -> unitInPool.getTag());
        }
    }

    private boolean isWorkerOrderQueued(UnitInPool worker) {
        return worker.unit()
                .getOrders()
                .stream()
                .anyMatch(unitOrder -> ability.equals(unitOrder.getAbility()));
    }

    private Optional<Point2d> getWorkerOrderTargetedWorldSpace(UnitInPool worker) {
        return worker.unit().getOrders().stream()
                .filter(unitOrder -> ability.equals(unitOrder.getAbility()))
                .map(UnitOrder::getTargetedWorldSpacePosition)
                .map(maybePosition -> maybePosition.map(point -> point.toPoint2d()))
                .findFirst()
                .orElse(Optional.empty());
    }

    private Optional<ResolvedPlacementResult> resolveLocation(UnitInPool worker, AgentWithData data) {
        return resolvedPlacementResult.or(() -> data.structurePlacementCalculator().flatMap(spc -> spc.suggestLocationForFreePlacement(
                data,
                worker.unit().getPosition().toPoint2d(),
                ability,
                targetUnitType,
                placementRules)));
    }

    @Override
    public Optional<TaskResult> getResult() {
        if (isComplete) {
            if (isSuccess) {
                return Optional.of(ImmutableTaskResult.builder()
                        .isSuccessful(true)
                        .locationResult(resolvedPlacementResult.map(ResolvedPlacementResult::asPoint2d))
                        .build());
            } else {
                return Optional.of(ImmutableTaskResult.of(false));
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }

    @Override
    public String getKey() {
        return taskKey;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        if (otherTask instanceof BuildStructureTask) {
            BuildStructureTask other = (BuildStructureTask) otherTask;
            return (other.ability.equals(this.ability));
        } else {
            return false;
        }
    }

    @Override
    public void debug(S2Agent agent) {
        if (isComplete) {
            return;
        }
        if (this.resolvedPlacementResult.isPresent()) {
            Point2d actualLocation = this.resolvedPlacementResult.get().asPoint2d();
            float height = agent.observation().terrainHeight(actualLocation);
            Point point3d = actualLocation.toPoint2d(height);
            Color color = Color.YELLOW;
            if (this.matchingUnitAtLocation.isPresent()) {
                color = Color.GREEN;
            }
            agent.debug().debugSphereOut(point3d, 1.0f, color);
            agent.debug().debugTextOut(
                    "Build " + targetUnitType.toString() + "\n" + buildAttempts + "/" + MAX_BUILD_ATTEMPTS,
                    point3d, Color.WHITE, 10);
        }
        if (this.assignedWorker.isPresent()) {
            UnitInPool unitInPool = agent.observation().getUnit(this.assignedWorker.get());
            if (unitInPool == null) {
                return;
            }
            Point point3d = unitInPool.unit().getPosition().add(Point.of(0f, 0f, -0.5f));
            Color color = Color.YELLOW;
            if (this.matchingUnitAtLocation.isPresent()) {
                color = Color.GREEN;
            }
            agent.debug().debugSphereOut(point3d, 1.0f, color);
            agent.debug().debugTextOut(
                    "Build " + targetUnitType.toString() + "\n" + buildAttempts + "/" + MAX_BUILD_ATTEMPTS,
                    point3d, Color.WHITE, 10);
            if (this.resolvedPlacementResult.isPresent()) {
                Point2d point2d = resolvedPlacementResult.get().asPoint2d();
                float height = agent.observation().terrainHeight(point2d);
                Point destinationLocation = point2d.toPoint2d(height);
                agent.debug().debugLineOut(point3d, destinationLocation, Color.TEAL);
            }
        }
    }

    @Override
    public String getDebugText() {
        return "[" + buildAttempts + "/" + MAX_BUILD_ATTEMPTS + "] Build " + targetUnitType + " @ " + resolvedPlacementResult.map(ResolvedPlacementResult::asPoint2d).map(p2d -> p2d.getX() + "," + p2d.getY()).orElse("anywhere");
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        // Abort construction task if worker rush detected.
        if (message instanceof TerranWorkerRushDefenceTask.WorkerRushDetected) {
            this.aborted = true;
        }
        return Optional.empty();
    }

    @Override
    public int reservedMinerals() {
        // If it's not safe to build this, then don't reserve the minerals.
        return (isInProgress || !isSafeToBuildStructure) ? 0 : minimumMinerals.orElse(0);
    }

    @Override
    public int reservedVespene() {
        // If it's not safe to build this, then don't reserve the vespene.
        return (isInProgress || !isSafeToBuildStructure) ? 0 : minimumVespene.orElse(0);
    }

    public UnitType getTargetUnitType() {
        return this.targetUnitType;
    }

    public Ability getAbility() {
        return this.ability;
    }

    /**
     * Returns true if the task has 'started', which is defined as the structure exists.
     */
    public boolean isStarted() {
        return this.matchingUnitAtLocation.isPresent();
    }
}
