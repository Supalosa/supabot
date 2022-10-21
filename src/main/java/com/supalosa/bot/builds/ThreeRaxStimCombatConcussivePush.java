package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;

import java.util.List;

public class ThreeRaxStimCombatConcussivePush extends SimpleBuildOrder {

    private static final Units CC = Units.TERRAN_COMMAND_CENTER;
    private static final Units SCV = Units.TERRAN_SCV;
    private static final Units BARRACKS = Units.TERRAN_BARRACKS;
    private static final Units BARRACKS_TECHLAB = Units.TERRAN_BARRACKS_TECHLAB;
    private static final Units STARPORT = Units.TERRAN_STARPORT;
    private static final Units FACTORY = Units.TERRAN_FACTORY;
    private static final Units EBAY = Units.TERRAN_ENGINEERING_BAY;

    // https://lotv.spawningtool.com/build/158142/
    public ThreeRaxStimCombatConcussivePush() {
        super(List.of(
                SimpleBuildOrderStage.atSupply(14, SCV, Abilities.BUILD_SUPPLY_DEPOT),
                SimpleBuildOrderStage.atSupply(15, SCV, Abilities.BUILD_BARRACKS),
                SimpleBuildOrderStage.atSupply(16, SCV, Abilities.BUILD_REFINERY),
                SimpleBuildOrderStage.atSupply(19, CC, Abilities.MORPH_ORBITAL_COMMAND),
                SimpleBuildOrderStage.atSupply(20, SCV, Abilities.BUILD_COMMAND_CENTER),
                SimpleBuildOrderStage.atSupply(20, BARRACKS, Abilities.BUILD_REACTOR_BARRACKS),
                SimpleBuildOrderStage.atSupply(21, SCV, Abilities.BUILD_SUPPLY_DEPOT),
                SimpleBuildOrderStage.atSupply(22, SCV, Abilities.BUILD_BARRACKS),
                SimpleBuildOrderStage.atSupply(22, SCV, Abilities.BUILD_BARRACKS),
                SimpleBuildOrderStage.atSupplyRepeat(23, BARRACKS, Abilities.TRAIN_MARINE),
                SimpleBuildOrderStage.atSupply(29, CC, Abilities.MORPH_ORBITAL_COMMAND),
                SimpleBuildOrderStage.atSupply(32, BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS),
                SimpleBuildOrderStage.atSupply(32, BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS),
                SimpleBuildOrderStage.atSupply(33, SCV, Abilities.BUILD_SUPPLY_DEPOT),
                SimpleBuildOrderStage.atSupply(37, BARRACKS_TECHLAB, Abilities.RESEARCH_STIMPACK),
                SimpleBuildOrderStage.atSupply(37, BARRACKS_TECHLAB, Abilities.RESEARCH_CONCUSSIVE_SHELLS),
                SimpleBuildOrderStage.atSupply(41, SCV, Abilities.BUILD_SUPPLY_DEPOT),
                SimpleBuildOrderStage.atSupply(41, SCV, Abilities.BUILD_REFINERY),
                SimpleBuildOrderStage.atSupplyRepeat(43, BARRACKS, Units.TERRAN_BARRACKS_TECHLAB, Abilities.TRAIN_MARAUDER),
                SimpleBuildOrderStage.atSupply(49, SCV, Abilities.BUILD_ENGINEERING_BAY),
                SimpleBuildOrderStage.atSupply(51, SCV, Abilities.BUILD_SUPPLY_DEPOT),
                SimpleBuildOrderStage.atSupply(53, BARRACKS_TECHLAB, Abilities.RESEARCH_COMBAT_SHIELD),
                SimpleBuildOrderStage.atSupply(63, SCV, Abilities.BUILD_FACTORY),
                SimpleBuildOrderStage.atSupply(65, SCV, Abilities.BUILD_MISSILE_TURRET),
                SimpleBuildOrderStage.atSupply(65, EBAY, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL1),
                SimpleBuildOrderStage.atSupply(65, SCV, Abilities.BUILD_COMMAND_CENTER),
                SimpleBuildOrderStage.atSupply(78, SCV, Abilities.BUILD_REFINERY),
                SimpleBuildOrderStage.atSupply(78, SCV, Abilities.BUILD_REFINERY),
                SimpleBuildOrderStage.atSupply(78, SCV, Abilities.BUILD_STARPORT),
                SimpleBuildOrderStage.atSupply(78, FACTORY, Abilities.BUILD_REACTOR_FACTORY),
                SimpleBuildOrderStage.atSupply(86, SCV, Abilities.BUILD_BARRACKS),
                SimpleBuildOrderStage.atSupply(86, SCV, Abilities.BUILD_BARRACKS),
                SimpleBuildOrderStage.atSupply(91, STARPORT, Abilities.TRAIN_MEDIVAC),
                SimpleBuildOrderStage.atSupply(91, STARPORT, Abilities.TRAIN_MEDIVAC),
                SimpleBuildOrderStage.atSupply(97, FACTORY, Abilities.BUILD_REACTOR_FACTORY),
                SimpleBuildOrderStage.atSupply(97, CC, Abilities.MORPH_ORBITAL_COMMAND),
                SimpleBuildOrderStage.atSupply(102, BARRACKS, Abilities.BUILD_REACTOR_BARRACKS),
                SimpleBuildOrderStage.atSupply(102, BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS),
                SimpleBuildOrderStage.atSupply(115, SCV, Abilities.BUILD_BUNKER)
        ));
    }
}
