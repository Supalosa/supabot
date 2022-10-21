package com.supalosa.bot.task.terran;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.AvailableAbility;
import com.github.ocraft.s2client.protocol.query.AvailableAbilities;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.GameData;
import com.supalosa.bot.builds.Build;
import com.supalosa.bot.builds.BuildOrderOutput;
import com.supalosa.bot.builds.SimpleBuildOrder;
import com.supalosa.bot.task.*;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.utils.UnitFilter;
import com.github.ocraft.s2client.protocol.unit.Tag;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A {@code BehaviourTask} that follows a {@code SimpleBuildOrder}.
 * You must specify a {@code BehaviourTask} that should be dispatched as soon as the build order is
 * complete.
 */
public class SimpleBuildOrderTask implements BehaviourTask {

    private SimpleBuildOrder simpleBuildOrder;
    private Map<BuildOrderOutput, Long> orderDispatchedAt = new HashMap<>();
    private static final long TIME_BETWEEN_DISPATCHES = 5;
    private Map<Tag, Long> orderDispatchedTo = new HashMap<>();
    private static final long ORDER_RESERVATION_TIME = 20;
    private BehaviourTask nextBehaviourTask;

    private long lastGasCheck = 0L;

    private boolean isComplete = false;

    public SimpleBuildOrderTask(SimpleBuildOrder simpleBuildOrder, BehaviourTask nextBehaviourTask) {
        this.simpleBuildOrder = simpleBuildOrder;
        this.nextBehaviourTask = nextBehaviourTask;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        this.simpleBuildOrder.onStep(agent.observation(), data.gameData());
        List<BuildOrderOutput> outputs = this.simpleBuildOrder.getOutput(agent.observation());
        mineGas(agent);
        long gameLoop = agent.observation().getGameLoop();
        Set<Tag> reservedTags = new HashSet<>();
        Set<Tag> reactors = agent.observation()
                .getUnits(UnitFilter.mine(Constants.TERRAN_REACTOR_TYPES)).stream()
                .map(UnitInPool::getTag).collect(Collectors.toSet());
        outputs.forEach(output -> {
            if (output.abilityToUse().isEmpty()) {
                simpleBuildOrder.onStageStarted(agent, data, output);
                return;
            }
            Optional<Unit> orderedUnit = resolveUnitToUse(agent, output);
            if (orderDispatchedAt.containsKey(output)) {
                if (gameLoop < orderDispatchedAt.get(output) + TIME_BETWEEN_DISPATCHES) {
                    return;
                } else {
                    orderDispatchedAt.remove(output);
                }
            }
            int maxParallel = Integer.MAX_VALUE;
            if (orderedUnit.isPresent()) {
                // TODO not use query here...
                AvailableAbilities abilities = agent.query().getAbilitiesForUnit(orderedUnit.get(), false);
                boolean isAvailable = false;
                Optional<AbilityData> abilityData = data.gameData().getAbility(output.abilityToUse().get());
                for (AvailableAbility availableAbility : abilities.getAbilities()) {
                    if (availableAbility.getAbility().equals(output.abilityToUse().get())) {
                        isAvailable = true;
                    } else if (abilityData.isPresent() &&
                            abilityData.get().getRemapsToAbility().equals(Optional.of(availableAbility.getAbility()))) {
                        // Check if the requested ability remaps to the available ability (eg reactor_barracks to reactor).
                        isAvailable = true;
                    }
                }
                if (isAvailable) {
                    agent.actions().unitCommand(orderedUnit.get(), output.abilityToUse().get(), false);
                    simpleBuildOrder.onStageStarted(agent, data, output);
                    boolean reserveUnit = true;
                    // Units with reactors can receive multiple orders.
                    if (orderedUnit.get().getAddOnTag().isPresent()) {
                        if (reactors.contains(orderedUnit.get().getAddOnTag().get())) {
                            if (orderedUnit.get().getOrders().isEmpty()) {
                                reserveUnit = false;
                            }
                        }
                    }
                    if (reserveUnit) {
                        // Prevent the unit from being used again this loop.
                        reservedTags.add(orderedUnit.get().getTag());
                        // Prevent the unit from being chosen again in resolveUnitToUse.
                        orderDispatchedTo.put(orderedUnit.get().getTag(), gameLoop);
                        orderDispatchedAt.put(output, gameLoop);
                    }
                }
            } else if (output.abilityToUse().get().getTargets().contains(Target.POINT)) {
                if (taskManager.addTask(
                        createBuildTask(data.gameData(), output.abilityToUse().get(), output.placementRules()), maxParallel)) {
                    orderDispatchedAt.put(output, gameLoop);
                    simpleBuildOrder.onStageStarted(agent, data, output);
                }
            } else if (output.abilityToUse().get().getTargets().contains(Target.UNIT)) {
                 if (Constants.BUILD_GAS_STRUCTURE_ABILITIES.contains(output.abilityToUse().get())) {
                    Optional<Unit> freeGeyserNearCc = BuildUtils.getBuildableGeyser(agent.observation());
                    freeGeyserNearCc.ifPresent(geyser -> {
                        if (taskManager.addTask(createBuildTask(data.gameData(), output.abilityToUse().get(), geyser, output.placementRules()), maxParallel)) {
                            orderDispatchedAt.put(output, gameLoop);
                            simpleBuildOrder.onStageStarted(agent, data, output);
                        }
                    });
                }
            }
        });
        if (this.simpleBuildOrder.isComplete()) {
            if (taskManager.addTask(nextBehaviourTask, 1)) {
                agent.actions().sendChat("Build order terminated at " +
                        simpleBuildOrder.getCurrentStageNumber() + "/" + simpleBuildOrder.getTotalStages(), ActionChat.Channel.TEAM);
                this.isComplete = true;
            } else {
                throw new IllegalStateException("Could not dispatch the next behaviour task.");
            }
        }
    }

