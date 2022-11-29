package com.supalosa.bot.strategy;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Units;
import com.supalosa.bot.AgentWithData;
import com.github.ocraft.s2client.protocol.unit.Tag;

import java.util.HashSet;
import java.util.Set;

/**
 * Analysis and response chain for Zerg Ling Flood.
 */
public class Zerg12PoolStrategicObservation implements StrategicObservation {

    // usually a 12 pool is scouted at around 1:30 for an average size map, which is gameLoop 2016.

    // The first gameLoop that we would expect to see zerglings outside the enemy base, where it isn't a 12 pool rush.
    public static final int TWELVE_POOL_ZERGLING_TIME = 2500;
    public static final int ZERGLING_THRESHOLD = 4;

    private boolean isComplete = false;

    @Override
    public boolean apply(AgentWithData agentWithData) {
        if (agentWithData.observation().getGameLoop() > TWELVE_POOL_ZERGLING_TIME) {
            isComplete = true;
        }
        if (agentWithData.enemyAwareness().getOverallEnemyArmy().getCount(Units.ZERG_ZERGLING) >= ZERGLING_THRESHOLD) {
            isComplete = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }
}
