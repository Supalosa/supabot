package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.GameData;
import org.apache.commons.lang3.Validate;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class SimpleBuildOrder implements BuildOrder {

    // Max time to spend on a stage before we decide the build order is not worth continuing.
    private static final long MAX_STAGE_TIME = 22L * 60;

    private final List<SimpleBuildOrderStage> stages;
    private final Set<SimpleBuildOrderStage> repeatingStages;
    private final List<SimpleBuildOrderStage> asynchronousStages;
    private int targetGasMiners;
    private int currentStageNumber;
    private final Map<UnitType, Integer> expectedCountOfUnitType;
    private final Map<Ability, Integer> abilitiesUsedCount;
    private boolean expectedCountInitialised = false;
    private long stageStartedAt = 0L;
    private boolean isTimedOut = false;
    private boolean isAttackPermitted = false;


    SimpleBuildOrder(List<SimpleBuildOrderStage> stages) {
        this.stages = stages;
        this.repeatingStages = new HashSet<>();
        this.asynchronousStages = new ArrayList<>();
        this.targetGasMiners = 0; //Integer.MAX_VALUE;
        this.currentStageNumber = 0;
        this.expectedCountOfUnitType = new HashMap<>(); // Will be initialised initially onStep.
        this.abilitiesUsedCount = new HashMap<>();
        validateStages();
    }

    private void validateStages() {
        boolean hasAttack = false;
        for (SimpleBuildOrderStage stage : stages) {
            if (stage.attack().isPresent() && stage.attack().get() == true) {
                hasAttack = true;
            }
        }
        Validate.isTrue(hasAttack, "A build order needs an attack(true) step.");
    }

    public int getCurrentStageNumber()  {
        return this.currentStageNumber;
    }

    public int getTotalStages() {
        return this.stages.size();
    }

    @Override
    public void onStep(ObservationInterface observationInterface, GameData data) {
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
        }
    }

    @Override
    public int getMaximumGasMiners() {
        return targetGasMiners;
    }

    @Override
    public List<BuildOrderOutput> getOutput(AgentWithData agentWithData) {
        if (currentStageNumber >= stages.size()) {
            return new ArrayList<>();
        } else {
            // Check if any asynchronous stages have triggered.
            List<SimpleBuildOrderStage> asynchronousTriggered = asynchronousStages.stream().filter(stage ->
                            stage.trigger().accept(this, agentWithData.observation(), agentWithData))
                    .collect(Collectors.toList());
            asynchronousStages.removeAll(asynchronousTriggered);
            if (asynchronousTriggered.size() > 0) {
                System.out.println("Inserted " + asynchronousTriggered.size() + " stages as their async trigger returned true");
                stages.addAll(currentStageNumber, asynchronousTriggered);
            }

            SimpleBuildOrderStage currentStage = stages.get(currentStageNumber);
            List<BuildOrderOutput> output = new ArrayList<>();
            if (currentStage.trigger().accept(this, agentWithData.observation(), agentWithData)) {
                output.add(convertStageToOutput(currentStage));
            }
            // Send repeating stages, but only if we can afford it.
            AtomicInteger remainingMoney = new AtomicInteger(Math.max(0,
                    agentWithData.observation().getMinerals() - agentWithData.taskManager().totalReservedMinerals()));
            repeatingStages.stream().forEach(action -> {
                Optional<UnitType> buildUnitType = action.ability().flatMap(ability -> agentWithData.gameData().getUnitBuiltByAbility(ability));
                int mineralCost = buildUnitType.flatMap(unitType -> agentWithData.gameData().getUnitMineralCost(unitType)).orElse(0);
                // Hack for scvs to be queued even if there's not enough money for it.
                boolean force = buildUnitType.filter(type -> Constants.WORKER_TYPES.contains(type)).isPresent();
                if (force || remainingMoney.get() >= mineralCost) {
                    output.add(convertStageToOutput(action));
                    remainingMoney.set(remainingMoney.get() - mineralCost);
                }
            });
            return output;
        }
    }

    /**
     * Returns the stage that we're waiting for (if applicable).
     */
    public Optional<SimpleBuildOrderStage> getWaitingStage(ObservationInterface observationInterface, AgentWithData agentWithData) {
        if (currentStageNumber < stages.size()) {
            SimpleBuildOrderStage currentStage = stages.get(currentStageNumber);
            if (!currentStage.trigger().accept(this, observationInterface, agentWithData)) {
                return Optional.of(currentStage);
            }
        }
        return Optional.empty();
    }

    @Override
    public void onStageStarted(S2Agent agent, AgentData data, BuildOrderOutput stage) {
        if (currentStageNumber >= stages.size()) {
            return;
        }
        SimpleBuildOrderStage currentStage = stages.get(currentStageNumber);
        if (stage.equals(convertStageToOutput(currentStage))) {
            System.out.println("Stage " + currentStage + " started at " + agent.observation().getGameLoop());
            currentStageNumber += 1;
            stageStartedAt = agent.observation().getGameLoop();
            if (currentStageNumber < stages.size()) {
                SimpleBuildOrderStage nextStage = stages.get(currentStageNumber);
                Optional<UnitType> expectedUnitType = nextStage.ability().flatMap(ability ->
                        data.gameData().getUnitBuiltByAbility(ability));
                if (expectedUnitType.isPresent()) {
                    expectedCountOfUnitType.compute(expectedUnitType.get(), (k, v) -> v == null ? 1 : v + 1);
                }
                if (currentStage.ability().isPresent()) {
                    abilitiesUsedCount.compute(currentStage.ability().get(), (k, v) -> v == null ? 1 : v + 1);
                }
                if (currentStage.repeat()) {
                    repeatingStages.add(currentStage);
                }
                // These actions aren't actually emitted to the consumer, but rather just update the internal state.
                if (currentStage.gasMiners().isPresent()) {
                    targetGasMiners = currentStage.gasMiners().get();
                }
                if (currentStage.attack().isPresent()) {
                    isAttackPermitted = currentStage.attack().get();
                }
                while (nextStage.trigger().isBlocking() == false) {
                    asynchronousStages.add(nextStage);
                    currentStageNumber += 1;
                    nextStage = stages.get(currentStageNumber);
                }
            }
        }
    }

    private BuildOrderOutput convertStageToOutput(SimpleBuildOrderStage simpleBuildOrderStage) {
        return simpleBuildOrderStage.toBuildOrderOutput();
    }

    @Override
    public boolean isComplete() {
        if (currentStageNumber >= stages.size()) {
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
