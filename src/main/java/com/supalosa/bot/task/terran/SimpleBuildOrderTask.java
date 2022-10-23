package com.supalosa.bot.task.terran;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.Action;
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
import com.supalosa.bot.builds.SimpleBuildOrderStage;
import com.supalosa.bot.task.*;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.utils.UnitFilter;
import com.github.ocraft.s2client.protocol.unit.Tag;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A {@code BehaviourTask} that follows a {@code SimpleBuildOrder}.
 * You must specify a {@code BehaviourTask} that should be dispatched as soon as the build order is
 * complete.
 */
public class SimpleBuildOrderTask extends BaseTask implements BehaviourTask {

    private static final long REBALANCE_INTERVAL = 22L * 60;
    private SimpleBuildOrder simpleBuildOrder;
    private Map<BuildOrderOutput, Long> orderDispatchedAt = new HashMap<>();
    private static final long TIME_BETWEEN_DISPATCHES = 22L * 10;
    private Map<Tag, Long> orderDispatchedTo = new HashMap<>();
    private static final long ORDER_RESERVATION_TIME = 22L * 1;
    private Supplier<BehaviourTask> nextBehaviourTask;
    private Map<Ability, Integer> expectedMaxParallelOrdersForAbility = new HashMap<>();
    private Set<BuildOrderOutput> seenOrders = new HashSet<>();

    private long lastGasCheck = 0L;
    private long lastRebalanceAt = 0L;

    private boolean isComplete = false;
    private List<BuildOrderOutput> lastOutput = new ArrayList<>();
    private Optional<SimpleBuildOrderStage> nextStage = Optional.empty();

