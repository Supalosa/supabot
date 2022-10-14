package com.supalosa.bot.analysis.production;

import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import org.immutables.value.Value;

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

}
