package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.GameData;
import com.supalosa.bot.task.ImmutablePlacementRules;
import com.supalosa.bot.task.PlacementRules;
import com.supalosa.bot.utils.UnitFilter;

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
    private boolean expectedCountInitialised = false;
    private long stageStartedAt = 0L;
    private boolean isTimedOut = false;

    SimpleBuildOrder(List<SimpleBuildOrderStage> stages) {
        this.stages = stages;
        this.repeatingStages = new HashSet<>();
        this.targetGasMiners = 0; //Integer.MAX_VALUE;
        this.currentStageNumber = 0;
        this.expectedCountOfUnitType = new HashMap<>(); // Will be initialised
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
    public List<BuildOrderOutput> getOutput(ObservationInterface observationInterface) {
        if (currentStageNumber >= stages.size()) {
            return new ArrayList<>();
        } else {
            SimpleBuildOrderStage currentStage = stages.get(currentStageNumber);
            List<BuildOrderOutput> output = new ArrayList<>();
            final int currentSupply = observationInterface.getFoodUsed();
            if (currentSupply >= currentStage.supplyTrigger()) {
                output.add(convertStageToOutput(currentStage));
            }
            output.addAll(repeatingStages.stream().map(this::convertStageToOutput)
                    .collect(Collectors.toList()));
            return output;
        }
    }

    @Override
    public void onStageStarted(S2Agent agent, AgentData data, BuildOrderOutput stage) {
        SimpleBuildOrderStage currentStage = stages.get(currentStageNumber);
        if (stage.equals(convertStageToOutput(currentStage))) {
            currentStageNumber += 1;
            stageStartedAt = agent.observation().getGameLoop();
            SimpleBuildOrderStage nextStage = stages.get(currentStageNumber);
            Optional<UnitType> expectedUnitType = nextStage.ability().flatMap(ability ->
                    data.gameData().getUnitBuiltByAbilility(ability));
            if (expectedUnitType.isPresent()) {
                expectedCountOfUnitType.compute(expectedUnitType.get(), (k, v) -> v == null ? 1 : v + 1);
            }
            if (currentStage.repeat()) {
                repeatingStages.add(currentStage);
            }
            if (currentStage.gasMiners().isPresent()) {
                targetGasMiners = currentStage.gasMiners().get();
            }
        }
    }

    private BuildOrderOutput convertStageToOutput(SimpleBuildOrderStage simpleBuildOrderStage) {
        Optional<PlacementRules> placementRules = simpleBuildOrderStage.expand() ? Optional.of(ImmutablePlacementRules.builder()
                .regionType(PlacementRules.Region.EXPANSION)
                .maxVariation(0)
                .build()) : Optional.empty();
        return ImmutableBuildOrderOutput.builder()
                .abilityToUse(simpleBuildOrderStage.ability())
                .eligibleUnitTypes(simpleBuildOrderStage.unitFilter())
                .addonRequired(simpleBuildOrderStage.addonType())
                .placementRules(placementRules)
                .build();
    }

    @Override
    public boolean isComplete() {
        if (isTimedOut || currentStageNumber >= stages.size()) {
            return true;
        } else {
            return false;
        }
    }

}
