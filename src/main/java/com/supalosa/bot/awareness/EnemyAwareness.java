package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;

import java.util.List;
import java.util.Optional;

public interface EnemyAwareness {

    void onStep(AgentWithData agentWithData);

    /**
     * Returns the potential location and composition of the enemy army nearest to a given position.
     * If there are multiple armies, it is the biggest one we see.
     * All unknown units are silently added to armies that we can't see.
     */
    List<Army> getMaybeEnemyArmies(Point2d point2d, float inRange);

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

    /**
     * Returns the estimated enemy mineral income per minute.
     */
    Estimation estimatedEnemyMineralIncome();

    /**
     * Returns the estimated number of enemy bases.
     */
    Estimation estimatedEnemyBases();
}
