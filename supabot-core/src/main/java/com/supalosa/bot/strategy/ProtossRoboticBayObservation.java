package com.supalosa.bot.strategy;

import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.utils.UnitFilter;

import java.time.Duration;
import java.util.Set;

/**
 * Observing colossus or other robo technology
 */
public class ProtossRoboticBayObservation implements StrategicObservation {

    private static long TERMINATE_AT = StrategicObservation.asGameLoop(Duration.parse("PT8M"));

    private boolean isComplete = false;

    @Override
    public boolean apply(AgentWithData agentWithData) {
        long gameLoop = agentWithData.observation().getGameLoop();
        int observed = agentWithData.observation().getUnits(UnitFilter.builder()
                .alliance(Alliance.ENEMY)
                .includeIncomplete(true)
                .unitTypes(Set.of(Units.PROTOSS_ROBOTICS_BAY, Units.PROTOSS_COLOSSUS))
                .build()).size();

        if (observed > 0) {
            isComplete = true;
            return true;
        }

        if (gameLoop > TERMINATE_AT) {
            isComplete = true;
        }
        return false;
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }
}
