package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.engagement.TerranBioThreatCalculator;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.message.*;
import com.supalosa.bot.task.terran.ImmutableScanRequestTaskMessage;
import com.supalosa.bot.utils.UnitFilter;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A bio army with marines, marauders, medivacs and mines.
 */
public class TerranBioArmyTask extends DefaultArmyTask {

    private int basePriority;

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = Long.MIN_VALUE;

    public TerranBioArmyTask(String armyName, String armyKey, int basePriority, Optional<TerranBioArmyTask> parentArmy) {
        super(armyName,
                armyKey,
                basePriority,
                new TerranBioThreatCalculator(),
                new TerranBioArmyTaskBehaviour(),
                parentArmy);
        this.basePriority = basePriority;
    }

    public TerranBioArmyTask(String armyName, int basePriority) {
        this(armyName, armyName, basePriority, Optional.empty());
    }

    @Override
    public DefaultArmyTask createChildArmyImpl() {
        return new TerranBioArmyTask(
                this.getArmyName() + "-child",
                this.getArmyName() + "-child" + UUID.randomUUID(),
                basePriority,
                Optional.of(this));
    }

    @Override
    public void onStepImpl(TaskManager taskManager, AgentWithData agentWithData) {
        super.onStepImpl(taskManager, agentWithData);
        long gameLoop = agentWithData.observation().getGameLoop();

        if (gameLoop > desiredCompositionUpdatedAt + 22L) {
            desiredCompositionUpdatedAt = gameLoop;
            updateBioArmyComposition(agentWithData);
        }
    }

