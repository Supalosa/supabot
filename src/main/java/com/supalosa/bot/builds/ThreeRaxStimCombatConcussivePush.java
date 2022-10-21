package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;

import java.util.List;
import java.util.Set;

public class ThreeRaxStimCombatConcussivePush extends SimpleBuildOrder {

    private static final Units CC = Units.TERRAN_COMMAND_CENTER;
    private static final Units ORBITAL = Units.TERRAN_ORBITAL_COMMAND;
    private static final Units SCV = Units.TERRAN_SCV;
    private static final Units BARRACKS = Units.TERRAN_BARRACKS;
    private static final Units BARRACKS_TECHLAB = Units.TERRAN_BARRACKS_TECHLAB;
    private static final Units STARPORT = Units.TERRAN_STARPORT;
    private static final Units FACTORY = Units.TERRAN_FACTORY;
    private static final Units EBAY = Units.TERRAN_ENGINEERING_BAY;

    // https://lotv.spawningtool.com/build/158142/
    public ThreeRaxStimCombatConcussivePush() {
        super(List.of(
                Build.atSupplyRepeat(1, Set.of(CC, ORBITAL), Abilities.TRAIN_SCV),
                Build.atSupplySetGasMiners(1, 12),
                Build.atSupply(14, SCV, Abilities.BUILD_SUPPLY_DEPOT),
                Build.atSupply(15, SCV, Abilities.BUILD_BARRACKS),
                Build.atSupply(16, SCV, Abilities.BUILD_REFINERY),
                Build.atSupply(19, BARRACKS, Abilities.TRAIN_MARINE),
                Build.atSupply(19, CC, Abilities.MORPH_ORBITAL_COMMAND),
                //Build.atSupply(20, SCV, Abilities.BUILD_COMMAND_CENTER),
                Build.atSupplyExpand(19, SCV, Abilities.BUILD_COMMAND_CENTER),
                Build.atSupply(20, BARRACKS, Abilities.BUILD_REACTOR_BARRACKS),
                Build.atSupply(21, SCV, Abilities.BUILD_SUPPLY_DEPOT),
                Build.atSupply(22, SCV, Abilities.BUILD_BARRACKS),
                Build.atSupply(22, SCV, Abilities.BUILD_BARRACKS),
                Build.atSupplyRepeat(23, BARRACKS, Abilities.TRAIN_MARINE),
                Build.atSupply(29, CC, Abilities.MORPH_ORBITAL_COMMAND),
                Build.atSupply(32, BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS),
                Build.atSupply(32, BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS),
                Build.atSupply(33, SCV, Abilities.BUILD_SUPPLY_DEPOT),
                Build.atSupply(37, BARRACKS_TECHLAB, Abilities.RESEARCH_STIMPACK),
                Build.atSupply(37, BARRACKS_TECHLAB, Abilities.RESEARCH_CONCUSSIVE_SHELLS),
                Build.atSupply(41, SCV, Abilities.BUILD_SUPPLY_DEPOT),
                Build.atSupply(41, SCV, Abilities.BUILD_REFINERY),
                Build.atSupplyRepeat(43, BARRACKS, Units.TERRAN_BARRACKS_TECHLAB, Abilities.TRAIN_MARAUDER),
                Build.atSupply(49, SCV, Abilities.BUILD_ENGINEERING_BAY),
                Build.atSupply(51, SCV, Abilities.BUILD_SUPPLY_DEPOT),
                Build.atSupply(53, BARRACKS_TECHLAB, Abilities.RESEARCH_COMBAT_SHIELD),
                Build.atSupply(63, SCV, Abilities.BUILD_FACTORY),
                Build.atSupply(65, SCV, Abilities.BUILD_MISSILE_TURRET),
                Build.atSupply(65, EBAY, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL1),
                //Build.atSupply(65, SCV, Abilities.BUILD_COMMAND_CENTER),
                Build.atSupplyExpand(65, SCV, Abilities.BUILD_COMMAND_CENTER),
                Build.atSupply(78, SCV, Abilities.BUILD_REFINERY),
                Build.atSupply(78, SCV, Abilities.BUILD_REFINERY),
                Build.atSupply(78, SCV, Abilities.BUILD_STARPORT),
                Build.atSupply(78, FACTORY, Abilities.BUILD_REACTOR_FACTORY),
                Build.atSupply(86, SCV, Abilities.BUILD_BARRACKS),
                Build.atSupply(86, SCV, Abilities.BUILD_BARRACKS),
                Build.atSupply(91, STARPORT, Abilities.TRAIN_MEDIVAC),
                Build.atSupply(91, STARPORT, Abilities.TRAIN_MEDIVAC),
                Build.atSupply(97, FACTORY, Abilities.BUILD_REACTOR_FACTORY),
                Build.atSupply(97, CC, Abilities.MORPH_ORBITAL_COMMAND),
                Build.atSupply(102, BARRACKS, Abilities.BUILD_REACTOR_BARRACKS),
                Build.atSupply(102, BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS),
                Build.atSupply(115, SCV, Abilities.BUILD_BUNKER)
        ));
    }
}
