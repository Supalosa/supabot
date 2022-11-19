package com.supalosa.bot.strategy;

import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.utils.UnitFilter;

import java.time.Duration;

/**
 * Analysis and response chain for 2 base gateway rush.
 */
public class Protoss2BaseGatewayRush implements StrategicObservation {

    // usually a 12 pool is scouted at around 1:30 for an average size map, which is gameLoop 2016.

    // 2nd gateway goes down around 2:13 (round up to 2:30)
    // 3rd gateway goes down around 2:40 (round up to 3:00)
    // scout needs to leave around here
    // 4th gateway goes down around 3:58 (round up to 4:15)
    // 1st stalker at 2:55
    // 2nd stalker at 3:24
    // 3rd stalker at 3:35

    private static long SECOND_GATEWAY_TIME = StrategicObservation.asGameLoop(Duration.parse("PT2M30S"));
    private static long THIRD_GATEWAY_TIME = StrategicObservation.asGameLoop(Duration.parse("PT3M"));
    private static long FOURTH_GATEWAY_TIME = StrategicObservation.asGameLoop(Duration.parse("PT4M15S"));

    private static long TERMINATE_AT = StrategicObservation.asGameLoop(Duration.parse("PT6M"));

    private boolean isComplete = false;

    @Override
    public boolean apply(AgentWithData agentWithData) {
        long gameLoop = agentWithData.observation().getGameLoop();
        int observedGateways = agentWithData.observation().getUnits(UnitFilter.builder()
                .alliance(Alliance.ENEMY)
                .includeIncomplete(true)
                .unitType(Units.PROTOSS_GATEWAY)
                .build()).size();

        if (observedGateways >= 2 && gameLoop < SECOND_GATEWAY_TIME) {
            isComplete = true;
            return true;
        }
        if (observedGateways >= 3 && gameLoop < THIRD_GATEWAY_TIME) {
            isComplete = true;
            return true;
        }
        if (observedGateways >= 4 && gameLoop < FOURTH_GATEWAY_TIME) {
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
