package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.FightManager;
import com.supalosa.bot.GameData;
import io.vertx.codegen.doc.Tag;
import org.apache.commons.lang3.Validate;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SimpleBuildOrder implements BuildOrder {

    // Max time to spend on a stage before we decide the build order is not worth continuing.
    private static final long MAX_STAGE_TIME = 22L * 60;

    private final List<SimpleBuildOrderStage> initialStages;
    private final LinkedHashSet<SimpleBuildOrderStage> inProgressStages;
    private final LinkedHashSet<SimpleBuildOrderStage> remainingStages;

    // Any stages that we've triggered that are supposed to repeat.
    private final Set<SimpleBuildOrderStage> repeatingStages;

    // Used for tracking of the current state.
    private final Map<UnitType, Integer> expectedCountOfUnitType;
    private final Map<Ability, Integer> abilitiesUsedCount;
    private boolean expectedCountInitialised = false;
    private long stageStartedAt = 0L;
    private boolean isTimedOut = false;

    // List of debug output that explains why the build failed.
    private List<String> buildFailureDebug = Collections.emptyList();

    protected SimpleBuildOrder(List<SimpleBuildOrderStage> stages) {
        this.initialStages = stages;
        this.inProgressStages = new LinkedHashSet<>();
        this.remainingStages = new LinkedHashSet<>(stages);
        this.repeatingStages = new HashSet<>();
        this.expectedCountOfUnitType = new HashMap<>(); // Will be initialised initially onStep.
        this.abilitiesUsedCount = new HashMap<>();
        validateStages();
    }

    private void validateStages() {
        boolean hasAttack = false;
        for (SimpleBuildOrderStage stage : initialStages) {
            if (stage.attack().isPresent() && stage.attack().get() == true) {
                hasAttack = true;
            }
        }
        Validate.isTrue(hasAttack, "A build order needs an attack(true) step.");
    }

    @Override
    public void onStep(AgentWithData agentWithData) {
        if (isTimedOut) {
            return;
        }
        ObservationInterface observationInterface = agentWithData.observation();
        if (!expectedCountInitialised) {
            expectedCountInitialised = true;
            // Put initial expected counts in from observation to account for things we started with,
            observationInterface.getUnits(Alliance.SELF).forEach(unitInPool -> {
               expectedCountOfUnitType.compute(unitInPool.unit().getType(), (k, v) -> v == null ? 1 : v + 1);
            });
        }
        long gameLoop = observationInterface.getGameLoop();
        if (gameLoop > stageStartedAt + MAX_STAGE_TIME || observationInterface.getMinerals() > 800) {
            isTimedOut = true;
            buildFailureDebug = List.of(
                    "Tag:buildTerminated " + remainingStages.size(),
                    "Tag:buildMinerals " + observationInterface.getMinerals(),
                    "Tag:buildSupply " + observationInterface.getFoodUsed() + " " + observationInterface.getFoodCap());
            return;
        }
        // Check which stages should be output.
        List<SimpleBuildOrderStage> newStages = remainingStages.stream().filter(potentialStage ->
                potentialStage.trigger().accept(this, observationInterface, agentWithData)).collect(Collectors.toList());

        newStages.forEach(newStage -> {
            remainingStages.remove(newStage);
            inProgressStages.add(newStage);
            handleStageStarted(newStage, agentWithData.gameData());
        });
    }

    private void handleStageStarted(SimpleBuildOrderStage newStage, GameData gameData) {
        Optional<UnitType> expectedUnitType = newStage.ability().flatMap(ability ->
                gameData.getUnitBuiltByAbility(ability));
        if (expectedUnitType.isPresent()) {
            expectedCountOfUnitType.compute(expectedUnitType.get(), (k, v) -> v == null ? 1 : v + 1);
        }
    }

    @Override
    public String getDebugText() {
        return getClass().getSimpleName() + ": " + remainingStages.size() + "/" + initialStages.size() + " remaining";
    }

    @Override
    public List<String> getVerboseDebugText() {
        return buildFailureDebug;
    }

    @Override
    public List<BuildOrderOutput> getOutput(AgentWithData agentWithData,
                                            Map<Ability, Integer> currentParallelAbilities) {
        List<BuildOrderOutput> output = new ArrayList<>();
        // Add repeating stages, if we can afford it.
        AtomicInteger remainingMoney = new AtomicInteger(Math.max(0,
                agentWithData.observation().getMinerals() - agentWithData.taskManager().totalReservedMinerals()));
        repeatingStages.stream().forEach(action -> {
            Optional<UnitType> buildUnitType = action.ability().flatMap(ability -> agentWithData.gameData().getUnitBuiltByAbility(ability));
            int mineralCost = buildUnitType.flatMap(unitType -> agentWithData.gameData().getUnitMineralCost(unitType)).orElse(0);
            // Hack for scvs to be queued even if there's not enough money for it.
            boolean force = buildUnitType.filter(type -> Constants.WORKER_TYPES.contains(type)).isPresent();
            if (force || remainingMoney.get() >= mineralCost) {
                output.add(action.toBuildOrderOutput());
                remainingMoney.set(remainingMoney.get() - mineralCost);
            }
        });

        return Stream.concat(inProgressStages.stream().map(SimpleBuildOrderStage::toBuildOrderOutput), output.stream())
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public void onStageStarted(S2Agent agent, AgentData data, BuildOrderOutput stage) {
        inProgressStages.removeIf(simpleBuildOrderStage -> {
            BuildOrderOutput stageAsOutput = simpleBuildOrderStage.toBuildOrderOutput();
            if (stageAsOutput.equals(stage)) {
                System.out.println("Stage " + simpleBuildOrderStage + " executed by engine, removed from list.");
                simpleBuildOrderStage.ability().ifPresent(ability -> {
                    abilitiesUsedCount.merge(ability, 1, Integer::sum);
                    System.out.println("Stage " + simpleBuildOrderStage + " used ability " + ability + ", expected count is now " + abilitiesUsedCount.getOrDefault(ability, 0) + ".");
                });
                if (simpleBuildOrderStage.repeat()) {
                    repeatingStages.add(simpleBuildOrderStage);
                    System.out.println("Stage " + simpleBuildOrderStage + " is repeating, added to repeat list.");
                }
                return true;
            } else {
                return false;
            }
        });
    }

    @Override
    public void onStageFailed(BuildOrderOutput stage, AgentWithData agentWithData) {
        isTimedOut = true;
        ObservationInterface observationInterface = agentWithData.observation();
        buildFailureDebug = List.of(
                "Tag:buildTerminated " + remainingStages.size(),
                "Tag:buildTerminatedAt " + stage.asHumanReadableString(),
                "Tag:buildMinerals " + observationInterface.getMinerals(),
                "Tag:buildSupply " + observationInterface.getFoodUsed() + " " + observationInterface.getFoodCap());
    }

    @Override
    public void onStageCompleted(BuildOrderOutput stage, AgentWithData agentWithData) {
        remainingStages.remove(stage);
    }

    @Override
    public boolean isComplete() {
        if (remainingStages.isEmpty()) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean isTimedOut() {
        return isTimedOut;
    }

    /**
     * Returns a count of Abilities that have been dispatched so far. This does not account for abilities that
     * have been dispatched but then aborted (eg building placement failed) or the unit created has since been destroyed.
     * Use checkpoints to 'rebuild' the build order.
     */
    Map<Ability, Integer> getAbilitiesUsedCount() {
        return Collections.unmodifiableMap(abilitiesUsedCount);
    }
}
