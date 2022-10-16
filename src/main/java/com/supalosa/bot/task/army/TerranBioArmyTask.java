package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.analysis.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.engagement.TerranBioThreatCalculator;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A permanent bio army that constantly asks for reinforcements.
 */
public class TerranBioArmyTask extends DefaultArmyTask {

    private int numMedivacs = 0;
    private int basePriority;

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = 0L;

    public TerranBioArmyTask(String armyName, int basePriority) {
        super(armyName, basePriority, new TerranBioThreatCalculator());
        this.basePriority = basePriority;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        super.onStep(taskManager, data, agent);
        long gameLoop = agent.observation().getGameLoop();
        numMedivacs = 0;
        armyUnits.forEach(tag -> {
            UnitInPool unit = agent.observation().getUnit(tag);
            if (unit != null) {
                if (unit.unit().getType() == Units.TERRAN_MEDIVAC) {
                    numMedivacs++;
                }
            }
        });

        if (gameLoop > desiredCompositionUpdatedAt + 22L) {
            desiredCompositionUpdatedAt = gameLoop;
            updateBioArmyComposition();
        }
    }

    private void updateBioArmyComposition() {
        List<UnitTypeRequest> result = new ArrayList<>();
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARINE)
                .productionAbility(Abilities.TRAIN_MARINE)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .amount(1000)
                .build()
        );
        if (armyUnits.size() > 10) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_MARAUDER)
                    .productionAbility(Abilities.TRAIN_MARAUDER)
                    .producingUnitType(Units.TERRAN_BARRACKS)
                    .amount(20)
                    .build()
            );
        }
        if (armyUnits.size() > 10 && numMedivacs < armyUnits.size() * 0.1) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_MEDIVAC)
                    .productionAbility(Abilities.TRAIN_MEDIVAC)
                    .producingUnitType(Units.TERRAN_STARPORT)
                    .amount((int)(Math.min(10, armyUnits.size() * 0.1)))
                    .build()
            );
        }
        desiredComposition = result;
    }

    @Override
    public boolean isComplete() {
        // This army is a permanent one.
        return false;
    }

    @Override
    protected AggressionState attackCommand(ObservationInterface observationInterface,
                                 ActionInterface actionInterface,
                                 Optional<Point2d> centreOfMass,
                                 Optional<Point2d> suggestedAttackMovePosition,
                                 Optional<Point2d> suggestedRetreatMovePosition,
                                 Optional<Army> maybeEnemyArmy) {
        AggressionState parentState = super.attackCommand(observationInterface, actionInterface, centreOfMass,
                suggestedAttackMovePosition, suggestedRetreatMovePosition, maybeEnemyArmy);
        if (parentState == AggressionState.ATTACKING && armyUnits.size() > 0) {
            Optional<Point2d> positionToAttackMove = suggestedAttackMovePosition;
            // Stutter step towards/away from enemy.
            // TODO: the retreat position should actually be pathfinded away from the enemy.
            // Move towards the enemy if we're winning, otherwise kite them.
            Optional<Point2d> movePoint = suggestedRetreatMovePosition;
            Optional<Point2d> attackPoint = positionToAttackMove.isPresent() ? positionToAttackMove : retreatPosition;
            if (movePoint.isPresent()) {
                armyUnits.forEach(tag -> {
                    UnitInPool unit = observationInterface.getUnit(tag);
                    if (unit != null) {
                        // Empty weapon = no attack - so just scan-attack there.
                        if (unit.unit().getWeaponCooldown().isEmpty() ||
                                (unit.unit().getWeaponCooldown().isPresent() && unit.unit().getWeaponCooldown().get() < 0.01f)) {
                            actionInterface.unitCommand(tag, Abilities.ATTACK, attackPoint.get(), false);
                        } else {
                            actionInterface.unitCommand(tag, Abilities.MOVE, movePoint.get(), false);
                        }
                    }
                });
            }
            if (maybeEnemyArmy.isPresent()) {
                calculateStimPack(observationInterface, actionInterface, maybeEnemyArmy, armyUnits);
            }
        }
        return parentState;
    }

    private void calculateStimPack(ObservationInterface observationInterface, ActionInterface actionInterface,
                           Optional<Army> maybeEnemyArmy, Set<Tag> unitsToAttackWith) {
        AtomicInteger stimmedMarines = new AtomicInteger(0);
        AtomicInteger stimmedMarauders = new AtomicInteger(0);
        Set<Tag> marinesWithoutStim = observationInterface.getUnits(unitInPool ->
                unitsToAttackWith.contains(unitInPool.getTag()) &&
                        (unitInPool.unit().getType() == Units.TERRAN_MARINE) &&
                        unitInPool.unit().getPosition().toPoint2d().distance(maybeEnemyArmy.get().position()) < 10f &&
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
                        (unitInPool.unit().getType() == Units.TERRAN_MARAUDER) &&
                        unitInPool.unit().getPosition().toPoint2d().distance(maybeEnemyArmy.get().position()) < 10f &&
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
        return desiredComposition;
    }

    @Override
    public boolean wantsUnit(Unit unit) {
        // This unit will take any bio-army unit.
        // Note, should set its priority lower than other tasks so it doesn't hog them all.
        return unit.getType() == Units.TERRAN_MARINE ||
                unit.getType() == Units.TERRAN_MARAUDER ||
                unit.getType() == Units.TERRAN_MEDIVAC ||
                unit.getType() == Units.TERRAN_RAVEN;
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
        centreOfMass.ifPresent(point2d -> {
            float z = agent.observation().terrainHeight(point2d);
            Point point = Point.of(point2d.getX(), point2d.getY(), z);
            agent.debug().debugSphereOut(point, aggressionState == AggressionState.REGROUPING ? 5f : 10f, Color.YELLOW);
            agent.debug().debugTextOut(this.armyName, point, Color.WHITE, 8);
        });
        targetPosition.ifPresent(point2d -> {
            float z = agent.observation().terrainHeight(point2d);
            Point point = Point.of(point2d.getX(), point2d.getY(), z);
            agent.debug().debugSphereOut(point, 1f, Color.RED);
            agent.debug().debugTextOut(this.armyName, point, Color.WHITE, 8);
        });
        retreatPosition.ifPresent(point2d -> {
            float z = agent.observation().terrainHeight(point2d);
            Point point = Point.of(point2d.getX(), point2d.getY(), z);
            agent.debug().debugSphereOut(point, 1f, Color.YELLOW);
            agent.debug().debugTextOut(this.armyName, point, Color.WHITE, 8);
        });
        if (centreOfMass.isPresent() && regionWaypoints.size() > 0) {
            Point2d startPoint = centreOfMass.get();
            Point lastPoint = Point.of(startPoint.getX(), startPoint.getY(), agent.observation().terrainHeight(startPoint)+1f);
            for (Region waypoint : regionWaypoints) {
                Point2d nextPoint = waypoint.centrePoint();
                Point newPoint = Point.of(nextPoint.getX(), nextPoint.getY(), agent.observation().terrainHeight(nextPoint)+1f);

                agent.debug().debugSphereOut(newPoint, 1f, Color.WHITE);
                agent.debug().debugLineOut(lastPoint, newPoint, Color.WHITE);
                lastPoint = newPoint;
            }
        }
    }

    @Override
    public void onUnitIdle(UnitInPool unitTag) {

    }
}
