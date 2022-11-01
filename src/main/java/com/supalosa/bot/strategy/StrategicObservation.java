package com.supalosa.bot.strategy;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.task.message.TaskMessage;

import java.util.List;

/**
 * Strategic observation is a special type of TaskMessage that is broadcasted when a certain observation about the game
 * is met.
 */
public interface StrategicObservation extends TaskMessage {

    /**
     * Returns true if the current state of the game matches the strategy.
     */
    boolean apply(AgentWithData agentWithData);

    /**
     * Returns true when this strategic observation is no longer relevant.
     */
    boolean isComplete();

    static List<StrategicObservation> allObservations() {
        return List.of(
                new Zerg12PoolStrategicObservation(),
                new WorkerRushStrategicObservation()
        );
    }
}
