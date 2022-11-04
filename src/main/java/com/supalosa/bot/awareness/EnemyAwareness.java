package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.AgentData;

import java.util.List;
import java.util.Optional;

public interface EnemyAwareness {

    void onStep(ObservationInterface observationInterface, AgentData data);

    /**
     * Returns the potential location and composition of the enemy army nearest to a given position.
     * If there are multiple armies, it is the biggest one we see.
     * All unknown units are silently added to armies that we can't see.
     */
    List<Army> getMaybeEnemyArmies(Point2d point2d, float inRange);

    /**
     * Returns the entire potential of the enemy army, including units we've seen previously in the last 60 seconds.
     */
    Optional<Army> getPotentialEnemyArmy();

    /**
     * Returns an army representing the units we can't see anymore.
     */
    Optional<Army> getMissingEnemyArmy();

    /**
     * Returns an army representing everything we think the enemy has.
     */
    Army getOverallEnemyArmy();

    void debug(S2Agent agent);

    void onUnitDestroyed(UnitInPool unit);
}
