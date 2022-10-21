package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.unit.Alliance;

import java.util.List;
import java.util.Optional;

public class SimpleBuildOrder implements BuildOrder {

    private final List<SimpleBuildOrderStage> stages;
    private int currentStageNumber;

    SimpleBuildOrder(List<SimpleBuildOrderStage> stages) {
        this.stages = stages;
        this.currentStageNumber = 0;
    }

    public int getCurrentStageNumber()  {
        return this.currentStageNumber;
    }

    public int getTotalStages() {
        return this.stages.size();
    }

    @Override
    public void onStep(ObservationInterface observationInterface) {
        SimpleBuildOrderStage currentStage = stages.get(currentStageNumber);

        if (isOrderUnderway(observationInterface, currentStage)) {
            currentStageNumber += 1;
        }
    }

    @Override
    public Optional<BuildOrderOutput> getOutput(ObservationInterface observationInterface) {
        if (currentStageNumber >= stages.size()) {
            return Optional.empty();
        } else {
            SimpleBuildOrderStage currentStage = stages.get(currentStageNumber);
            return Optional.of(
                    ImmutableBuildOrderOutput.builder()
                            .abilityToUse(currentStage.ability())
                            .addonRequired(currentStage.addonType())
                            .build());
        }
    }

    private boolean isOrderUnderway(ObservationInterface observationInterface,
                                    SimpleBuildOrderStage simpleBuildOrderStage) {
        if (simpleBuildOrderStage.ability().isPresent()) {
            // Search for units already using the ability.
            List<UnitInPool> eligibleUnits = simpleBuildOrderStage.unitFilter()
                    .map(filter -> observationInterface.getUnits(filter))
                    .orElseGet(() -> observationInterface.getUnits(Alliance.SELF));
            return eligibleUnits.stream().map(unitInPool -> unitInPool.unit())
                    .flatMap(unit -> unit.getOrders().stream())
                    .anyMatch(unitOrder ->
                            simpleBuildOrderStage.ability().get().equals(unitOrder.getAbility()));
        } else {
            // Default if no ability - assume it's already underway.
            // The idea is that we might have a build order stage to attack or something.
            return true;
        }
    }

    @Override
    public boolean isComplete() {
        if (currentStageNumber >= stages.size()) {
            return true;
        } else {
            return false;
        }
    }

}
