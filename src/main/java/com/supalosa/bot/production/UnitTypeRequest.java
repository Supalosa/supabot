package com.supalosa.bot.production;

import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.supalosa.bot.placement.PlacementRules;
import org.immutables.value.Value;

import java.util.Optional;

/**
 * Represents a desire to have an amount of a certain unit.
 */
@Value.Immutable
public interface UnitTypeRequest {

    /**
     * The unit type to produce.
     */
    UnitType unitType();

    /**
     * Alternate form of the unit (used for accounting to see if we need more).
     */
    Optional<UnitType> alternateForm();

    /**
     * The ability which produces the unit.
     */
    Ability productionAbility();

    /**
     * The unit type that produces the unit.
     */
    UnitType producingUnitType();

    /**
     * The amount that is requested.
     */
    int amount();

    /**
     * Placement rules, if this is a structure.
     */
    Optional<PlacementRules> placementRules();

    @Value.Default
    default boolean needsTechLab() {
        return false;
    }

}
