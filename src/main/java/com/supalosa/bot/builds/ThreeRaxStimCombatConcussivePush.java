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
                .atSupply(0).startRepeatingUnit(Set.of(CC, ORBITAL), Abilities.TRAIN_SCV)
                .atSupply(0).setGasMiners(12)
                .atSupply(14).buildSupplyDepot().at(PlacementRules.mainRampSupplyDepot1())
                .then().buildStructure(Abilities.BUILD_BARRACKS).at(PlacementRules.mainRampBarracksWithAddon())
                .then().buildStructure(Abilities.BUILD_REFINERY)
                .then().trainUnit(BARRACKS, Abilities.TRAIN_MARINE)
                .after(1).of(Units.TERRAN_BARRACKS).morphOrbital()
                .then().expand()
                .atSupply(20).useAbility(BARRACKS, Abilities.BUILD_REACTOR_BARRACKS)
                .atSupply(21).buildSupplyDepot().at(PlacementRules.mainRampSupplyDepot2())
                .then().buildStructure(Abilities.BUILD_BARRACKS)
                .then().buildStructure(Abilities.BUILD_BARRACKS)
                .then().startRepeatingUnit(BARRACKS, Abilities.TRAIN_MARINE)
                .atSupply(29).morphOrbital()
                .atSupply(32).useAbility(BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS)
                .then().useAbility(BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS)
                .atSupply(33).buildSupplyDepot()
                .atSupply(37).useAbility(BARRACKS_TECHLAB, Abilities.RESEARCH_STIMPACK)
                .then().useAbility(BARRACKS_TECHLAB, Abilities.RESEARCH_CONCUSSIVE_SHELLS)
                .atSupply(41).buildSupplyDepot()
                .then().buildStructure(Abilities.BUILD_REFINERY)
                .atSupply(43).startRepeatingUnitWithAddon(BARRACKS, BARRACKS_TECHLAB, Abilities.TRAIN_MARAUDER)
                .atSupply(43).startRepeatingUnitWithAddon(BARRACKS, BARRACKS_TECHLAB, Abilities.TRAIN_MARAUDER)
                .atSupply(49).buildStructure(Abilities.BUILD_ENGINEERING_BAY)
                .atSupply(51).buildSupplyDepot()
                .atSupply(53).useAbility(BARRACKS_TECHLAB, Abilities.RESEARCH_COMBAT_SHIELD)
                .then().attack()
                .then().buildSupplyDepot()
                .then().buildStructure(Abilities.BUILD_FACTORY)
                .then().buildStructure(Abilities.BUILD_MISSILE_TURRET)
                .then().useAbility(EBAY, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL1)
                .then().buildSupplyDepot()
                .then().expand()
                .then().attack()
                .then().buildSupplyDepot()
                .then().buildStructure(Abilities.BUILD_REFINERY)
                .then().buildStructure(Abilities.BUILD_REFINERY)
                .then().buildStructure(Abilities.BUILD_STARPORT)
                .then().useAbility(FACTORY, Abilities.BUILD_REACTOR_FACTORY)
                .then().buildSupplyDepot()
                .then().buildSupplyDepot()
                .then().attack()
                .then().buildStructure(Abilities.BUILD_BARRACKS)
                .then().buildStructure(Abilities.BUILD_BARRACKS)
                .then().startRepeatingUnit(STARPORT, Abilities.TRAIN_MEDIVAC)
                .then().startRepeatingUnit(STARPORT, Abilities.TRAIN_MEDIVAC)
                .then().buildSupplyDepot()
                .then().buildSupplyDepot()
                .then().attack()
                .then().useAbility(STARPORT, Abilities.BUILD_REACTOR_STARPORT)
                .then().useAbility(BARRACKS, Abilities.BUILD_REACTOR_BARRACKS)
                .then().useAbility(BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS)
                .then().morphOrbital()
                .then().attack()
                .build());
    }
}
