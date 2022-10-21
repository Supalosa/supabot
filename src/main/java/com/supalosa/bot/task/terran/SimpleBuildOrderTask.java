package com.supalosa.bot.task.terran;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitTypeData;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.builds.BuildOrderOutput;
import com.supalosa.bot.builds.SimpleBuildOrder;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.TaskResult;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.utils.UnitFilter;
import com.github.ocraft.s2client.protocol.unit.Tag;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A task that follows a {@code SimpleBuildOrder}.
 */
public class SimpleBuildOrderTask implements Task {

    private SimpleBuildOrder simpleBuildOrder;

    private boolean isComplete = false;

    public SimpleBuildOrderTask(SimpleBuildOrder simpleBuildOrder) {
        this.simpleBuildOrder = simpleBuildOrder;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        this.simpleBuildOrder.onStep(agent.observation());
        Optional<BuildOrderOutput> output = this.simpleBuildOrder.getOutput(agent.observation());
        if (output.isPresent()) {
            Optional<Unit> orderedUnit = resolveUnitToUse(agent, output.get());
            orderedUnit.ifPresent(unit -> {
            });
        }
        if (this.simpleBuildOrder.isComplete()) {
            this.isComplete = true;
        }
    }

    private Optional<Unit> resolveUnitToUse(S2Agent agent, BuildOrderOutput buildOrderOutput) {
        ObservationInterface observationInterface = agent.observation();
        if (buildOrderOutput.abilityToUse().isPresent() && buildOrderOutput.eligibleUnitTypes().isPresent()) {
            Ability ability = buildOrderOutput.abilityToUse().get();
            UnitFilter eligibleUnitTypes = buildOrderOutput.eligibleUnitTypes().get();
            // HACK: if scv then we don't assign a worker as BuildOrderTask does its own assigning
            // What is the best way around this? Probably by also assigning tasks for unit production.
            if (eligibleUnitTypes.unitType().isPresent() &&
                    eligibleUnitTypes.unitType().get().equals(Units.TERRAN_SCV)) {
                return Optional.empty();
            } else {
                // Select a eligible unit to execute the task.
                List<UnitInPool> eligibleUnits = observationInterface.getUnits(eligibleUnitTypes);
                    if (Constants.TERRAN_TECHLAB_TYPES.contains(buildOrderOutput.addonRequired().get())) {
                        Set<Tag> techLabs = observationInterface
                                .getUnits(UnitFilter.mine(Constants.TERRAN_TECHLAB_TYPES)).stream()
                                .map(UnitInPool::getTag).collect(Collectors.toSet());
                        eligibleUnits = eligibleUnits.stream()
                                .filter(unit -> techLabs.contains(unit.unit().getAddOnTag()))
                                .collect(Collectors.toList());
                    } else if (Constants.TERRAN_REACTOR_TYPES.contains(buildOrderOutput.addonRequired().get())) {
                        Set<Tag> reactors = observationInterface
                                .getUnits(UnitFilter.mine(Constants.TERRAN_REACTOR_TYPES)).stream()
                                .map(UnitInPool::getTag).collect(Collectors.toSet());
                        if (buildOrderOutput.addonRequired().isPresent()) {
                        eligibleUnits = eligibleUnits.stream()
                                .filter(unit -> reactors.contains(unit.unit().getAddOnTag()))
                                .collect(Collectors.toList());
                    } else {
                        throw new IllegalStateException("Unsupported addon type used in build: " + buildOrderOutput.addonRequired().get());
                    }
                }
                return eligibleUnits.stream()
                        .map(UnitInPool::unit)
                        .filter(unit -> unit.getOrders().isEmpty())
                        .findAny();
            }
        } else {
            return Optional.empty();
        }
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
        return "BuildOrder";
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        return otherTask instanceof SimpleBuildOrderTask;
    }

    @Override
    public void debug(S2Agent agent) {
        float xPosition = 0.75f;
        agent.debug().debugTextOut(
                "Build Order (" + simpleBuildOrder.getCurrentStageNumber() + "/" + simpleBuildOrder.getTotalStages() + ")",
                Point2d.of(xPosition, 0.5f), Color.WHITE, 8);
        final float spacing = 0.0125f;
        float yPosition = 0.51f;
        Optional<BuildOrderOutput> next = simpleBuildOrder.getOutput(agent.observation());
        if (next.isPresent()) {
            String text = next.get().abilityToUse().isPresent() ? next.get().abilityToUse().get().toString() : "None";
            agent.debug().debugTextOut(text, Point2d.of(xPosition, yPosition), Color.WHITE, 8);
            yPosition += (spacing);
        }
    }

    @Override
    public String getDebugText() {
        return "Build Order " + simpleBuildOrder.getCurrentStageNumber() + "/" + simpleBuildOrder.getTotalStages();
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
    }
}
