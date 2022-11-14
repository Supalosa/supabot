package com.supalosa.bot.strategy;

import com.github.ocraft.s2client.protocol.data.Units;
import com.supalosa.bot.AgentWithData;

/**
 * Analysis and response chain for 2 base gateway rush.
 */
public class Protoss2BaseGatewayRush implements StrategicObservation {

    // usually a 12 pool is scouted at around 1:30 for an average size map, which is gameLoop 2016.

    //public static final int EXPECTED_THIRD_BASE_BY =

    private boolean isComplete = false;

    @Override
    public boolean apply(AgentWithData agentWithData) {
        isComplete = true;
        return false;
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }
}
