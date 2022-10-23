package com.supalosa.bot.task;

import java.util.function.Supplier;

/**
 * A behaviour task, such as a build order or 'default' handler that builds after the
 * build order is complete.
 */
public interface BehaviourTask extends Task {

    Supplier<BehaviourTask> getNextBehaviourTask();
}
