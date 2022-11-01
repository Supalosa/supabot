package com.supalosa.bot.strategy;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.task.army.DefaultArmyTask;
import com.supalosa.bot.task.army.TerranWorkerRushDefenceTask;
import com.supalosa.bot.utils.UnitFilter;

import java.util.List;

/**
 * Analysis and response chain for Zerg Ling Flood.
 */
public class WorkerRushStrategicObservation implements StrategicObservation {

    // usually a 12 pool is scouted at around 1:30 for an average size map, which is gameLoop 2016.

    // The first gameLoop that we would expect to see zerglings outside the enemy base, where it isn't a 12 pool rush.
    public static final int TWELVE_POOL_ZERGLING_TIME = 2500;
    public static final int ZERGLING_THRESHOLD = 4;

    private boolean isComplete = false;

    @Override
    public boolean apply(AgentWithData agentWithData) {
        if (agentWithData.observation().getArmyCount() > 1) {
            isComplete = true;
            return false;
        }
        if (agentWithData.mapAwareness().shouldDefendLocation(agentWithData.observation().getStartLocation().toPoint2d())) {
            List<UnitInPool> numNearbyWorkers = agentWithData.observation().getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.ENEMY)
                            .unitTypes(Constants.WORKER_TYPES)
                            .inRangeOf(agentWithData.observation().getStartLocation().toPoint2d())
                            .range(15f)
                            .build());
            if (numNearbyWorkers.size() > 6) {
                isComplete = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }
}
