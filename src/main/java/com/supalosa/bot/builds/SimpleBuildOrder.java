package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.GameData;
import com.supalosa.bot.task.ImmutablePlacementRules;
import com.supalosa.bot.task.PlacementRules;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.Validate;

import java.util.*;
import java.util.stream.Collectors;

public class SimpleBuildOrder implements BuildOrder {

    // Max time to spend on a stage before we decide the build order is not worth continuing.
    private static final long MAX_STAGE_TIME = 22L * 60;

    private final List<SimpleBuildOrderStage> stages;
    private final Set<SimpleBuildOrderStage> repeatingStages;
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
        if (gameLoop > stageStartedAt + MAX_STAGE_TIME) {
            isTimedOut = true;
        }
    }

    @Override
    public int getMaximumGasMiners() {
        return targetGasMiners;
    }

    @Override
    public List<BuildOrderOutput> getOutput(ObservationInterface observationInterface, GameData gameData) {
        if (currentStageNumber >= stages.size()) {
            return new ArrayList<>();
        } else {
            SimpleBuildOrderStage currentStage = stages.get(currentStageNumber);
            List<BuildOrderOutput> output = new ArrayList<>();
            final int currentSupply = observationInterface.getFoodUsed();
            if (currentStage.trigger().accept(this, observationInterface, gameData)) {
                output.add(convertStageToOutput(currentStage));
            }
            output.addAll(repeatingStages.stream().map(this::convertStageToOutput)
                    .collect(Collectors.toList()));
            return output;
        }
    }

    /**
     * Returns the stage that we're waiting for (if applicable).
     */
    public Optional<SimpleBuildOrderStage> getWaitingStage(ObservationInterface observationInterface, GameData gameData) {
        if (currentStageNumber < stages.size()) {
            SimpleBuildOrderStage currentStage = stages.get(currentStageNumber);
            if (!currentStage.trigger().accept(this, observationInterface, gameData)) {
                return Optional.of(currentStage);
            }
        }
        return Optional.empty();
    }

    @Override
    public void onStageStarted(S2Agent agent, AgentData data, BuildOrderOutput stage) {
        SimpleBuildOrderStage currentStage = stages.get(currentStageNumber);
        if (stage.equals(convertStageToOutput(currentStage))) {
            System.out.println("Stage " + currentStage + " started at " + agent.observation().getGameLoop());
            currentStageNumber += 1;
            stageStartedAt = agent.observation().getGameLoop();
            SimpleBuildOrderStage nextStage = stages.get(currentStageNumber);
            Optional<UnitType> expectedUnitType = nextStage.ability().flatMap(ability ->
                    data.gameData().getUnitBuiltByAbilility(ability));
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
        }
    }

    private BuildOrderOutput convertStageToOutput(SimpleBuildOrderStage simpleBuildOrderStage) {
        return ImmutableBuildOrderOutput.builder()
                .originatingHashCode(simpleBuildOrderStage.hashCode())
                .abilityToUse(simpleBuildOrderStage.ability())
                .eligibleUnitTypes(simpleBuildOrderStage.unitFilter())
                .addonRequired(simpleBuildOrderStage.addonType())
                .placementRules(simpleBuildOrderStage.placementRules())
                .performAttack(simpleBuildOrderStage.attack())
                .build();
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