    void mineGas(S2Agent agent) {
        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop < lastGasCheck + 5) {
            return;
        }
        lastGasCheck = gameLoop;
        BuildUtils.reassignGasWorkers(agent, 0, simpleBuildOrder.getMaximumGasMiners());
    }

    private BuildStructureTask createBuildTask(GameData data, Ability abilityTypeForStructure, Unit targetUnitToBuildOn, Optional<PlacementRules> placementRules) {
        Optional<UnitType> unitTypeForStructure = data.getUnitBuiltByAbilility(abilityTypeForStructure);
        return new BuildStructureTask(
                abilityTypeForStructure,
                unitTypeForStructure.orElseThrow(() -> new IllegalArgumentException("No unit type known for " + abilityTypeForStructure)),
                Optional.empty(),
                Optional.of(targetUnitToBuildOn),
                unitTypeForStructure.flatMap(type -> data.getUnitMineralCost(type)),
                unitTypeForStructure.flatMap(type -> data.getUnitVespeneCost(type)),
                placementRules);

    }

    private BuildStructureTask createBuildTask(GameData data, Ability abilityTypeForStructure, Optional<PlacementRules> placementRules) {
        Optional<UnitType> unitTypeForStructure = data.getUnitBuiltByAbilility(abilityTypeForStructure);
        return new BuildStructureTask(
                abilityTypeForStructure,
                unitTypeForStructure.orElseThrow(() -> new IllegalArgumentException("No unit type known for " + abilityTypeForStructure)),
                Optional.empty(),
                Optional.empty(),
                unitTypeForStructure.flatMap(type -> data.getUnitMineralCost(type)),
                unitTypeForStructure.flatMap(type -> data.getUnitVespeneCost(type)),
                placementRules);
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
                // Select an eligible unit to execute the task. A unit that was assigned an action less than
                // ORDER_RESERVATION_TIME ago is not eligible for another action this cycle.
                final long gameLoop = observationInterface.getGameLoop();
                List<UnitInPool> eligibleUnits = observationInterface.getUnits(eligibleUnitTypes)
                        .stream().filter(unit ->
                                gameLoop > orderDispatchedTo.getOrDefault(unit.getTag(), 0L) + ORDER_RESERVATION_TIME)
                        .collect(Collectors.toList());
                Set<Tag> reactors = agent.observation()
                        .getUnits(UnitFilter.mine(Constants.TERRAN_REACTOR_TYPES)).stream()
                        .map(UnitInPool::getTag).collect(Collectors.toSet());
                if (buildOrderOutput.addonRequired().isPresent()) {
                    if (buildOrderOutput.addonRequired().filter(addon -> Constants.TERRAN_TECHLAB_TYPES.contains(addon)).isPresent()) {
                        Set<Tag> techLabs = observationInterface
                                .getUnits(UnitFilter.mine(Constants.TERRAN_TECHLAB_TYPES)).stream()
                                .map(UnitInPool::getTag).collect(Collectors.toSet());
                        eligibleUnits = eligibleUnits.stream()
                                .filter(unit -> unit.unit().getAddOnTag().isPresent() && techLabs.contains(unit.unit().getAddOnTag().get()))
                                .collect(Collectors.toList());
                    } else if (buildOrderOutput.addonRequired().filter(addon -> Constants.TERRAN_REACTOR_TYPES.contains(addon)).isPresent()) {
                        eligibleUnits = eligibleUnits.stream()
                                .filter(unit -> unit.unit().getAddOnTag().isPresent() && reactors.contains(unit.unit().getAddOnTag().get()))
                                .collect(Collectors.toList());
                    } else {
                        throw new IllegalStateException("Unsupported addon type used in build: " + buildOrderOutput.addonRequired().get());
                    }
                }
                return eligibleUnits.stream()
                        .map(UnitInPool::unit)
                        .filter(unit -> unit.getOrders().isEmpty() ||
                                (unit.getAddOnTag().isPresent() &&
                                        reactors.contains(unit.getAddOnTag().get()) &&
                                        unit.getOrders().size() <= 1))
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
        List<BuildOrderOutput> nextStages = simpleBuildOrder.getOutput(agent.observation());
        for (BuildOrderOutput next : nextStages) {
            String text = next.abilityToUse().isPresent() ? next.abilityToUse().get().toString() : "None";
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
