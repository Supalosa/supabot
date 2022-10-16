package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.analysis.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;

import java.util.*;

/**
 * A subtype of TerranBioArmyTask that is smaller and disbands if the overall army is too small.
 */
public class TerranBioHarassArmyTask extends TerranBioArmyTask {

    private boolean isComplete = false;

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = 0L;

    public TerranBioHarassArmyTask(String armyName, int basePriority) {
        super(armyName, basePriority);
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        super.onStep(taskManager, data, agent);
        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > desiredCompositionUpdatedAt + 22L) {
            desiredCompositionUpdatedAt = gameLoop;
            updateBioArmyComposition();
        }
        // This army disappears if the overall army is small.
        if (agent.observation().getArmyCount() < 40) {
            this.isComplete = true;
        }
    }

    private void updateBioArmyComposition() {
        List<UnitTypeRequest> result = new ArrayList<>();
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARINE)
                .productionAbility(Abilities.TRAIN_MARINE)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .amount(8)
                .build()
        );
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARAUDER)
                .productionAbility(Abilities.TRAIN_MARAUDER)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .amount(4)
                .build()
        );
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MEDIVAC)
                .productionAbility(Abilities.TRAIN_MEDIVAC)
                .producingUnitType(Units.TERRAN_STARPORT)
                .amount(2)
                .build()
        );
        desiredComposition = result;
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        if (otherTask instanceof TerranBioHarassArmyTask) {
            // only one at a time for now.
            return true;
        }
        return false;
    }

    @Override
    public boolean wantsUnit(Unit unit) {
        // This unit will take any bio-army unit.
        // Note, should set its priority lower than other tasks so it doesn't hog them all.
        return unit.getType() == Units.TERRAN_MARINE ||
                unit.getType() == Units.TERRAN_MARAUDER ||
                unit.getType() == Units.TERRAN_MEDIVAC ||
                unit.getType() == Units.TERRAN_RAVEN;
    }
}
