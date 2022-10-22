package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.action.ActionError;
import com.github.ocraft.s2client.protocol.action.ActionResult;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.task.army.TerranWorkerRushDefenceTask;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class BuildStructureTask implements Task {

    private static final long BUILD_ATTEMPT_INTERVAL = 22;
    private static final long MAX_BUILD_ATTEMPTS = 10;

    private final Ability ability;
    private final Optional<Integer> minimumMinerals;
    private final Optional<Integer> minimumVespene;
    private final UnitType targetUnitType;
    private Optional<Point2d> location;
    private final Optional<Unit> specificTarget;
    private final Optional<PlacementRules> placementRules;

    private long nextAssignedWorkerAttempt = 0L;
    private int numWorkersUsed = 0;

    private Optional<Tag> matchingUnitAtLocation = Optional.empty();
    private Optional<Tag> assignedWorker = Optional.empty();

    private final String taskKey;
    private long lastBuildAttempt = 0;
    private long buildAttempts = 0;
    private boolean isComplete = false;

    private boolean aborted = false;

    public BuildStructureTask(Ability ability,
                              UnitType targetUnitType,
                              Optional<Point2d> location,
                              Optional<Unit> specificTarget,
                              Optional<Integer> minimumMinerals,
                              Optional<Integer> minimumVespene,
                              Optional<PlacementRules> placementRules) {
        this.ability = ability;
        this.targetUnitType = targetUnitType;
        this.location = location;
        this.specificTarget = specificTarget;
        this.minimumMinerals = minimumMinerals;
        this.minimumVespene = minimumVespene;
        this.placementRules = placementRules;
        this.taskKey = targetUnitType.toString() + "." + UUID.randomUUID();
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        if (aborted) {
            isComplete = true;
            if (assignedWorker.isPresent()) {
                agent.actions().unitCommand(assignedWorker.get(), Abilities.STOP, false);
            }
            return;
        }
        Optional<UnitInPool> worker = Optional.empty();
        long gameLoop = agent.observation().getGameLoop();
        if (assignedWorker.isEmpty()) {
            if (gameLoop > nextAssignedWorkerAttempt) {
                assignedWorker = findWorker(taskManager, agent, data, placementRules);
                // Resume the construction if applicable.
                assignedWorker.ifPresent(theWorker -> {
                    matchingUnitAtLocation.ifPresent(tag -> {
                        UnitInPool unit = agent.observation().getUnit(tag);
                        if (unit != null) {
                            agent.actions().unitCommand(theWorker, Abilities.SMART, unit.unit(), false);
                        }
                    });
                });
            }
        } else {
            agent.observation().getActionErrors().stream().forEach(actionError -> {
                if (actionError.getUnitTag().equals(assignedWorker)) {
                    System.out.println("Assigned builder had an action error: " + actionError.getActionResult());
                    if (actionError.getActionResult() == ActionResult.COULDNT_REACH_TARGET) {
                        assignedWorker = Optional.empty();
                        return;
                    }
                }
            });
            worker = assignedWorker
                    .map(assignedWorkerTag -> agent.observation().getUnit(assignedWorkerTag));
            if (worker.isEmpty()) {
                if (assignedWorker.isPresent()) {
                    // Worker was assigned but not found - the worker probably died.
                    ++buildAttempts;
                    nextAssignedWorkerAttempt = gameLoop + (numWorkersUsed) * 22L;
                    ++numWorkersUsed;
                }
                assignedWorker = Optional.empty();
            }
        }
        if (matchingUnitAtLocation.isEmpty()) {
            final Optional<Point2d> locationToSearch = location.isPresent() ?
                    location :
                    worker.map(w -> w.unit().getPosition().toPoint2d());
            // Find any matching units within 1.5 range of target location
            List<UnitInPool> matchingUnits = agent.observation().getUnits(unitInPool ->
                    unitInPool.getUnit().filter(unit -> unit.getAlliance() == Alliance.SELF &&
                            unit.getType().equals(targetUnitType) &&
                            unit.getBuildProgress() < 0.99 &&
                            locationToSearch.map(targetLocation -> unit.getPosition().toPoint2d().distance(targetLocation) < 1.5)
                                    .orElse(false)
                    ).isPresent()
            );
            matchingUnitAtLocation = matchingUnits.stream().findFirst().map(unitInPool -> unitInPool.getTag());
        } else {
            matchingUnitAtLocation = matchingUnitAtLocation.filter(tag -> agent.observation().getUnit(tag) != null);
        }

        List<ActionError> actionErrors = agent.observation().getActionErrors();
        if (buildAttempts > MAX_BUILD_ATTEMPTS || actionErrors.stream().anyMatch(actionError -> {
            if (actionError.getUnitTag().equals(assignedWorker) &&
                    actionError.getActionResult() != ActionResult.NOT_ENOUGH_MINERALS &&
                    actionError.getActionResult() != ActionResult.NOT_ENOUGH_VESPENE &&
                    actionError.getActionResult() != ActionResult.COULDNT_REACH_TARGET) {
                System.out.println("Relevant action error: " + actionError.getActionResult());
                return true;
            }
            if (actionError.getUnitTag().equals(assignedWorker) || actionError.getAbility().equals(Optional.of(ability))) {
                System.out.println("Action error: " + actionError.getActionResult());
            }
            System.out.println("General Action error: " + actionError.getActionResult());
            return false;
        })) {
            agent.actions().sendChat("Failed: " + getDebugText(), ActionChat.Channel.TEAM);
            System.out.println("BuildTask " + targetUnitType + " failed");
            // Cancel the construction if applicable.
            matchingUnitAtLocation.ifPresent(tag -> {
                agent.actions().unitCommand(tag, Abilities.CANCEL, false);
            });
            isComplete = true;
            return;
        }

        if (worker.isPresent() &&
                !matchingUnitAtLocation.isPresent() &&
                minimumMinerals.map(minimum -> agent.observation().getMinerals() >= minimum).orElse(true) &&
                minimumVespene.map(minimum -> agent.observation().getVespene() >= minimum).orElse(true) &&
                !isWorkerOrderQueued(worker.get())) {
            if (gameLoop > lastBuildAttempt + BUILD_ATTEMPT_INTERVAL) {
                if (specificTarget.isPresent()) {
                    agent.actions().unitCommand(assignedWorker.get(), ability, specificTarget.get(), false);
                } else {
                    location = resolveLocation(worker.get(), data);
                    location.ifPresent(target ->
                        agent.actions().unitCommand(assignedWorker.get(), ability, target, false)
                    );
                }
                //System.out.println("BuildTask " + targetUnitType + " attempted (Attempt " + buildAttempts + ")");
                lastBuildAttempt = gameLoop;
                ++buildAttempts;
            }
        }
        if (matchingUnitAtLocation.isPresent()) {
            UnitInPool actualUnit = agent.observation().getUnit(matchingUnitAtLocation.get());
            if (actualUnit != null) {
                float buildProgress = actualUnit.unit().getBuildProgress();
                if (buildProgress > 0.99) {
                    isComplete = true;
                }
                // Cancel if low HP.
                float expectedHp = actualUnit.unit().getHealthMax().map(healthMax -> healthMax * buildProgress).orElse(1000f);
                if (actualUnit.unit().getHealth().map(health -> health < expectedHp * 0.25f).orElse(false)) {
                    matchingUnitAtLocation.ifPresent(tag -> {
                        agent.actions().unitCommand(tag, Abilities.CANCEL, false);
                    });
                }
            }
        }
        //System.out.println("Onstep for task " + targetUnitType.toString() + " (Worker: " + worker + ",
        // MatchingUnit: " + matchingUnitAtLocation + ")");
    }

    private Optional<Tag> findWorker(TaskManager taskManager, S2Agent agent, AgentData data, Optional<PlacementRules> placementRules) {
        // This should probably be a preidcate associated to the PlacementRules it itself.
        boolean nearBaseOnly = placementRules.isPresent() ?
                placementRules.get().regionType().equals(PlacementRules.Region.ANY_PLAYER_BASE) :
                false;
        if (location.isPresent()) {
            // If location is known, find closest unit to that location.
            return taskManager.findFreeUnitForTask(
                    this,
                    agent.observation(),
                    unitInPool -> unitInPool.unit() != null &&
                            Constants.WORKER_TYPES.contains(unitInPool.unit().getType()),
                    Comparator.comparing((UnitInPool unitInPool) ->
                            unitInPool.unit().getPosition().toPoint2d().distance(location.get()))
            ).map(unitInPool -> unitInPool.getTag());
        } else if (nearBaseOnly) {
            // If the placement rules require the structure in the base, search near the base only.
            Point2d baseLocation = data.mapAwareness()
                    .getDefenceLocation()
                    .orElse(agent.observation().getStartLocation().toPoint2d());
            return taskManager.findFreeUnitForTask(
                    this,
                    agent.observation(),
                    unitInPool -> unitInPool.unit() != null &&
                            Constants.WORKER_TYPES.contains(unitInPool.unit().getType()) &&
                            data.mapAwareness()
                                    .getRegionDataForPoint(unitInPool.unit().getPosition().toPoint2d())
                                    .map(RegionData::isPlayerBase).orElse(false),
                    Comparator.comparing((UnitInPool unitInPool) ->
                            unitInPool.unit().getPosition().toPoint2d().distance(baseLocation))
            ).map(unitInPool -> unitInPool.getTag());
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

    private Optional<Point2d> resolveLocation(UnitInPool worker, AgentData data) {
        //System.out.println("Worker: " + worker);
        return location.or(() -> data.structurePlacementCalculator().flatMap(spc -> spc.suggestLocationForFreePlacement(
                data,
                worker.unit().getPosition().toPoint2d(),
                20,
                ability,
                targetUnitType,
                placementRules)));
    }

    private float getRandomScalar() {
        return ThreadLocalRandom.current().nextFloat() * 2 - 1;
    }

    @Override
    public Optional<TaskResult> getResult() {
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
        if (this.location.isPresent()) {
            Point2d actualLocation = this.location.get();
            float height = agent.observation().terrainHeight(actualLocation);
            Point point3d = Point.of(actualLocation.getX(), actualLocation.getY(), height);
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
            Point point3d = unitInPool.unit().getPosition();
            Color color = Color.YELLOW;
            if (this.matchingUnitAtLocation.isPresent()) {
                color = Color.GREEN;
            }
            agent.debug().debugSphereOut(point3d, 1.0f, color);
            agent.debug().debugTextOut(
                    "Build " + targetUnitType.toString() + "\n" + buildAttempts + "/" + MAX_BUILD_ATTEMPTS,
                    point3d, Color.WHITE, 10);
        }
    }

    @Override
    public String getDebugText() {
        return "[" + buildAttempts + "/" + MAX_BUILD_ATTEMPTS + "] Build " + targetUnitType + " @ " + location.map(p2d -> p2d.getX() + "," + p2d.getY()).orElse("anywhere");
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
        return matchingUnitAtLocation.isEmpty() ? minimumMinerals.orElse(0) : 0;
    }

    @Override
    public int reservedVespene() {
        return matchingUnitAtLocation.isPresent() ? minimumVespene.orElse(0) : 0;
    }
}
