package com.supalosa.bot.placement;

import com.github.ocraft.s2client.protocol.data.UnitType;
import org.immutables.value.Value;

import java.util.Optional;

@Value.Immutable
public interface PlacementRules {

    enum Region {
        /**
         * Place it in any player owned region.
         */
        PLAYER_BASE_ANY(true),
        /**
         * Place it on the border of the player base.
         */
        PLAYER_BASE_BORDER(true),
        /**
         * Place it on the centre of the player base.
         */
        PLAYER_BASE_CENTRE(true),
        /**
         * Place it on an expansion only.
         */
        EXPANSION(true),
        /**
         * Place it on the terran ramp.
         */
        MAIN_RAMP_SUPPLY_DEPOT_1(true),
        /**
         * Place it on the terran ramp.
         */
        MAIN_RAMP_SUPPLY_DEPOT_2(true),
        /**
         * Place it on the terran ramp with space for the addon.
         */
        MAIN_RAMP_BARRACKS_WITH_ADDON(true),
        /**
         * Place it at the natural choke point, or else the main base choke point.
         */
        NATURAL_CHOKE_POINT(false);

        private boolean playerBase;

        Region(boolean playerBase) {
            this.playerBase = playerBase;
        }

        /**
         * True if the worker must be pulled from a player base.
         */
        public boolean isPlayerBase() {
            return this.playerBase;
        }
    }

    Region regionType();

    int maxVariation();

    Optional<UnitType> near();

    static PlacementRules expansion() {
        return ImmutablePlacementRules.builder().regionType(Region.EXPANSION).maxVariation(0).build();
    }

    static PlacementRules anyPlayerBase() {
        return ImmutablePlacementRules.builder().regionType(Region.PLAYER_BASE_ANY).maxVariation(20).build();
    }

    static PlacementRules borderOfBase() {
        return ImmutablePlacementRules.builder().regionType(Region.PLAYER_BASE_BORDER).maxVariation(20).build();
    }

    static PlacementRules centreOfBase() {
        return ImmutablePlacementRules.builder().regionType(Region.PLAYER_BASE_CENTRE).maxVariation(20).build();
    }

    static PlacementRules inBaseNear(UnitType unitType) {
        return ImmutablePlacementRules.builder().regionType(Region.PLAYER_BASE_ANY).near(unitType).maxVariation(10).build();
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

    static PlacementRules exact() {
        return ImmutablePlacementRules.builder().regionType(Region.PLAYER_BASE_ANY).maxVariation(0).build();
    }

    static PlacementRules naturalChokePoint() {
        return ImmutablePlacementRules.builder().regionType(Region.NATURAL_CHOKE_POINT).maxVariation(5).build();
    }
}
