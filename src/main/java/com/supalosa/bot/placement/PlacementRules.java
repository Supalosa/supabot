package com.supalosa.bot.placement;

import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.google.common.base.Preconditions;
import org.immutables.value.Value;

import java.util.Optional;

@Value.Immutable
public interface PlacementRules {

    /**
     * Build the structure anywhere in the given region (weighting may apply).
     */
    Optional<PlacementRegion> regionType();

    /**
     * Build the structure near any structure of a given type.
     */
    Optional<UnitType> near();

    /**
     * Build the structure at, or near, a specific position.
     */
    Optional<Point2d> at();

    /**
     * Build the structure on top of a specific target.
     */
    Optional<Unit> on();

    /**
     * Max variation from the given locator.
     */
    int maxVariation();

    @Value.Check
    default void check() {
        Preconditions.checkState(regionType().isPresent() ^ at().isPresent() ^ on().isPresent(),
                "Only one of regionType, at, on should be present: " + this);
    }


    static PlacementRules expansion() {
        return ImmutablePlacementRules.builder().regionType(PlacementRegion.EXPANSION).maxVariation(0).build();
    }

    static PlacementRules anyPlayerBase() {
        return ImmutablePlacementRules.builder().regionType(PlacementRegion.PLAYER_BASE_ANY).maxVariation(20).build();
    }

    static PlacementRules borderOfBase() {
        return ImmutablePlacementRules.builder().regionType(PlacementRegion.PLAYER_BASE_BORDER).maxVariation(20).build();
    }

    static PlacementRules centreOfBase() {
        return ImmutablePlacementRules.builder().regionType(PlacementRegion.PLAYER_BASE_CENTRE).maxVariation(20).build();
    }

    static PlacementRules inBaseNear(UnitType unitType) {
        return ImmutablePlacementRules.builder().regionType(PlacementRegion.PLAYER_BASE_ANY).near(unitType).maxVariation(10).build();
    }

    static PlacementRules mainRampSupplyDepot1() {
        return ImmutablePlacementRules.builder().regionType(PlacementRegion.MAIN_RAMP_SUPPLY_DEPOT_1).maxVariation(6).build();
    }

    static PlacementRules mainRampSupplyDepot2() {
        return ImmutablePlacementRules.builder().regionType(PlacementRegion.MAIN_RAMP_SUPPLY_DEPOT_2).maxVariation(6).build();
    }

    static PlacementRules mainRampBarracksWithAddon() {
        return ImmutablePlacementRules.builder().regionType(PlacementRegion.MAIN_RAMP_BARRACKS_WITH_ADDON).maxVariation(6).build();
    }

    static PlacementRules exact() {
        return ImmutablePlacementRules.builder().regionType(PlacementRegion.PLAYER_BASE_ANY).maxVariation(0).build();
    }

    static PlacementRules naturalChokePoint() {
        return ImmutablePlacementRules.builder().regionType(PlacementRegion.NATURAL_CHOKE_POINT).maxVariation(5).build();
    }

    static PlacementRules on(Unit unit) {
        return ImmutablePlacementRules.builder().on(unit).maxVariation(0).build();
    }

    static PlacementRules at(Point2d point, int maxVariation) {
        return ImmutablePlacementRules.builder().at(point).maxVariation(maxVariation).build();
    }
}
