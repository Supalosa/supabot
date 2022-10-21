package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Abilities;

import java.util.Optional;

/**
 * An ordered list of structures/units/upgrades to build.
 */
public interface BuildOrder {

    /**
     * Returns the most appropriate action to take in the current step.
     * If no order is appropriate yet, do nothing.
     */
    Optional<BuildOrderOutput> getOutput(ObservationInterface observationInterface);

    /**
     * Returns whether the build is complete and the build should be handed over to the default
     * build handler.
     */
    boolean isComplete();

    void onStep(ObservationInterface observationInterface);
}
