package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.engagement.WorkerDefenceThreatCalculator;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.NotImplementedException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Defence against an enemy worker rush.
 */
public class TerranWorkerRushDefenceTask extends DefaultArmyTask {

    public class WorkerRushDetected implements TaskMessage {}

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = 0L;
    private Set<Tag> autocastedWorkers = new HashSet<>();

    public TerranWorkerRushDefenceTask() {
        super("WorkerRushDefence", 100, new WorkerDefenceThreatCalculator(), new TerranWorkerRushDefenceTaskBehaviour());
    }

    @Override
    public DefaultArmyTask createChildArmyImpl() {
        throw new NotImplementedException("Cannot reinforce this army.");
    }

    @Override
    public void onStepImpl(TaskManager taskManager, AgentWithData agentWithData) {
        super.onStepImpl(taskManager, agentWithData);
        long gameLoop = agentWithData.observation().getGameLoop();

        if (gameLoop > desiredCompositionUpdatedAt + 22L) {
            desiredCompositionUpdatedAt = gameLoop;
            updateComposition();
        }
        getAssignedUnits().forEach(armyUnitTag -> {
            if (!autocastedWorkers.contains(armyUnitTag)) {
                agentWithData.actions().toggleAutocast(armyUnitTag, Abilities.EFFECT_REPAIR);
                autocastedWorkers.add(armyUnitTag);
            }
        });
        // This army disappears if we can't see any workers near the start position.
        List<UnitInPool> enemyUnitsNearStartPosition = agentWithData.observation().getUnits(
                UnitFilter.builder()
                        .alliance(Alliance.ENEMY)
                        .inRangeOf(agentWithData.observation().getStartLocation().toPoint2d())
                        .range(20f)
                        .build());
        if (enemyUnitsNearStartPosition.size() == 0) {
            sendChat(agentWithData.actions(), "Worker rush defence ended.");
            this.markComplete();
        } else {
            taskManager.dispatchMessage(this, new WorkerRushDetected());
        }
    }

    private void sendChat(ActionInterface actionInterface, String message) {
        actionInterface.sendChat("[WorkerRush] " + message, ActionChat.Channel.BROADCAST);
    }

    private void updateComposition() {
        List<UnitTypeRequest> result = new ArrayList<>();
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_SCV)
                .productionAbility(Abilities.TRAIN_SCV)
                .producingUnitType(Units.TERRAN_COMMAND_CENTER)
                .amount(100)
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
        if (otherTask instanceof TerranWorkerRushDefenceTask) {
            // only one at a time for now.
            return true;
        }
        return false;
    }

    @Override
    public void onUnitIdle(UnitInPool unitTag) {

    }
}
