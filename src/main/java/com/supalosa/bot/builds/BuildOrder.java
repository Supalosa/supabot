package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.GameData;
import com.supalosa.bot.strategy.StrategicObservation;

import java.util.List;
import java.util.Optional;

/**
 * An ordered list of structures/units/upgrades to build.
 */
public interface BuildOrder {

    /**
     * Returns the most appropriate action to take in the current step.
     * If no order is appropriate yet, do nothing.
     */
    List<BuildOrderOutput> getOutput(AgentWithData data);

    void onStageStarted(S2Agent agent, AgentData data, BuildOrderOutput stage);

    void onStageFailed(BuildOrderOutput stage, AgentWithData agentWithData);

    void onStageCompleted(BuildOrderOutput stage, AgentWithData agentWithData);

    /**
     * Returns whether the build is (successfully) complete and the build should be handed over to the default
     * build handler.
     */
    boolean isComplete();
    
    boolean isTimedOut();

    void onStep(AgentWithData agentWithData);

    int getMaximumGasMiners();

    String getDebugText();
}
