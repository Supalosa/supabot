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

    public static final Set<UnitType> TERRAN_CC_TYPES = Set.of(
            Units.TERRAN_ORBITAL_COMMAND,
            Units.TERRAN_COMMAND_CENTER,
            Units.TERRAN_PLANETARY_FORTRESS
    );
    public static final UnitType[] TERRAN_CC_TYPES_ARRAY = TERRAN_CC_TYPES.toArray(new UnitType[]{});

    public static final Set<UnitType> MINERAL_TYPES = Set.of(
            Units.NEUTRAL_MINERAL_FIELD, Units.NEUTRAL_MINERAL_FIELD750,
            Units.NEUTRAL_RICH_MINERAL_FIELD, Units.NEUTRAL_RICH_MINERAL_FIELD750,
            Units.NEUTRAL_PURIFIER_MINERAL_FIELD, Units.NEUTRAL_PURIFIER_MINERAL_FIELD750,
            Units.NEUTRAL_PURIFIER_RICH_MINERAL_FIELD, Units.NEUTRAL_PURIFIER_RICH_MINERAL_FIELD750,
            Units.NEUTRAL_LAB_MINERAL_FIELD, Units.NEUTRAL_LAB_MINERAL_FIELD750,
            Units.NEUTRAL_BATTLE_STATION_MINERAL_FIELD, Units.NEUTRAL_BATTLE_STATION_MINERAL_FIELD750
    );

    public static final Set<Units> VESPENE_GEYSER_TYPES = Set.of(Units.NEUTRAL_VESPENE_GEYSER, Units.NEUTRAL_PROTOSS_VESPENE_GEYSER,
            Units.NEUTRAL_SPACE_PLATFORM_GEYSER, Units.NEUTRAL_PURIFIER_VESPENE_GEYSER,
            Units.NEUTRAL_SHAKURAS_VESPENE_GEYSER, Units.NEUTRAL_RICH_VESPENE_GEYSER);
}
