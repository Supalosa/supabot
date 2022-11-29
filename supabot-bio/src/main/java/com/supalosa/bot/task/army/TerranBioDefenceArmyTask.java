package com.supalosa.bot.task.army;

import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;

import java.util.ArrayList;
import java.util.List;

/**
 * A subtype of TerranBioArmyTask that handles defence of a region.
 * May load its marines into a bunker.
 * Its reinforcement is handled externally.
 */
public class TerranBioDefenceArmyTask extends TerranBioArmyTask {

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = 0L;

    private final Task parentTask;

    public TerranBioDefenceArmyTask(String armyName, Task parentTask) {
        super(armyName, 0);
        this.parentTask = parentTask;
    }

    @Override
    public void onStepImpl(TaskManager taskManager, AgentWithData agentWithData) {
        super.onStepImpl(taskManager, agentWithData);
        long gameLoop = agentWithData.observation().getGameLoop();
        if (parentTask.isComplete()) {
            markComplete();
        }
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        if (otherTask instanceof TerranBioDefenceArmyTask) {
            return otherTask.getKey().equals(this.getKey());
        }
        return false;
    }
}
