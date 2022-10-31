package com.supalosa.bot.task;

import com.supalosa.bot.strategy.StrategicObservation;

public interface StrategyTask extends Task {

    /**
     * Returns true if the given observation has been seen before.
     */
    boolean hasSeenObservation(Class<? extends StrategicObservation> observation);
}