    private void updateBioArmyComposition(AgentData data) {
        List<UnitTypeRequest> result = new ArrayList<>();
        int maxBio = 80;
        int targetMarines = 40;
        int targetMarauders = 40;
        Army enemyArmy = data.enemyAwareness().getOverallEnemyArmy();

        targetMarauders +=
                enemyArmy.composition().getOrDefault(Units.ZERG_ROACH, 0) +
                enemyArmy.composition().getOrDefault(Units.PROTOSS_STALKER, 0) +
                enemyArmy.composition().getOrDefault(Units.TERRAN_SIEGE_TANK, 0) * 3 +
                enemyArmy.composition().getOrDefault(Units.TERRAN_SIEGE_TANK_SIEGED, 0) * 2 +
                enemyArmy.composition().getOrDefault(Units.ZERG_ULTRALISK, 0) * 5;

        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARINE)
                .productionAbility(Abilities.TRAIN_MARINE)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .amount(Math.max(0, maxBio - targetMarauders))
                .build()
        );
        if (getAssignedUnits().size() > 5) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_MARAUDER)
                    .productionAbility(Abilities.TRAIN_MARAUDER)
                    .producingUnitType(Units.TERRAN_BARRACKS)
                    .needsTechLab(true)
                    .amount(Math.max(0, maxBio -  targetMarines))
                    .build()
            );
        }
        if (getAssignedUnits().size() > 10) {
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
                enemyArmy.composition().getOrDefault(Units.PROTOSS_COLOSSUS, 0) * 2 +
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
                        enemyArmy.composition().getOrDefault(Units.TERRAN_SIEGE_TANK_SIEGED, 0) * 1.0 +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_IMMORTAL, 0) * 1.0 +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_COLOSSUS, 0) * 2.0 +
                        enemyArmy.composition().getOrDefault(Units.PROTOSS_ARCHON, 0) * 1.0);
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
        int targetRavens = data.mapAwareness().getObservedCreepCoverage().orElse(0f) > 0.5f ? 1 : 0;
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
        int targetMedivacs = (int)Math.min(10, Math.ceil(getAssignedUnits().size() * 0.5));
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

    private List<TaskPromise> requestScannerSweep(AgentData data, Point2d scanPosition, long scanRequiredBefore) {
        return data.taskManager().dispatchMessage(this,
                ImmutableScanRequestTaskMessage.builder()
                        .point2d(scanPosition)
                        .requiredBefore(scanRequiredBefore)
                        .build());
    }

    private void calculateWidowMines(Optional<Point2d> centreOfMass, Optional<Army> maybeEnemyArmy,
                                     ObservationInterface observationInterface, ActionInterface actionInterface) {
        if (centreOfMass.isPresent() && maybeEnemyArmy.isPresent() && (getAmountOfUnit(Units.TERRAN_WIDOWMINE) > 0 || getAmountOfUnit(Units.TERRAN_WIDOWMINE_BURROWED) > 0)) {
            getAssignedUnits().forEach(tag -> {
                UnitInPool unitInPool = observationInterface.getUnit(tag);
                if (unitInPool.unit().getType() != Units.TERRAN_WIDOWMINE && unitInPool.unit().getType() != Units.TERRAN_WIDOWMINE_BURROWED) {
                    return;
                }
                Point2d position = unitInPool.unit().getPosition().toPoint2d();
                List<UnitInPool> nearEnemies = observationInterface.getUnits(
                        UnitFilter.builder()
                                .alliance(Alliance.ENEMY)
                                .unitTypes(Constants.ARMY_UNIT_TYPES)
                                .inRangeOf(position)
                                .range(15f)
                                .build());
                boolean anyInRange = false;
                for (UnitInPool nearEnemy : nearEnemies) {
                    if (nearEnemy.unit().getPosition().toPoint2d().distance(position) < 10f) {
                        anyInRange = true;
                    }
                }
                if (anyInRange) {
                    actionInterface.unitCommand(tag, Abilities.BURROW_DOWN_WIDOWMINE, false);
                } else if (nearEnemies.size() == 0) {
                    actionInterface.unitCommand(tag, Abilities.BURROW_UP_WIDOWMINE, false);
                }
            });
        }
    }

    private void calculateStimPack(ObservationInterface observationInterface, ActionInterface actionInterface,
                           Optional<Army> maybeEnemyArmy, Set<Tag> unitsToAttackWith) {
        AtomicInteger stimmedMarines = new AtomicInteger(0);
        AtomicInteger stimmedMarauders = new AtomicInteger(0);
        Set<Tag> marinesWithoutStim = observationInterface.getUnits(unitInPool ->
                unitsToAttackWith.contains(unitInPool.getTag()) &&
                        maybeEnemyArmy.flatMap(Army::position).isPresent() &&
                        (unitInPool.unit().getType() == Units.TERRAN_MARINE) &&
                        unitInPool.unit().getPosition().toPoint2d().distance(maybeEnemyArmy.flatMap(Army::position).get()) < 10f &&
                        unitInPool.unit().getHealth().filter(health -> health > 25f).isPresent()
        ).stream().filter(unitInPool -> {
            if (unitInPool.unit().getBuffs().contains(Buffs.STIMPACK)) {
                stimmedMarines.incrementAndGet();
                return false;
            } else {
                return true;
            }
        }).map(unitInPool -> unitInPool.getTag()).collect(Collectors.toSet());

        Set<Tag> maraudersWithoutStim = observationInterface.getUnits(unitInPool ->
                unitsToAttackWith.contains(unitInPool.getTag()) &&
                        maybeEnemyArmy.flatMap(Army::position).isPresent() &&
                        (unitInPool.unit().getType() == Units.TERRAN_MARAUDER) &&
                        unitInPool.unit().getPosition().toPoint2d().distance(maybeEnemyArmy.flatMap(Army::position).get()) < 10f &&
                        unitInPool.unit().getHealth().filter(health -> health > 40f).isPresent()
        ).stream().filter(unitInPool -> {
            if (unitInPool.unit().getBuffs().contains(Buffs.STIMPACK_MARAUDER)) {
                stimmedMarauders.incrementAndGet();
                return false;
            } else {
                return true;
            }
        }).map(unitInPool -> unitInPool.getTag()).collect(Collectors.toSet());
        // Stim 1:1 ratio
        int stimsRequested = Math.max(0, (int) maybeEnemyArmy.get().size() - stimmedMarines.get() - stimmedMarauders.get());
        marinesWithoutStim =
                marinesWithoutStim.stream().limit(stimsRequested).collect(Collectors.toSet());
        if (marinesWithoutStim.size() > 0) {
            actionInterface.unitCommand(marinesWithoutStim, Abilities.EFFECT_STIM_MARINE, false);
            stimsRequested -= marinesWithoutStim.size();
        }
        maraudersWithoutStim = maraudersWithoutStim.stream().limit(stimsRequested).collect(Collectors.toSet());
        if (maraudersWithoutStim.size() > 0) {
            actionInterface.unitCommand(maraudersWithoutStim, Abilities.EFFECT_STIM_MARAUDER, false);
        }
    }

    @Override
    public List<UnitTypeRequest> requestingUnitTypes() {
        if (getParentTask().isEmpty()) {
            // Only the parent task should control the composition.
            return desiredComposition;
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    public int getPriority() {
        return basePriority;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        return false;
    }

    @Override
    public void debug(S2Agent agent) {
        super.debug(agent);
    }

    @Override
    public void onUnitIdle(UnitInPool unitTag) {

    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
    }
}
