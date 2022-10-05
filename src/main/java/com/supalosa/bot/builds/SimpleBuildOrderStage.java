package com.supalosa.bot.builds;

import com.github.ocraft.s2client.protocol.data.Abilities;
import org.immutables.value.Value;

import java.time.Duration;
import java.util.Optional;

@Value.Immutable
public abstract class SimpleBuildOrderStage {
    @Value.Parameter
    public abstract int supplyTrigger();

    @Value.Parameter
    public abstract Optional<Abilities> ability();

    @Value.Default
    public boolean expand() {
        return false;
    }

    @Value.Default
    public boolean stopWorkerProduction() {
        return false;
    }

    @Value.Default
    public boolean repeat() {
        return false;
    }

    @Value.Default
    public boolean stopRepeating() {
        return false;
    }

    static SimpleBuildOrderStage atSupply(int supply, Abilities ability) {
        return ImmutableSimpleBuildOrderStage.builder().supplyTrigger(supply).ability(ability).build();
    }

    static SimpleBuildOrderStage atSupplyRepeat(int supply, Abilities ability) {
        return ImmutableSimpleBuildOrderStage.builder().supplyTrigger(supply).ability(ability).repeat(true).build();
    }
    static SimpleBuildOrderStage stopWorkerProductionAt(int supply) {
        return ImmutableSimpleBuildOrderStage.builder().supplyTrigger(supply).stopWorkerProduction(true).build();
    }

    static SimpleBuildOrderStage resumeWorkerProductionAt(int supply) {
        return ImmutableSimpleBuildOrderStage.builder().supplyTrigger(supply).stopWorkerProduction(false).build();
    }
}
