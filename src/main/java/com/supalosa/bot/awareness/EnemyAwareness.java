package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.AgentData;

import java.util.Optional;

public interface EnemyAwareness {

    void onStep(ObservationInterface observationInterface, AgentData data);

    /**
     * Returns the potential location of the enemy army nearest to a given position.
     * If there are multiple armies, it is the biggest one we see.
     * @param point2d
     */
    Optional<Army> getMaybeEnemyArmy(Point2d point2d);

    Optional<Army> getLargestEnemyArmy();

    void debug(S2Agent agent);
}
