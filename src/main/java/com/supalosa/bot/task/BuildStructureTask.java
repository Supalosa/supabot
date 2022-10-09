package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.action.ActionError;
import com.github.ocraft.s2client.protocol.action.ActionResult;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public class BuildStructureTask implements Task {

    private static final long BUILD_ATTEMPT_INTERVAL = 5;
    private static final long MAX_BUILD_ATTEMPTS = 10;

    private final Ability ability;
    private final UnitType targetUnitType;
    private final Optional<Point2d> location;
    private final Optional<PlacementRules> placementRules;

    private Optional<Tag> matchingUnitAtLocation = Optional.empty();
    private Optional<Tag> assignedWorker = Optional.empty();

    private final String taskKey;
    private long lastBuildAttempt = 0;
    private long buildAttempts = 0;
    private boolean isComplete = false;

    public BuildStructureTask(Ability ability, UnitType targetUnitType, Optional<Point2d> location,
                              Optional<PlacementRules> placementRules) {
        this.ability = ability;
        this.targetUnitType = targetUnitType;
        this.location = location;
        this.placementRules = placementRules;
        this.taskKey = targetUnitType.toString() + "." + UUID.randomUUID();
    }

    @Override
    public void onStep(TaskManager taskManager, S2Agent agent) {
        Optional<UnitInPool> worker = Optional.empty();
        if (assignedWorker.isEmpty()) {
            assignedWorker = taskManager.findFreeUnit(
                    agent.observation(),
                    unitInPool -> unitInPool.unit() != null &&
                            unitInPool.unit().getType().equals(Units.TERRAN_SCV)).map(unitInPool -> unitInPool.getTag());
            taskManager.reserveUnit(assignedWorker, this);
            assignedWorker.ifPresent(tag -> System.out.println("Reserved unit " + tag));
        } else {
            worker = assignedWorker
                    .map(assignedWorkerTag -> agent.observation().getUnit(assignedWorkerTag));
            if (worker.isEmpty()) {
                assignedWorker = Optional.empty();
            }
        }
        if (matchingUnitAtLocation.isEmpty()) {
            // Find any matching units within 1.0 range of target location
            List<UnitInPool> matchingUnits = agent.observation().getUnits(unitInPool ->
                unitInPool.getUnit().filter(unit -> unit.getAlliance() == Alliance.SELF &&
                        unit.getType().equals(targetUnitType) &&
                        location.map(targetLocation -> unit.getPosition().toPoint2d().distance(targetLocation) < 1.5)
                                .orElse(false)
                ).isPresent()
            );
            matchingUnitAtLocation = matchingUnits.stream().findFirst().map(unitInPool -> unitInPool.getTag());
        } else {
            matchingUnitAtLocation = matchingUnitAtLocation.filter(tag -> agent.observation().getUnit(tag) != null);
        }

        long gameLoop = agent.observation().getGameLoop();
        List<ActionError> actionErrors = agent.observation().getActionErrors();
        if (buildAttempts > MAX_BUILD_ATTEMPTS || actionErrors.stream().anyMatch(actionError -> {
           if (actionError.getUnitTag().equals(assignedWorker)) {
               System.out.println("Relevant action error: " + actionError.getActionResult());
               return true;
           }
           return false;
        })) {
            agent.actions().sendChat("Failed: " + targetUnitType, ActionChat.Channel.TEAM);
            System.out.println("Task " + targetUnitType + " failed");
            isComplete = true;
        }

        if (worker.isPresent() && !matchingUnitAtLocation.isPresent() && !isWorkerOrderQueued(worker.get())) {
            if (gameLoop > lastBuildAttempt + BUILD_ATTEMPT_INTERVAL) {
                agent.actions().unitCommand(assignedWorker.get(), ability, resolveLocation(worker.get()), false);
                lastBuildAttempt = gameLoop;
                ++buildAttempts;
            }
        }
        if (matchingUnitAtLocation.isPresent()) {
            UnitInPool actualUnit = agent.observation().getUnit(matchingUnitAtLocation.get());
            if (actualUnit != null && actualUnit.getUnit().isPresent() && actualUnit.getUnit().get().getBuildProgress() > 0.99) {
                System.out.println(targetUnitType + ": " + actualUnit.getUnit().get().getBuildProgress());
                isComplete = true;
            }
            if (isComplete) {
                agent.actions().sendChat("Finished: " + targetUnitType, ActionChat.Channel.TEAM);
                System.out.println(targetUnitType + ": " + actualUnit.getUnit().get().getBuildProgress());
            }
        }
        //System.out.println("Onstep for task " + targetUnitType.toString() + " (Worker: " + worker + ", MatchingUnit: " + matchingUnitAtLocation + ")");
    }

    private boolean isWorkerOrderQueued(UnitInPool worker) {
        return worker.unit()
                .getOrders()
                .stream()
                .anyMatch(unitOrder -> ability.equals(unitOrder.getAbility()));
    }

    private Point2d resolveLocation(UnitInPool worker) {
        //System.out.println("Worker: " + worker);
        return location.orElseGet(() -> worker.unit().getPosition()
                    .toPoint2d()
                    .add(Point2d.of(getRandomScalar(), getRandomScalar())
                            .mul(15.0f)));
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
            BuildStructureTask other = (BuildStructureTask)otherTask;
            return (other.ability.equals(this.ability));
        } else {
            return false;
        }
    }
}
