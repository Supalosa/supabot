package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;

import java.util.*;

/**
 * A subtype of TerranBioArmyTask that is smaller and disbands if the overall army is too small.
 * It can also load units into medivac and drop into enemy base.
 */
public class TerranBioHarassArmyTask extends TerranBioArmyTask {

    private enum HarassMode {
        GROUND,
        AIR
    }

    private enum LoadingState {
        LOADING,
        MOVING,
        UNLOADING
    }

    private boolean isComplete = false;

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = 0L;

    private HarassMode harassMode = HarassMode.GROUND;
    private long harassModeCalculatedAt = 0L;

    private LoadingState loadingState = LoadingState.MOVING;
    private long loadingStateChangedAt = 0L;

    private int deleteAtArmyCount = 200;

    public TerranBioHarassArmyTask(String armyName, int basePriority, int deleteAtArmyCount) {
        super(armyName, basePriority);
        this.deleteAtArmyCount = deleteAtArmyCount;
    }

    @Override
    public void onStepImpl(TaskManager taskManager, AgentWithData agentWithData) {
        super.onStepImpl(taskManager, agentWithData);
        long gameLoop = agentWithData.observation().getGameLoop();

        if (gameLoop > desiredCompositionUpdatedAt + 22L) {
            desiredCompositionUpdatedAt = gameLoop;
            updateBioArmyComposition();
        }
        // This army disappears if the overall army is small.
        if (harassMode == HarassMode.GROUND && agentWithData.observation().getArmyCount() < deleteAtArmyCount) {
            this.markComplete();
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
                .needsTechLab(true)
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
    public List<UnitTypeRequest> requestingUnitTypes() {
        return desiredComposition;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        if (otherTask instanceof TerranBioHarassArmyTask) {
            return otherTask.getKey().equals(this.getKey());
        }
        return false;
    }

    @Override
    public boolean wantsUnit(Unit unit) {
        // Stop accepting ground units once we're in air mode.
        if (harassMode == HarassMode.AIR && !(unit.getFlying().orElse(true))) {
            return false;
        }
        return super.wantsUnit(unit);
    }

    @Override
    public String getDebugText() {
        return super.getDebugText() + " [" + harassMode + ", " + loadingState + "]";
    }
}
