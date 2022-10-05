package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Abilities;

import java.util.List;

public class SimpleBuildOrder implements BuildOrder {

    private List<SimpleBuildOrderStage> stages;

    SimpleBuildOrder(List<SimpleBuildOrderStage> stages) {
        this.stages = stages;
    }

    @Override
    public final Abilities getNextAbility(ObservationInterface observationInterface) {
        return null;
    }
}
