package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.AgentData;

import java.util.Optional;

public interface EnemyAwareness {

    void onStep(ObservationInterface observationInterface, AgentData data);

    /**
     * Returns the potential location and composition of the enemy army nearest to a given position.
     * If there are multiple armies, it is the biggest one we see.
     */
    Optional<Army> getMaybeEnemyArmy(Point2d point2d);

    /**
     * Returns the actual observed largest enemy army, including the position it was seen at.
     * Deprecated unless we need the position, because we should be looking for the
     * potential enemy army.
     */
    @Deprecated
    Optional<Army> getLargestEnemyArmy();

    /**
     * Returns the entire potential of the enemy army, including units we've seen previously in the last 60 seconds.
     */
    Optional<Army> getPotentialEnemyArmy();

    /**
     * Returns an army representing the units we can't see anymore.
     */
    Optional<Army> getMissingEnemyArmy();

    void debug(S2Agent agent);
}
