package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskMessageResponse;
import com.supalosa.bot.task.message.TaskPromise;
import org.apache.commons.lang3.NotImplementedException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface Task {

    /**
     * Updates the internal state of the task.
     * Note that it will NOT be called if {@code isComplete()} ever returned true;
     */
    void onStep(TaskManager taskManager, AgentData data, S2Agent agent);

    /**
     * Returns the result of the task (success, fail etc).
     * If the task is incomplete, should return an empty result.
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

    /**
     * Called when a task has sent a message.
     *
     * @param taskOrigin The task that sent the message.
     * @param message The message from another task.
     * @return If this task accepts the message, an optional promise that will be called when the
     * task is resolved (successfully or not).
     */
    Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message);

    /**
     * Return the amount of minerals that this task requires.
     */
    default int reservedMinerals() {
        return 0;
    }

    /**
     * Return the amount of vespene that this task requires.
     */
    default int reservedVespene() {
        return 0;
    }

    /**
     * Runs a callback when this task is started (in particular, when action in the game has been taken).
     * It may be called multiple times, if the task has internal retry behaviour.
     */
    default Task onStarted(Consumer<Optional<TaskResult>> callback) {
        throw new NotImplementedException("This task does not support onFailure callbacks.");
    }

    /**
     * Runs a callback when this task is complete.
     */
    default Task onComplete(Consumer<Optional<TaskResult>> callback) {
        throw new NotImplementedException("This task does not support onComplete callbacks.");
    }

    /**
     * Runs a callback when this task is complete.
     */
    default Task onFailure(Consumer<Optional<TaskResult>> callback) {
        throw new NotImplementedException("This task does not support onFailure callbacks.");
    }
}
