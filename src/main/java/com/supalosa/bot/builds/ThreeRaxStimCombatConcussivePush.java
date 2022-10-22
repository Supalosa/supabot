package com.supalosa.bot.builds;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.supalosa.bot.task.PlacementRules;

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
        super(Build.terran()
                .atSupply(0).startRepeatingUnit(Set.of(CC, ORBITAL), Abilities.TRAIN_SCV)
                .atSupply(0).setGasMiners(12)
                .atSupply(14).buildSupplyDepot().at(PlacementRules.mainRampSupplyDepot1())
                .atSupply(15).buildStructure(Abilities.BUILD_BARRACKS).at(PlacementRules.mainRampBarracksWithAddon())
                .atSupply(16).buildStructure(Abilities.BUILD_REFINERY)
                .atSupply(19).trainUnit(BARRACKS, Abilities.TRAIN_MARINE)
                .atSupply(19).morphOrbital()
                .atSupply(19).expand()
                .atSupply(20).useAbility(BARRACKS, Abilities.BUILD_REACTOR_BARRACKS)
                .atSupply(21).buildSupplyDepot().at(PlacementRules.mainRampSupplyDepot2())
                .atSupply(22).buildStructure(Abilities.BUILD_BARRACKS)
                .atSupply(22).buildStructure(Abilities.BUILD_BARRACKS)
                .atSupply(23).startRepeatingUnit(BARRACKS, Abilities.TRAIN_MARINE)
                .atSupply(29).morphOrbital()
                .atSupply(32).useAbility(BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS)
                .atSupply(32).useAbility(BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS)
                .atSupply(33).buildSupplyDepot()
                .atSupply(37).useAbility(BARRACKS_TECHLAB, Abilities.RESEARCH_STIMPACK)
                .atSupply(37).useAbility(BARRACKS_TECHLAB, Abilities.RESEARCH_CONCUSSIVE_SHELLS)
                .atSupply(41).buildSupplyDepot()
                .atSupply(41).buildStructure(Abilities.BUILD_REFINERY)
                .atSupply(43).startRepeatingUnitWithAddon(BARRACKS, BARRACKS_TECHLAB, Abilities.TRAIN_MARAUDER)
                .atSupply(49).buildStructure(Abilities.BUILD_ENGINEERING_BAY)
                //.atSupply(51).startRepeatingStructure(Abilities.BUILD_SUPPLY_DEPOT)
                .atSupply(51).buildSupplyDepot()
                .atSupply(53).useAbility(BARRACKS_TECHLAB, Abilities.RESEARCH_COMBAT_SHIELD)
                .atSupply(63).buildStructure(Abilities.BUILD_FACTORY)
                .atSupply(65).buildStructure(Abilities.BUILD_MISSILE_TURRET)
                .atSupply(65).useAbility(EBAY, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL1)
                .atSupply(65).buildSupplyDepot()
                .atSupply(65).expand()
                //.atSupply(70).startRepeatingStructure(Abilities.BUILD_SUPPLY_DEPOT)
                .atSupply(68).buildSupplyDepot()
                .atSupply(78).buildStructure(Abilities.BUILD_REFINERY)
                .atSupply(78).buildStructure(Abilities.BUILD_REFINERY)
                .atSupply(78).buildStructure(Abilities.BUILD_STARPORT)
                .atSupply(78).useAbility(FACTORY, Abilities.BUILD_REACTOR_FACTORY)
                .atSupply(80).buildSupplyDepot()
                .atSupply(80).buildSupplyDepot()
                .atSupply(86).buildStructure(Abilities.BUILD_BARRACKS)
                .atSupply(86).buildStructure(Abilities.BUILD_BARRACKS)
                .atSupply(91).startRepeatingUnit(STARPORT, Abilities.TRAIN_MEDIVAC)
                .atSupply(91).startRepeatingUnit(STARPORT, Abilities.TRAIN_MEDIVAC)
                .atSupply(95).buildSupplyDepot()
                .atSupply(95).buildSupplyDepot()
                .atSupply(97).useAbility(FACTORY, Abilities.BUILD_REACTOR_FACTORY)
                .atSupply(97).morphOrbital()
                .atSupply(102).useAbility(BARRACKS, Abilities.BUILD_REACTOR_BARRACKS)
                .atSupply(102).useAbility(BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS)
                .atSupply(115).buildStructure(Abilities.BUILD_BUNKER)
                .build());
    }
}
