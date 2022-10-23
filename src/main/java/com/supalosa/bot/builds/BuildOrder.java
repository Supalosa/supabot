package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.GameData;

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
    List<BuildOrderOutput> getOutput(ObservationInterface observationInterface);

    void onStageStarted(S2Agent agent, AgentData data, BuildOrderOutput stage);

    /**
     * Returns whether the build is (successfully) complete and the build should be handed over to the default
     * build handler.
     */
    boolean isComplete();
    
    boolean isTimedOut();

    void onStep(ObservationInterface observationInterface, GameData data);

    int getMaximumGasMiners();
}
