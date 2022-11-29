package com.supalosa.bot.debug;

import com.supalosa.bot.AgentData;
import com.supalosa.bot.SupaBot;

public class NoOpDebugTarget implements DebugTarget {

    private SupaBot agent;

    @Override
    public void initialise(SupaBot agent) {
        this.agent = agent;
    }

    @Override
    public void onStep(SupaBot agent, AgentData data) {

    }

    @Override
    public void stop() {

    }
}
