package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;

import java.util.List;
import java.util.Map;

/**
 * An ordered list of structures/units/upgrades to build.
 */
public interface BuildOrder {

    /**
     * Returns the most appropriate action to take in the current step.
     * If no order is appropriate yet, do nothing.
     */
    List<BuildOrderOutput> getOutput(AgentWithData data, Map<Ability, Integer> currentParallelAbilities);

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

    // Used for sending multi-line output.
    List<String> getVerboseDebugText();
}
