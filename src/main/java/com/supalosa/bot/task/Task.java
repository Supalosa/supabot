package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.placement.StructurePlacementCalculator;

import java.util.Optional;
import java.util.concurrent.Future;

public interface Task {

    void onStep(TaskManager taskManager, AgentData data, S2Agent agent);

    Optional<TaskResult> getResult();

    boolean isComplete();

    /**
     * Unique key for the task. If another task is created with the same key, it will be rejected by the
     * task manager.
     * @return
     */
    String getKey();

    boolean isSimilarTo(Task otherTask);

    void debug(S2Agent agent);

    String getDebugText();
}
