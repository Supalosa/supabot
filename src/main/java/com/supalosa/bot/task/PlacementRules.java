package com.supalosa.bot.task;

import com.github.ocraft.s2client.protocol.data.UnitType;
import org.immutables.value.Value;

import java.util.Optional;

@Value.Immutable
public interface PlacementRules {
    enum Region {
        /**
         * Place it in any player owned region.
         */
        ANY_PLAYER_BASE,
        /**
         * Place it on an expansion only.
         */
        EXPANSION,
        /**
         * Place it on the terran ramp.
         */
        MAIN_RAMP_SUPPLY_DEPOT_1,
        /**
         * Place it on the terran ramp.
         */
        MAIN_RAMP_SUPPLY_DEPOT_2,
        /**
         * Place it on the terran ramp with space for the addon.
         */
        MAIN_RAMP_BARRACKS_WITH_ADDON,
    }

    Region regionType();

    int maxVariation();

    Optional<UnitType> near();

    static PlacementRules expansion() {
        return ImmutablePlacementRules.builder().regionType(Region.EXPANSION).maxVariation(0).build();
    }

    static PlacementRules inBase() {
        return ImmutablePlacementRules.builder().regionType(Region.ANY_PLAYER_BASE).maxVariation(20).build();
    }

    static PlacementRules inBaseNear(UnitType unitType) {
        return ImmutablePlacementRules.builder().regionType(Region.ANY_PLAYER_BASE).near(unitType).maxVariation(10).build();
    }

    static PlacementRules mainRampSupplyDepot1() {
        return ImmutablePlacementRules.builder().regionType(Region.MAIN_RAMP_SUPPLY_DEPOT_1).maxVariation(6).build();
    }

    static PlacementRules mainRampSupplyDepot2() {
        return ImmutablePlacementRules.builder().regionType(Region.MAIN_RAMP_SUPPLY_DEPOT_2).maxVariation(6).build();
    }

    static PlacementRules mainRampBarracksWithAddon() {
        return ImmutablePlacementRules.builder().regionType(Region.MAIN_RAMP_BARRACKS_WITH_ADDON).maxVariation(6).build();
    }
}
