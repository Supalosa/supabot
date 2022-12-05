package com.supalosa.bot;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.task.army.ArmyTask;

import java.util.ArrayList;
import java.util.List;

public class TerranBioCompositionChooser implements CompositionChooser {

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = Long.MIN_VALUE;

    private AoeOptions selectedAoeOption = AoeOptions.NONE;

    private enum AoeOptions {
        NONE,
        SIEGE_TANK,
        WIDOW_MINE
    };

    @Override
    public void onStep(AgentWithData agentWithData) {
        long gameLoop = agentWithData.observation().getGameLoop();

        if (gameLoop > desiredCompositionUpdatedAt + 22L) {
            desiredCompositionUpdatedAt = gameLoop;
            updateDesiredComposition(agentWithData);
        }
    }

    private void updateDesiredComposition(AgentWithData agentWithData) {

        ArmyTask mainArmy = agentWithData.fightManager().getMainArmy();

        List<UnitTypeRequest> result = new ArrayList<>();
        int maxBio = 120;
        int targetMarines = 40;
        int targetMarauders = 40;
        Army enemyArmy = agentWithData.enemyAwareness().getOverallEnemyArmy();

        targetMarauders +=
                enemyArmy.composition().getOrDefault(Units.ZERG_ROACH, 0) +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_STALKER, 0) * 2 +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_COLOSSUS, 0) * 3 +
                        enemyArmy.composition().getOrDefault(Units.TERRAN_SIEGE_TANK, 0) * 3 +
                        enemyArmy.composition().getOrDefault(Units.TERRAN_SIEGE_TANK_SIEGED, 0) * 3 +
                        enemyArmy.composition().getOrDefault(Units.ZERG_ULTRALISK, 0) * 5;

        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARINE)
                .productionAbility(Abilities.TRAIN_MARINE)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .amount(Math.max(0, maxBio - targetMarauders * 2))
                .build()
        );
        if (mainArmy.getSize() > 5) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_MARAUDER)
                    .productionAbility(Abilities.TRAIN_MARAUDER)
                    .producingUnitType(Units.TERRAN_BARRACKS)
                    .needsTechLab(true)
                    .amount(Math.max(0, maxBio -  targetMarines))
                    .build()
            );
        }
        if (mainArmy.getSize() > 10) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_WIDOWMINE)
                    .alternateForm(Units.TERRAN_WIDOWMINE_BURROWED)
                    .productionAbility(Abilities.TRAIN_WIDOWMINE)
                    .producingUnitType(Units.TERRAN_FACTORY)
                    .needsTechLab(false)
                    .amount(10)
                    .build()
            );
        }

        int targetVikings =
                enemyArmy.composition().getOrDefault(Units.ZERG_BROODLORD, 0) * 2 +
                        enemyArmy.composition().getOrDefault(Units.TERRAN_BATTLECRUISER, 0) * 2 +
                        enemyArmy.composition().getOrDefault(Units.TERRAN_LIBERATOR, 0) * 1 +
                        enemyArmy.composition().getOrDefault(Units.TERRAN_VIKING_FIGHTER, 0) * 1 +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_CARRIER, 0) * 2 +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_COLOSSUS, 0) * 4 +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_TEMPEST, 0) * 2;
        if (targetVikings > 0) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_VIKING_FIGHTER)
                    .alternateForm(Units.TERRAN_VIKING_ASSAULT)
                    .productionAbility(Abilities.TRAIN_VIKING_FIGHTER)
                    .producingUnitType(Units.TERRAN_STARPORT)
                    .needsTechLab(false)
                    .amount(targetVikings)
                    .build()
            );
        }
        int targetLiberators = (int)Math.ceil(
                enemyArmy.composition().getOrDefault(Units.ZERG_ULTRALISK, 0) * 0.5 +
                        enemyArmy.composition().getOrDefault(Units.TERRAN_SIEGE_TANK, 0) * 1.0 +
                        enemyArmy.composition().getOrDefault(Units.TERRAN_SIEGE_TANK_SIEGED, 0) * 0.5 +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_IMMORTAL, 0) * 0.5 +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_COLOSSUS, 0) * 1.0 +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_ARCHON, 0) * 0.75);
        if (targetLiberators > 0) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_LIBERATOR)
                    .alternateForm(Units.TERRAN_LIBERATOR_AG)
                    .productionAbility(Abilities.TRAIN_LIBERATOR)
                    .producingUnitType(Units.TERRAN_STARPORT)
                    .needsTechLab(false)
                    .amount(targetLiberators)
                    .build()
            );
        }
        int targetRavens = agentWithData.mapAwareness().getObservedCreepCoverage().orElse(0f) > 0.5f ? 1 : 0;
        if (targetRavens > 0) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_RAVEN)
                    .productionAbility(Abilities.TRAIN_RAVEN)
                    .producingUnitType(Units.TERRAN_STARPORT)
                    .needsTechLab(false)
                    .amount(targetRavens)
                    .build()
            );
        }
        int targetMedivacs = (int)Math.min(10, Math.ceil(mainArmy.getSize() * 0.5));
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MEDIVAC)
                .productionAbility(Abilities.TRAIN_MEDIVAC)
                .producingUnitType(Units.TERRAN_STARPORT)
                .amount(targetMedivacs)
                .build()
        );
        int targetGhosts =
                enemyArmy.composition().getOrDefault(Units.PROTOSS_COLOSSUS, 0) +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_HIGH_TEMPLAR, 0) +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_ARCHON, 0) +
                        enemyArmy.composition().getOrDefault(Units.ZERG_INFESTOR, 0) +
                        enemyArmy.composition().getOrDefault(Units.ZERG_QUEEN, 0) / 10;
        if (targetGhosts > 0) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_GHOST)
                    .productionAbility(Abilities.TRAIN_GHOST)
                    .producingUnitType(Units.TERRAN_BARRACKS)
                    .needsTechLab(true)
                    .amount(targetGhosts)
                    .build()
            );
        }
        desiredComposition = result;
    }

    @Override
    public List<UnitTypeRequest> getRequestedUnits() {
        return desiredComposition;
    }
}
