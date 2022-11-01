package com.supalosa.bot;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;

import java.util.HashSet;
import java.util.Set;

import static java.util.Arrays.asList;

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

    public static final Set<UnitType> TERRAN_ADDON_TYPES = Set.of(
            Units.TERRAN_BARRACKS_TECHLAB,
            Units.TERRAN_FACTORY_TECHLAB,
            Units.TERRAN_STARPORT_TECHLAB,
            Units.TERRAN_BARRACKS_REACTOR,
            Units.TERRAN_FACTORY_REACTOR,
            Units.TERRAN_STARPORT_REACTOR
    );

    public static final Set<UnitType> TERRAN_TECHLAB_TYPES = Set.of(
            Units.TERRAN_BARRACKS_TECHLAB,
            Units.TERRAN_FACTORY_TECHLAB,
            Units.TERRAN_STARPORT_TECHLAB
    );

    public static final Set<UnitType> TERRAN_REACTOR_TYPES = Set.of(
            Units.TERRAN_BARRACKS_REACTOR,
            Units.TERRAN_FACTORY_REACTOR,
            Units.TERRAN_STARPORT_REACTOR
    );

    public static final Set<UnitType> ALL_TOWN_HALL_TYPES = Set.of(
            Units.TERRAN_ORBITAL_COMMAND,
            Units.TERRAN_COMMAND_CENTER,
            Units.TERRAN_PLANETARY_FORTRESS,
            Units.ZERG_HATCHERY,
            Units.ZERG_LAIR,
            Units.ZERG_HIVE,
            Units.PROTOSS_NEXUS
    );

    public static final Set<UnitType> MINERAL_TYPES = Set.of(
            Units.NEUTRAL_MINERAL_FIELD, Units.NEUTRAL_MINERAL_FIELD750,
            Units.NEUTRAL_RICH_MINERAL_FIELD, Units.NEUTRAL_RICH_MINERAL_FIELD750,
            Units.NEUTRAL_PURIFIER_MINERAL_FIELD, Units.NEUTRAL_PURIFIER_MINERAL_FIELD750,
            Units.NEUTRAL_PURIFIER_RICH_MINERAL_FIELD, Units.NEUTRAL_PURIFIER_RICH_MINERAL_FIELD750,
            Units.NEUTRAL_LAB_MINERAL_FIELD, Units.NEUTRAL_LAB_MINERAL_FIELD750,
            Units.NEUTRAL_BATTLE_STATION_MINERAL_FIELD, Units.NEUTRAL_BATTLE_STATION_MINERAL_FIELD750
    );

    public static final Set<UnitType> VESPENE_GEYSER_TYPES = Set.of(Units.NEUTRAL_VESPENE_GEYSER, Units.NEUTRAL_PROTOSS_VESPENE_GEYSER,
            Units.NEUTRAL_SPACE_PLATFORM_GEYSER, Units.NEUTRAL_PURIFIER_VESPENE_GEYSER,
            Units.NEUTRAL_SHAKURAS_VESPENE_GEYSER, Units.NEUTRAL_RICH_VESPENE_GEYSER);

    public static final Set<UnitType> FIELD_TYPES = new HashSet<>(asList(
            Units.NEUTRAL_MINERAL_FIELD, Units.NEUTRAL_MINERAL_FIELD750,
            Units.NEUTRAL_RICH_MINERAL_FIELD, Units.NEUTRAL_RICH_MINERAL_FIELD750,
            Units.NEUTRAL_PURIFIER_MINERAL_FIELD, Units.NEUTRAL_PURIFIER_MINERAL_FIELD750,
            Units.NEUTRAL_PURIFIER_RICH_MINERAL_FIELD, Units.NEUTRAL_PURIFIER_RICH_MINERAL_FIELD750,
            Units.NEUTRAL_LAB_MINERAL_FIELD, Units.NEUTRAL_LAB_MINERAL_FIELD750,
            Units.NEUTRAL_BATTLE_STATION_MINERAL_FIELD, Units.NEUTRAL_BATTLE_STATION_MINERAL_FIELD750,
            Units.NEUTRAL_VESPENE_GEYSER, Units.NEUTRAL_PROTOSS_VESPENE_GEYSER,
            Units.NEUTRAL_SPACE_PLATFORM_GEYSER, Units.NEUTRAL_PURIFIER_VESPENE_GEYSER,
            Units.NEUTRAL_SHAKURAS_VESPENE_GEYSER, Units.NEUTRAL_RICH_VESPENE_GEYSER
    ));

    public static final Set<UnitType> WORKER_TYPES = Set.of(
            Units.TERRAN_SCV,
            Units.ZERG_DRONE,
            Units.PROTOSS_PROBE
    );

    public static final Set<Ability> BUILD_GAS_STRUCTURE_ABILITIES = Set.of(
            Abilities.BUILD_REFINERY,
            Abilities.BUILD_EXTRACTOR,
            Abilities.BUILD_ASSIMILATOR
    );

    /**
     * Units that we will classify as 'army', so not harrassment units.
     */
    public static final Set<UnitType> ARMY_UNIT_TYPES = Set.of(
            Units.TERRAN_MARINE,
            Units.TERRAN_MARAUDER,
            Units.TERRAN_GHOST,
            Units.TERRAN_SIEGE_TANK,
            Units.TERRAN_SIEGE_TANK_SIEGED,
            Units.TERRAN_LIBERATOR,
            Units.TERRAN_LIBERATOR_AG,
            Units.TERRAN_THOR,
            Units.TERRAN_THOR_AP,
            Units.TERRAN_BATTLECRUISER,
            Units.TERRAN_BUNKER,
            Units.TERRAN_PLANETARY_FORTRESS,
            Units.ZERG_ZERGLING,
            Units.ZERG_ROACH,
            Units.ZERG_BANELING,
            Units.ZERG_HYDRALISK,
            Units.ZERG_LURKER_MP,
            Units.ZERG_INFESTOR,
            Units.ZERG_ULTRALISK,
            Units.ZERG_MUTALISK,
            Units.ZERG_CORRUPTOR,
            Units.ZERG_RAVAGER,
            Units.ZERG_QUEEN, // thanks to queen bot
            Units.ZERG_SPINE_CRAWLER,
            Units.ZERG_BROODLORD,
            Units.ZERG_BROODLORD_COCOON,
            Units.PROTOSS_ZEALOT,
            Units.PROTOSS_ADEPT,
            Units.PROTOSS_ADEPT_PHASE_SHIFT,
            Units.PROTOSS_ARCHON,
            Units.PROTOSS_COLOSSUS,
            Units.PROTOSS_IMMORTAL,
            Units.PROTOSS_CARRIER,
            Units.PROTOSS_TEMPEST,
            Units.PROTOSS_SENTRY,
            Units.PROTOSS_DISRUPTOR,
            Units.PROTOSS_STALKER,
            Units.PROTOSS_PHOTON_CANNON);

    /**
     * Structures that should be built, even if the region is under attack. The idea is that these are
     * structures that might help defend.
     */
    public static final Set<UnitType> CRITICAL_STRUCTURE_TYPES = Set.of(
            Units.TERRAN_BARRACKS,
            Units.TERRAN_FACTORY,
            Units.TERRAN_STARPORT,
            Units.TERRAN_SUPPLY_DEPOT,
            Units.TERRAN_BUNKER,
            Units.TERRAN_MISSILE_TURRET,
            Units.PROTOSS_FORGE,
            Units.PROTOSS_GATEWAY,
            Units.PROTOSS_PHOTON_CANNON,
            Units.PROTOSS_PYLON,
            Units.PROTOSS_SHIELD_BATTERY,
            Units.ZERG_SPAWNING_POOL,
            Units.ZERG_SPINE_CRAWLER,
            Units.ZERG_SPORE_CRAWLER
    );

    /**
     * Things that should be focus-fired by antiair units like Vikings.
     */
    public static Set<UnitType> ANTIAIR_ATTACKABLE_UNIT_TYPES = Set.of(
            Units.PROTOSS_COLOSSUS,
            Units.PROTOSS_CARRIER,
            Units.PROTOSS_ORACLE,
            Units.PROTOSS_TEMPEST,
            Units.PROTOSS_PHOENIX,
            Units.TERRAN_LIBERATOR,
            Units.TERRAN_LIBERATOR_AG,
            Units.TERRAN_BANSHEE,
            Units.TERRAN_VIKING_FIGHTER,
            Units.ZERG_MUTALISK,
            Units.ZERG_BROODLORD,
            Units.ZERG_CORRUPTOR
    );


    public static Set<UnitType> ANTI_AIR_UNIT_TYPES = Set.of(
            Units.PROTOSS_STALKER,
            Units.PROTOSS_ARCHON,
            Units.PROTOSS_CARRIER,
            Units.PROTOSS_HIGH_TEMPLAR,
            Units.PROTOSS_PHOTON_CANNON,
            Units.PROTOSS_PHOENIX,
            Units.PROTOSS_TEMPEST,
            Units.ZERG_CORRUPTOR,
            Units.ZERG_MUTALISK,
            Units.ZERG_HYDRALISK,
            Units.TERRAN_MARINE,
            Units.TERRAN_THOR,
            Units.TERRAN_THOR_AP,
            Units.TERRAN_VIKING_FIGHTER,
            Units.TERRAN_BATTLECRUISER);
}
