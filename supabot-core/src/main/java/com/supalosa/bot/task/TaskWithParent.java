package com.supalosa.bot.task;

import java.util.Optional;

/**
 * A task that has a parent task.
 */
public interface TaskWithParent {

    Optional<? extends Task> getParentTask();
}
