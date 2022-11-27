package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.AvailableAbility;
import com.github.ocraft.s2client.protocol.query.AvailableAbilities;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.GameData;
import com.supalosa.bot.builds.BuildOrder;
import com.supalosa.bot.builds.BuildOrderOutput;
import com.supalosa.bot.builds.SimpleBuildOrder;
import com.supalosa.bot.builds.SimpleBuildOrderStage;
import com.supalosa.bot.placement.PlacementRules;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.task.terran.BuildUtils;
import com.supalosa.bot.utils.CalculateCurrentConstructionTasksVisitor;
import com.supalosa.bot.utils.UnitFilter;
import com.github.ocraft.s2client.protocol.unit.Tag;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Executor for a {@code BuildOrder}.
 * You must specify a {@code BehaviourTask} that should be dispatched as soon as the build order is
 * complete.
 */
public class SimpleBuildOrderTask extends BaseTask {

    private static final long REBALANCE_INTERVAL = 22L * 60;
    private BuildOrder currentBuildOrder;
    private Map<BuildOrderOutput, Long> orderDispatchedAt = new HashMap<>();
    private static final long TIME_BETWEEN_DISPATCHES = 22L * 10;
    private Map<Tag, Long> orderDispatchedTo = new HashMap<>();
    private static final long ORDER_RESERVATION_TIME = 22L * 1;
    private Optional<Supplier<BuildOrder>> nextBuildOrder;

    private int targetGasMiners = 0;

    private long lastGasCheck = 0L;
    private long lastRebalanceAt = 0L;

    private List<BuildOrderOutput> lastOutput = new ArrayList<>();
    private boolean hasAnnouncedFailure = false;

    public SimpleBuildOrderTask(BuildOrder buildOrder, Supplier<BuildOrder> nextBuildOrder) {
        super();
        this.currentBuildOrder = buildOrder;
        this.nextBuildOrder = Optional.of(nextBuildOrder);
    }

