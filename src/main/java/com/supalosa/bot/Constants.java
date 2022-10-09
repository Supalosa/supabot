package com.supalosa.bot;

import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;

import java.util.Set;

public class Constants {
    // Incomplete.
    public static final Set<UnitType> STRUCTURE_UNIT_TYPES = Set.of(
            Units.TERRAN_COMMAND_CENTER,
            Units.TERRAN_SUPPLY_DEPOT,
            Units.TERRAN_SUPPLY_DEPOT_LOWERED,
            Units.TERRAN_BARRACKS
    );
}
