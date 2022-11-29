package com.supalosa.bot.debug;

import com.supalosa.bot.AgentData;
import com.supalosa.bot.SupaBot;

public interface DebugTarget {

    void initialise(SupaBot agent);

    void onStep(SupaBot agent, AgentData data);

    void stop();
}