    @Override
    public void onStep(TaskManager taskManager, AgentWithData agentWithData) {
        this.currentBuildOrder.onStep(agentWithData);
        mineGas(agentWithData);
        rebalanceWorkers(agentWithData);
        long gameLoop = agentWithData.observation().getGameLoop();
        Set<Tag> reservedTags = new HashSet<>();
        Set<Tag> reactors = agentWithData.observation()
                .getUnits(UnitFilter.mine(Constants.TERRAN_REACTOR_TYPES)).stream()
                .map(UnitInPool::getTag).collect(Collectors.toSet());

        // Determine how many parallel tasks of a certain type are currently running.
        Map<Ability, Integer> currentParallelAbilities = computeCurrentParallelAbilities(agentWithData);

        List<BuildOrderOutput> outputs = this.currentBuildOrder.getOutput(agentWithData, currentParallelAbilities);

        Map<Ability, Integer> expectedParallelAbilities = computeExpectedParallelAbilities(outputs);
        this.lastOutput = outputs;

        // Dispatch any outputs that aren't currently running.
        outputs.forEach(output -> {
            output.performAttack().ifPresent(doPerformAttack -> {
                agentWithData.fightManager().setCanAttack(doPerformAttack);
                if (doPerformAttack) {
                    agentWithData.fightManager().reinforceAttackingArmy();
                }
            });
            output.dispatchTask().ifPresent(taskToDispatch -> {
                taskManager.addTask(taskToDispatch.get(), 1);
            });
            output.setGasMiners().ifPresent(target -> {
                this.targetGasMiners = target;
            });
            if (output.abilityToUse().isEmpty()) {
                // If there is no ability, the stage automatically succeeds.
                currentBuildOrder.onStageStarted(agentWithData, agentWithData, output);
                return;
            }
            Ability ability = output.abilityToUse().get();
            Optional<Unit> orderedUnit = resolveUnitToUse(agentWithData, output);
            if (orderDispatchedAt.containsKey(output)) {
                if (gameLoop < orderDispatchedAt.get(output) + TIME_BETWEEN_DISPATCHES) {
                    return;
                } else {
                    orderDispatchedAt.remove(output);
                }
            }
            int maxParallel = expectedParallelAbilities.getOrDefault(ability, 0);
            if (maxParallel <= 0) {
                return;
            }
            int currentParallel = currentParallelAbilities.getOrDefault(ability, 0);
            if (currentParallel >= maxParallel) {
                return;
            }
            if (orderedUnit.isPresent()) {
                boolean isAvailable = agentWithData.gameData().unitHasAbility(orderedUnit.get().getTag(), ability);
                if (isAvailable) {
                    agentWithData.actions().unitCommand(orderedUnit.get(), ability, false);
                    // Signal to the build order that the ability was used.
                    currentBuildOrder.onStageStarted(agentWithData, agentWithData, output);
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
            } else if (output.placementRules().isPresent()) {
                Task buildTask;
                if (output.placementRules().get().on().isPresent()) {
                    buildTask = createBuildTask(agentWithData.gameData(),
                            ability,
                            output.placementRules().get().on().get(),
                            output.placementRules());
                } else {
                    buildTask = createBuildTask(agentWithData.gameData(), ability, output.placementRules());
                }
                if (taskManager.addTask(buildTask, maxParallel)) {
                    final BuildOrderOutput thisStage = output;
                    buildTask.onStarted(result -> {
                                // Signal to the build order that the construction was started (dispatched).
                                if (!isComplete()) {
                                    currentBuildOrder.onStageStarted(agentWithData, agentWithData, thisStage);
                                }
                            })
                            .onFailure(result -> {
                                if (!isComplete() && !output.repeat()) {
                                    changeBuild();
                                    this.onFailure();
                                    System.out.println("Build task " + ability + " failed, aborting build order.");
                                    announceFailure(agentWithData.observation(), agentWithData.actions());
                                    currentBuildOrder.onStageFailed(thisStage, agentWithData);
                                }
                            })
                            .onComplete(result -> {
                                if (!isComplete() && !output.repeat()) {
                                    currentBuildOrder.onStageCompleted(thisStage, agentWithData);
                                }
                            });
                    orderDispatchedAt.put(output, gameLoop);
                }
            } else if (ability.getTargets().contains(Target.UNIT)) {
                 if (Constants.BUILD_GAS_STRUCTURE_ABILITIES.contains(ability)) {
                    Optional<Unit> freeGeyserNearCc = BuildUtils.getBuildableGeyser(agentWithData.observation());
                    freeGeyserNearCc.ifPresent(geyser -> {
                        if (taskManager.addTask(createBuildTask(agentWithData.gameData(), ability, geyser, output.placementRules()), maxParallel)) {
                            orderDispatchedAt.put(output, gameLoop);
                            currentBuildOrder.onStageStarted(agentWithData, agentWithData, output);
                        }
                    });
                }
            }
        });

        if (this.currentBuildOrder.isComplete()) {
            changeBuild();
        } else if (this.currentBuildOrder.isTimedOut()) {
            changeBuild();
            announceFailure(agentWithData.observation(), agentWithData.actions());
        }

        // TODO move this to a task.
        BuildUtils.defaultTerranRamp(agentWithData);
    }

    private Map<Ability, Integer> computeExpectedParallelAbilities(List<BuildOrderOutput> outputs) {
        Map<Ability, Integer> result = new HashMap<>();
        outputs.forEach(output -> {
            output.abilityToUse().ifPresent(ability -> {
                result.merge(ability, 1, Integer::sum);
            });
        });
        return result;
    }

    /**
     * Compute how many abilities are currently queued (not just considering abilities actually in use,
     * but also soon to be in use, e.g. via Build Tasks that have been dispatched.
     */
    private Map<Ability, Integer> computeCurrentParallelAbilities(AgentWithData agentWithData) {
        Map<Ability, Integer> result = new HashMap<>();
        Set<Tag> consideredUnits = new HashSet<>();

        // Get abilities in each structures' queue.
        agentWithData.observation().getUnits(Alliance.SELF).forEach(unitInPool -> {
            unitInPool.unit().getOrders().stream().forEach(abilityQueued -> {
                result.merge(abilityQueued.getAbility(), 1, Integer::sum);
            });
            consideredUnits.add(unitInPool.getTag());
        });

        // Get abilities in Build Tasks that haven't been assigned yet.
        Map<Ability, Integer> constructionTasks = agentWithData.taskManager().visitTasks(new CalculateCurrentConstructionTasksVisitor());
        constructionTasks.forEach((ability, count) -> {
            result.merge(ability, count, Integer::sum);
        });

        return result;
    }

    // Renders a list of outputs to a friendly string that can go in a chat message.
    private String renderOutputsToString(List<BuildOrderOutput> outputs) {
        StringBuilder builder = new StringBuilder();
        outputs.forEach(output -> {
            builder.append(output.asHumanReadableString() + ", ");
        });
        return builder.toString();
    }

    private void changeBuild() {
        if (nextBuildOrder.isPresent()) {
            currentBuildOrder = nextBuildOrder.get().get();
            nextBuildOrder = Optional.empty();
        }
    }

    private void announceFailure(ObservationInterface observationInterface, ActionInterface actionInterface) {
        // Hack, implement this better.
        if (!(currentBuildOrder instanceof SimpleBuildOrder)) {
            return;
        }
        SimpleBuildOrder currentSimpleBuildOrder = (SimpleBuildOrder) currentBuildOrder;
        if (hasAnnouncedFailure) {
            return;
        }
        hasAnnouncedFailure = true;
        String outputsAsString = renderOutputsToString(lastOutput);
        actionInterface.sendChat("Tag: BuildFail " + outputsAsString, ActionChat.Channel.BROADCAST);
        currentBuildOrder.getVerboseDebugText().forEach(verboseDebugText -> {
            actionInterface.sendChat(verboseDebugText, ActionChat.Channel.BROADCAST);
            System.out.println("[Build] " + verboseDebugText);
        });
    }

    private void mineGas(AgentWithData agentWithData) {
        long gameLoop = agentWithData.observation().getGameLoop();
        if (gameLoop < lastGasCheck + 5) {
            return;
        }
        lastGasCheck = gameLoop;
        BuildUtils.reassignGasWorkers(agentWithData, 0, targetGasMiners);
    }

    private void rebalanceWorkers(AgentWithData agentWithData) {
        long gameLoop = agentWithData.observation().getGameLoop();
        if (gameLoop < lastRebalanceAt + REBALANCE_INTERVAL) {
            return;
        }
        lastRebalanceAt = gameLoop;
        BuildUtils.rebalanceWorkers(agentWithData);
    }

    private BuildStructureTask createBuildTask(GameData data, Ability abilityTypeForStructure, Unit targetUnitToBuildOn, Optional<PlacementRules> placementRules) {
        Optional<UnitType> unitTypeForStructure = data.getUnitBuiltByAbility(abilityTypeForStructure);
        return new BuildStructureTask(
                abilityTypeForStructure,
                unitTypeForStructure.orElseThrow(() -> new IllegalArgumentException("No unit type known for " + abilityTypeForStructure)),
                Optional.empty(),
                Optional.of(targetUnitToBuildOn),
                unitTypeForStructure.flatMap(type -> data.getUnitMineralCost(type)),
                unitTypeForStructure.flatMap(type -> data.getUnitVespeneCost(type)),
                placementRules);

    }

    private Task createBuildTask(GameData data, Ability abilityTypeForStructure, Optional<PlacementRules> placementRules) {
        Optional<UnitType> unitTypeForStructure = data.getUnitBuiltByAbility(abilityTypeForStructure);
        return new BuildStructureTask(
                abilityTypeForStructure,
                unitTypeForStructure.orElseThrow(() -> new IllegalArgumentException("No unit type known for " + abilityTypeForStructure)),
                Optional.empty(),
                Optional.empty(),
                unitTypeForStructure.flatMap(type -> data.getUnitMineralCost(type)),
                unitTypeForStructure.flatMap(type -> data.getUnitVespeneCost(type)),
                placementRules);
    }

    private Optional<Unit> resolveUnitToUse(S2Agent agentWithData, BuildOrderOutput buildOrderOutput) {
        ObservationInterface observationInterface = agentWithData.observation();
        if (buildOrderOutput.abilityToUse().isPresent() && buildOrderOutput.eligibleUnitTypes().isPresent()) {
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
                Set<Tag> reactors = agentWithData.observation()
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
        } else if (buildOrderOutput.specificUnit().isPresent()) {
            UnitInPool unitInPool = agentWithData.observation().getUnit(buildOrderOutput.specificUnit().get());
            if (unitInPool != null) {
                return unitInPool.getUnit();
            } else {
                return Optional.empty();
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
        return false;
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
    public void debug(S2Agent agentWithData) {
        float xPosition = 0.75f;
        agentWithData.debug().debugTextOut(
                "Build Order:",
                Point2d.of(xPosition, 0.5f), Color.WHITE, 8);
        final float spacing = 0.0125f;
        float yPosition = 0.51f;
        List<BuildOrderOutput> nextStages = lastOutput;
        Optional<String> previousValue = Optional.empty();
        int foldedCount = 1;
        for (BuildOrderOutput next : nextStages) {
            if (previousValue.isPresent()) {
                if (previousValue.get().equals(next.asHumanReadableString())) {
                    ++foldedCount;
                } else if (yPosition < 1f) {
                    agentWithData.debug().debugTextOut(previousValue.get() + " x " + foldedCount,
                            Point2d.of(xPosition, yPosition), Color.WHITE, 8);
                    foldedCount = 1;
                    yPosition += (spacing);
                }
            }
            previousValue = Optional.of(next.asHumanReadableString());
        }
        if (yPosition < 1f && previousValue.isPresent()) {
            agentWithData.debug().debugTextOut(previousValue.get() + " x " + foldedCount,
                    Point2d.of(xPosition, yPosition), Color.WHITE, 8);
        }
    }

    @Override
    public String getDebugText() {
        return "Build Order " + currentBuildOrder.getDebugText();
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
    }
}
