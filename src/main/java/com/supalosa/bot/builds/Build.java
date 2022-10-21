package com.supalosa.bot.builds;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.supalosa.bot.utils.UnitFilter;

import java.util.Set;

/**
 * Helper class to construct build order stages.
 */
public class Build {

    static SimpleBuildOrderStage atSupply(int supply, UnitType unitType, Abilities ability) {
        return ImmutableSimpleBuildOrderStage.builder()
                .supplyTrigger(supply)
                .ability(ability)
                .unitFilter(UnitFilter.mine(unitType))
                .build();
    }

    static SimpleBuildOrderStage atSupply(int supply, UnitType unitType, UnitType addonType, Abilities ability) {
        return ImmutableSimpleBuildOrderStage.builder()
                .supplyTrigger(supply)
                .ability(ability)
                .addonType(addonType)
                .unitFilter(UnitFilter.mine(unitType))
                .build();
    }

    static SimpleBuildOrderStage atSupplySetGasMiners(int supply, int miners) {
        return ImmutableSimpleBuildOrderStage.builder()
                .supplyTrigger(supply)
                .gasMiners(miners)
                .build();
    }

    static SimpleBuildOrderStage atSupplyExpand(int supply, UnitType unitType, Abilities ability) {
        return ImmutableSimpleBuildOrderStage.builder()
                .supplyTrigger(supply)
                .ability(ability)
                .unitFilter(UnitFilter.mine(unitType))
                .expand(true)
                .build();
    }

    static SimpleBuildOrderStage atSupplyRepeat(int supply, UnitType unitType, Abilities ability) {
        return ImmutableSimpleBuildOrderStage.builder()
                .supplyTrigger(supply)
                .ability(ability)
                .unitFilter(UnitFilter.mine(unitType))
                .repeat(true).build();
    }

    static SimpleBuildOrderStage atSupplyRepeat(int supply, Set<UnitType> unitTypes, Abilities ability) {
        return ImmutableSimpleBuildOrderStage.builder()
                .supplyTrigger(supply)
                .ability(ability)
                .unitFilter(UnitFilter.mine(unitTypes))
                .repeat(true).build();
    }

    static SimpleBuildOrderStage atSupplyRepeat(int supply, UnitType unitType, UnitType addonType, Abilities ability) {
        return ImmutableSimpleBuildOrderStage.builder()
                .supplyTrigger(supply)
                .ability(ability)
                .addonType(addonType)
                .unitFilter(UnitFilter.mine(unitType))
                .repeat(true).build();
    }

    static SimpleBuildOrderStage stopRepeating(int supply) {
        return ImmutableSimpleBuildOrderStage.builder().supplyTrigger(supply).stopWorkerProduction(true).build();
    }

    static SimpleBuildOrderStage resumeWorkerProductionAt(int supply) {
        return ImmutableSimpleBuildOrderStage.builder().supplyTrigger(supply).stopWorkerProduction(false).build();
    }
}
