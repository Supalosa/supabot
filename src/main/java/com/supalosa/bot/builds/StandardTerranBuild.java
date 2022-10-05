package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Abilities;

import java.util.List;

public class StandardTerranBuild extends SimpleBuildOrder {

    public StandardTerranBuild() {
        super(List.of(
                SimpleBuildOrderStage.atSupply(10, Abilities.BUILD_SUPPLY_DEPOT),
                SimpleBuildOrderStage.atSupply(12, Abilities.BUILD_BARRACKS),
                SimpleBuildOrderStage.atSupplyRepeat(13,Abilities.TRAIN_MARINE),
                SimpleBuildOrderStage.atSupplyRepeat(18, Abilities.BUILD_SUPPLY_DEPOT),
                SimpleBuildOrderStage.stopWorkerProductionAt(24)
        ));
    }
}
