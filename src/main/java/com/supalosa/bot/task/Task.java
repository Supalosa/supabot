package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.placement.StructurePlacementCalculator;

import java.util.Optional;
import java.util.concurrent.Future;

public interface Task {

    /**
     * Updates the internal state of the task.
     * Note that it will NOT be called if {@code isComplete()} ever returned true;
     */
    void onStep(TaskManager taskManager, AgentData data, S2Agent agent);

    /**
     * Returns the result of the task (success, fail etc).
     * Generally not used or implemented yet.
     */
    Optional<TaskResult> getResult();

    /**
     * Queries whether the task is complete.
     * Note that the task is effectively deleted (all reserved units are released etc) once this returns true.
     * It will no longer call onStep after this.
     */
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
