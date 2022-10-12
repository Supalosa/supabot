package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.supalosa.bot.AgentData;

import java.util.Optional;
import java.util.UUID;

public class ScoutingTask implements Task {

    private final String taskKey;

    private Optional<Tag> assignedScout = Optional.empty();

    private boolean isComplete = false;

    public ScoutingTask() {
        this.taskKey = "SCOUT." + UUID.randomUUID();
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        Optional<UnitInPool> scoutUnit = assignedScout
                .map(tag -> agent.observation().getUnit(tag))
                .or(() -> taskManager.findFreeUnitForTask(this,
                        agent.observation(),
                        unitInPool -> unitInPool.unit().getType() == Units.TERRAN_SCV));
        assignedScout = scoutUnit.map(unitInPool -> unitInPool.getTag());

        if (assignedScout.isEmpty()) {
            return;
        }

    }

    @Override
    public Optional<TaskResult> getResult() {
        return Optional.empty();
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }

    @Override
    public String getKey() {
        return taskKey;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        return (otherTask instanceof ScoutingTask);
    }

    @Override
    public void debug(S2Agent agent) {

    }

    @Override
    public String getDebugText() {
        return "Scouting";
    }
}
