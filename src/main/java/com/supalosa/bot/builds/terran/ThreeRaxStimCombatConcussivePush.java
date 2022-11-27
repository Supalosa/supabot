package com.supalosa.bot.builds.terran;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.supalosa.bot.builds.Build;
import com.supalosa.bot.builds.SimpleBuildOrder;
import com.supalosa.bot.placement.PlacementRules;
import com.supalosa.bot.strategy.WorkerRushStrategicObservation;
import com.supalosa.bot.strategy.Zerg12PoolStrategicObservation;
import com.supalosa.bot.task.army.TerranWorkerRushDefenceTask;

import java.util.Set;

public class ThreeRaxStimCombatConcussivePush extends SimpleBuildOrder {

    private static final Units CC = Units.TERRAN_COMMAND_CENTER;
    private static final Units ORBITAL = Units.TERRAN_ORBITAL_COMMAND;
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
                // TODO: perhaps these should be in a generic Terran Reaction class.
                .onObservationOf(WorkerRushStrategicObservation.class).dispatchTask(() -> new TerranWorkerRushDefenceTask())
                // Wall off earlier if a 12 pool is coming.
                .onObservationOf(Zerg12PoolStrategicObservation.class).buildSupplyDepot().at(PlacementRules.mainRampSupplyDepot2())
                .onObservationOf(Zerg12PoolStrategicObservation.class).startRepeatingUnit(Units.TERRAN_BARRACKS, Abilities.TRAIN_MARINE)
                .atSupply(14).buildSupplyDepot().at(PlacementRules.mainRampSupplyDepot1())
                .then().buildStructure(Abilities.BUILD_BARRACKS).at(PlacementRules.mainRampBarracksWithAddon())
                .then().buildStructure(Abilities.BUILD_REFINERY).at(PlacementRules.freeVespeneGeyser())
                .then().trainUnit(BARRACKS, Abilities.TRAIN_MARINE)
                .after(1).of(Units.TERRAN_BARRACKS).morphOrbital()
                .then().expand()
                .atSupply(20).useAbility(BARRACKS, Abilities.BUILD_REACTOR_BARRACKS)
                .atSupply(21).buildSupplyDepot().at(PlacementRules.mainRampSupplyDepot2())
                .then().buildStructure(Abilities.BUILD_BARRACKS).at(PlacementRules.centreOfBase())
                .then().buildStructure(Abilities.BUILD_BARRACKS).at(PlacementRules.centreOfBase())
                .then().startRepeatingUnit(BARRACKS, Abilities.TRAIN_MARINE)
                .then().startRepeatingUnit(BARRACKS, Abilities.TRAIN_MARINE)
                .atSupply(29).morphOrbital()
                .atSupply(32).useAbility(BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS)
                .then().useAbility(BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS)
                .atSupply(33).buildSupplyDepot().at(PlacementRules.borderOfBase())
                .atSupply(37).useAbility(BARRACKS_TECHLAB, Abilities.RESEARCH_STIMPACK)
                .then().useAbility(BARRACKS_TECHLAB, Abilities.RESEARCH_CONCUSSIVE_SHELLS)
                .atSupply(41).buildSupplyDepot().at(PlacementRules.borderOfBase())
                .then().buildStructure(Abilities.BUILD_REFINERY).at(PlacementRules.freeVespeneGeyser())
                .atSupply(43).startRepeatingUnitWithAddon(BARRACKS, BARRACKS_TECHLAB, Abilities.TRAIN_MARAUDER)
                .atSupply(43).startRepeatingUnitWithAddon(BARRACKS, BARRACKS_TECHLAB, Abilities.TRAIN_MARAUDER)
                .atSupply(49).buildStructure(Abilities.BUILD_ENGINEERING_BAY).at(PlacementRules.borderOfBase())
                .atSupply(51).buildSupplyDepot().at(PlacementRules.borderOfBase())
                .then().startRepeatingStructure(Abilities.BUILD_SUPPLY_DEPOT).at(PlacementRules.borderOfBase())
                .then().startRepeatingStructure(Abilities.BUILD_SUPPLY_DEPOT).at(PlacementRules.borderOfBase())
                .atSupply(53).useAbility(BARRACKS_TECHLAB, Abilities.RESEARCH_COMBAT_SHIELD)
                .after(10).of(Units.TERRAN_MARINE).attack()
                .then().buildStructure(Abilities.BUILD_FACTORY).at(PlacementRules.borderOfBase())
                .then().buildStructure(Abilities.BUILD_MISSILE_TURRET).at(PlacementRules.centreOfBase())
                .then().useAbility(EBAY, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL1)
                .then().expand()
                .then().attack()
                .then().buildStructure(Abilities.BUILD_REFINERY).at(PlacementRules.freeVespeneGeyser())
                .then().buildStructure(Abilities.BUILD_REFINERY).at(PlacementRules.freeVespeneGeyser())
                .then().buildStructure(Abilities.BUILD_STARPORT).near(Units.TERRAN_FACTORY)
                .then().useAbility(FACTORY, Abilities.BUILD_REACTOR_FACTORY)
                .then().attack()
                .then().buildStructure(Abilities.BUILD_BARRACKS)
                .then().buildStructure(Abilities.BUILD_BARRACKS)
                .then().swapAddonsBetween(Units.TERRAN_FACTORY, Units.TERRAN_STARPORT)
                .then().startRepeatingUnit(STARPORT, Abilities.TRAIN_MEDIVAC)
                .then().startRepeatingUnit(STARPORT, Abilities.TRAIN_MEDIVAC)
                .then().buildStructure(Abilities.BUILD_BARRACKS)
                .then().buildStructure(Abilities.BUILD_BARRACKS)
                .then().attack()
                .then().useAbility(BARRACKS, Abilities.BUILD_REACTOR_BARRACKS)
                .then().useAbility(FACTORY, Abilities.BUILD_REACTOR_FACTORY)
                .then().startRepeatingUnit(BARRACKS, Abilities.TRAIN_MARINE)
                .then().startRepeatingUnit(BARRACKS, Abilities.TRAIN_MARINE)
                .then().useAbility(BARRACKS, Abilities.BUILD_TECHLAB_BARRACKS)
                .then().startRepeatingUnitWithAddon(BARRACKS, BARRACKS_TECHLAB, Abilities.TRAIN_MARAUDER)
                .then().startRepeatingUnit(FACTORY, Abilities.TRAIN_WIDOWMINE)
                .then().startRepeatingUnit(FACTORY, Abilities.TRAIN_WIDOWMINE)
                .then().morphOrbital()
                .then().attack()
                .then().expand()
                .build());
    }
}
