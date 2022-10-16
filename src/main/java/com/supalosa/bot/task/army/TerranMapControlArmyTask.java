package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.analysis.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A subtype of TerranBioArmyTask that holds a single base.
 */
public class TerranMapControlArmyTask extends TerranBioArmyTask {

    private boolean isComplete = false;

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = 0L;

    private Optional<Point2d> parkedPosition = Optional.empty();

    public TerranMapControlArmyTask(String armyName, int basePriority) {
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
        if (agent.observation().getArmyCount() < 24) {
            this.isComplete = true;
        }
        if (parkedPosition.isEmpty()) {
            parkedPosition = data.mapAwareness().getNextScoutTarget();
            this.setTargetPosition(parkedPosition);
        }
    }

    private void updateBioArmyComposition() {
        List<UnitTypeRequest> result = new ArrayList<>();
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARINE)
                .productionAbility(Abilities.TRAIN_MARINE)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .amount(1)
                .build()
        );
        desiredComposition = result;
    }

    @Override
    protected AggressionState attackCommand(S2Agent agent,
                                            AgentData data,
                                            Optional<Point2d> centreOfMass,
                                            Optional<Point2d> suggestedAttackMovePosition,
                                            Optional<Point2d> suggestedRetreatMovePosition,
                                            Optional<Army> maybeEnemyArmy) {
        AggressionState parentState = super.attackCommand(agent, data, centreOfMass,
                suggestedAttackMovePosition, suggestedRetreatMovePosition, maybeEnemyArmy);

        ObservationInterface observationInterface = agent.observation();
        ActionInterface actionInterface = agent.actions();

        if (parkedPosition.isEmpty()) {
            parkedPosition = data.mapAwareness().getNextScoutTarget();
        }
        if (armyUnits.isEmpty()) {
            return parentState;
        }
        parkedPosition.ifPresent(position -> {
           actionInterface.unitCommand(armyUnits, Abilities.MOVE, position, false);
        });

        return AggressionState.ATTACKING;
    }

    @Override
    public List<UnitTypeRequest> requestingUnitTypes() {
        return desiredComposition;
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        if (otherTask instanceof TerranMapControlArmyTask) {
            // only one at a time for now.
            return true;
        }
        return false;
    }
}