    public SimpleBuildOrderTask(SimpleBuildOrder simpleBuildOrder, Supplier<BehaviourTask> nextBehaviourTask) {
        super();
        this.simpleBuildOrder = simpleBuildOrder;
        this.nextBehaviourTask = nextBehaviourTask;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        this.simpleBuildOrder.onStep(agent.observation(), data.gameData());
        List<BuildOrderOutput> outputs = this.simpleBuildOrder.getOutput(agent.observation(), data.gameData());
        this.nextStage = this.simpleBuildOrder.getWaitingStage(agent.observation(), data.gameData());
        this.lastOutput = outputs;
        mineGas(agent);
        rebalanceWorkers(agent);
        long gameLoop = agent.observation().getGameLoop();
        Set<Tag> reservedTags = new HashSet<>();
        Set<Tag> reactors = agent.observation()
                .getUnits(UnitFilter.mine(Constants.TERRAN_REACTOR_TYPES)).stream()
                .map(UnitInPool::getTag).collect(Collectors.toSet());
        // Determine how many parallel tasks of a certain type should be running.
        outputs.forEach(output -> {
            if (output.performAttack().isPresent()) {
                data.fightManager().setCanAttack(output.performAttack().get());
                // An attack order was given.
                if (output.performAttack().get() == true) {
                    data.fightManager().reinforceAttackingArmy();
                }
            }
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
            if (!seenOrders.contains(output)) {
                // First time seeing this order: allow one more parallel construction of it.
                expectedMaxParallelOrdersForAbility.compute(output.abilityToUse().get(), (k, v) -> v == null ? 1 : v + 1);
                seenOrders.add(output);
            }
            int maxParallel = expectedMaxParallelOrdersForAbility.getOrDefault(output.abilityToUse().get(), 0);
            if (maxParallel <= 0) {
                //throw new IllegalStateException("Did not expect to see this ability used.");
                return;
            }
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
                    // Signal to the build order that the ability was used.
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
                Task buildTask = createBuildTask(data.gameData(), output.abilityToUse().get(), output.placementRules());
                if (taskManager.addTask(buildTask, maxParallel)) {
                    final BuildOrderOutput thisStage = output;
                    buildTask
                            .onStarted(result -> {
                                // Signal to the build order that the construction was started (dispatched).
                                simpleBuildOrder.onStageStarted(agent, data, thisStage);
                            })
                            .onFailure(result -> {
                                this.isComplete = true;
                                onFailure();
                                System.out.println("Build task " + output.abilityToUse().get() + " failed, aborting build order.");
                                expectedMaxParallelOrdersForAbility.compute(output.abilityToUse().get(), (k, v) -> v == null ? 0 : v - 1);
                                announceFailure(agent.observation(), agent.actions());
                            })
                            .onComplete(result -> {
                                expectedMaxParallelOrdersForAbility.compute(output.abilityToUse().get(), (k, v) -> v == null ? 0 : v - 1);
                            });
                    orderDispatchedAt.put(output, gameLoop);
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
            this.isComplete = true;
            onComplete();
        } else if (this.simpleBuildOrder.isTimedOut()) {
            this.isComplete = true;
            announceFailure(agent.observation(), agent.actions());
            onFailure();
        }

        // TODO move this to a task.
        BuildUtils.defaultTerranRamp(data, agent);
    }

    // Renders a list of outputs to a friendly string that can go in a chat message.
    private String renderOutputsToString(List<BuildOrderOutput> outputs) {
        StringBuilder builder = new StringBuilder();
        outputs.forEach(output -> {
            builder.append(output.asHumanReadableString() + ", ");
        });
        return builder.toString();
    }

    private void announceFailure(ObservationInterface observationInterface, ActionInterface actionInterface) {
        String outputsAsString = renderOutputsToString(lastOutput);
        actionInterface.sendChat("tag:buildorder-terminated-" +
                simpleBuildOrder.getCurrentStageNumber() + "/" + simpleBuildOrder.getTotalStages() + "/" + observationInterface.getGameLoop(),
                ActionChat.Channel.BROADCAST);
        actionInterface.sendChat("tag:buildorder-current-" + outputsAsString, ActionChat.Channel.BROADCAST);
        nextStage.ifPresent(next ->
                actionInterface.sendChat("tag:buildorder-next-trigger-" + next.trigger().toString(), ActionChat.Channel.BROADCAST));
        System.out.println("Build order terminated at stage " + simpleBuildOrder.getCurrentStageNumber() + ": " + outputsAsString);
        nextStage.ifPresent(next ->
                System.out.println("Next stage trigger: " + next.trigger().toString()));
    }

    private void mineGas(S2Agent agent) {
        long gameLoop = agent.observation().getGameLoop();
        if (gameLoop < lastGasCheck + 5) {
            return;
        }
        lastGasCheck = gameLoop;
        BuildUtils.reassignGasWorkers(agent, 0, simpleBuildOrder.getMaximumGasMiners());
    }

    private void rebalanceWorkers(S2Agent agent) {
        long gameLoop = agent.observation().getGameLoop();
        if (gameLoop < lastRebalanceAt + REBALANCE_INTERVAL) {
            return;
        }
        lastRebalanceAt = gameLoop;
        BuildUtils.rebalanceWorkers(agent);
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

    private Task createBuildTask(GameData data, Ability abilityTypeForStructure, Optional<PlacementRules> placementRules) {
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
        List<BuildOrderOutput> nextStages = lastOutput;
        for (BuildOrderOutput next : nextStages) {
            agent.debug().debugTextOut(next.asHumanReadableString(), Point2d.of(xPosition, yPosition), Color.WHITE, 8);
            yPosition += (spacing);
        }
        if (nextStage.isPresent()) {
            agent.debug().debugTextOut(
                    "Next @ " + nextStage.get().trigger() + ": " + nextStage.get().ability().map(Ability::toString).orElse("Unknown"),
                    Point2d.of(xPosition, yPosition), Color.WHITE, 8);
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

    @Override
    public Supplier<BehaviourTask> getNextBehaviourTask() {
        return nextBehaviourTask;
    }
}