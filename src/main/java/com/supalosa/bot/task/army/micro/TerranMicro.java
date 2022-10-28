package com.supalosa.bot.task.army.micro;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Buffs;
import com.github.ocraft.s2client.protocol.data.UnitAttribute;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.Constants;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.task.army.DefaultArmyTaskBehaviourStateHandler;
import com.supalosa.bot.task.army.FightPerformance;
import com.supalosa.bot.utils.Point2dMap;
import com.supalosa.bot.utils.Utils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class TerranMicro {

    public static void handleMarineMarauderMicro(Unit unit, Optional<Point2d> goalPosition, DefaultArmyTaskBehaviourStateHandler.BaseArgs args, Point2dMap<Unit> enemyUnitMap, AtomicLong remainingUnitsToStim) {
        // Stutter-step micro. The more we're winning, the smaller the stutter radius.
        float stutterRadius = 10f;
        if (args.fightPerformance() == FightPerformance.STABLE) {
            stutterRadius = 7.5f;
        } else if (args.fightPerformance() == FightPerformance.WINNING) {
            stutterRadius = 1.5f;
        }
        if (unit.getWeaponCooldown().isEmpty() || unit.getWeaponCooldown().get() < 0.01f) {
            // Off weapon cooldown.
            goalPosition.ifPresent(position -> {
                if (args.currentRegion().equals(args.targetRegion())) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, position, false);
                } else if (args.nextRegion().isPresent()) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, args.nextRegion().get().region().centrePoint(), false);
                } else {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, position, false);
                }
            });
        } else {
            // On weapon cooldown.
            Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                    .getNearestInRadius(unit.getPosition().toPoint2d(), 10f)
                    .map(enemy -> enemy.getPosition().toPoint2d());
            float finalStutterRadius = stutterRadius;
            // If the nearest enemy is within stutterRadius, walk away, otherwise walk towards it.
            // Bias towards the region we came from.
            Optional<Point2d> retreatPosition = nearestEnemyUnit
                    .filter(enemyPosition -> enemyPosition.distance(unit.getPosition().toPoint2d()) < finalStutterRadius)
                    .map(enemyPosition -> Utils.getBiasedRetreatPosition(
                            unit.getPosition().toPoint2d(),
                            enemyPosition,
                            args.centreOfMass().or(() -> args.previousRegion().map(RegionData::region).map(Region::centrePoint)),
                            1.5f))
                    .or(() -> goalPosition);
            retreatPosition.ifPresent(retreatPoint2d -> {
                args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, retreatPoint2d, false);
            });
        }
        // Marine and marauder stimpack usage.
        if (!unit.getBuffs().contains(Buffs.STIMPACK) &&
                !unit.getBuffs().contains(Buffs.STIMPACK_MARAUDER) &&
                remainingUnitsToStim.get() > 0) {
            if (args.agentWithData().gameData().unitHasAbility(unit.getTag(), Abilities.EFFECT_STIM)) {
                remainingUnitsToStim.decrementAndGet();
                args.agentWithData().actions().unitCommand(unit, Abilities.EFFECT_STIM, false);
            }
        }
    }

    public static void handleVikingMicro(Unit unit, Optional<Point2d> goalPosition,
                                         DefaultArmyTaskBehaviourStateHandler.BaseArgs args, Point2dMap<Unit> enemyUnitMap) {
        // Stutter-step micro. The more we're winning, the smaller the stutter radius.
        float stutterRadius = 9f;
        if (unit.getWeaponCooldown().isEmpty() || unit.getWeaponCooldown().get() < 0.01f) {
            // Off weapon cooldown.
            Optional<Unit> bestTarget = enemyUnitMap
                    .getHighestScoreInRadius(unit.getPosition().toPoint2d(),12f,
                            enemy -> Constants.ANTIAIR_ATTACKABLE_UNIT_TYPES.contains(enemy.getType()),
                            (enemy, distance) -> distance);
            bestTarget.ifPresentOrElse(
                    target -> args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, target, false),
                    () -> goalPosition.ifPresent(position -> {
                        if (args.currentRegion().equals(args.targetRegion())) {
                            args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, position, false);
                        } else if (args.nextRegion().isPresent()) {
                            args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, args.nextRegion().get().region().centrePoint(), false);
                        } else {
                            args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, position, false);
                        }
                    }));
        } else {
            // On weapon cooldown.
            Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                    .getNearestInRadius(unit.getPosition().toPoint2d(), 10f, enemy -> Constants.ANTI_AIR_UNIT_TYPES.contains(enemy.getType()))
                    .map(enemy -> enemy.getPosition().toPoint2d());
            float finalStutterRadius = stutterRadius;
            // If the nearest enemy is within stutterRadius, walk away, otherwise walk towards it.
            // Bias towards the region we came from.
            Optional<Point2d> retreatPosition = nearestEnemyUnit
                    .filter(enemyPosition -> enemyPosition.distance(unit.getPosition().toPoint2d()) < finalStutterRadius)
                    .map(enemyPosition -> Utils.getBiasedRetreatPosition(
                            unit.getPosition().toPoint2d(),
                            enemyPosition,
                            args.centreOfMass().or(() -> args.previousRegion().map(RegionData::region).map(Region::centrePoint)),
                            1.5f))
                    .or(() -> goalPosition);
            retreatPosition.ifPresent(retreatPoint2d -> {
                args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, retreatPoint2d, false);
            });
        }
    }

    public static void handleGhostMicro(Unit unit, Optional<Point2d> goalPosition,
                                        DefaultArmyTaskBehaviourStateHandler.BaseArgs args, Point2dMap<Unit> enemyUnitMap) {
        if (unit.getEnergy().orElse(0f) > 50f) {
            // Ghost handle targeting.
            Optional<Unit> nearestEnergyTarget = enemyUnitMap
                    .getNearestInRadius(unit.getPosition().toPoint2d(), 11.5f,
                            enemy -> enemy.getEnergy().isPresent() && enemy.getEnergyMax().isPresent() && enemy.getEnergy().get() > enemy.getEnergyMax().get() / 2);
            if (nearestEnergyTarget.isPresent()) {
                args.agentWithData().actions().unitCommand(unit, Abilities.EFFECT_EMP, nearestEnergyTarget.get().getPosition().toPoint2d(), false);
            } else {
                // Find nearest biological target with 145 hp or more.
                Optional<Unit> nearestBiologicalTarget = enemyUnitMap
                        .getNearestInRadius(unit.getPosition().toPoint2d(), 11.5f,
                                enemy -> enemy.getHealth().orElse(0f) >= 145f &&
                                        args.agentWithData().gameData()
                                                .getAttributes(enemy.getType())
                                                .contains(UnitAttribute.BIOLOGICAL));
                nearestBiologicalTarget.ifPresent(target ->
                        args.agentWithData().actions().unitCommand(unit, Abilities.EFFECT_GHOST_SNIPE, target, false)
                );
            }
        }
        if (unit.getWeaponCooldown().isEmpty() || unit.getWeaponCooldown().get() < 0.01f) {
            // Ghost Off weapon cooldown.
            goalPosition.ifPresent(position -> {
                if (args.currentRegion().equals(args.targetRegion())) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, position, false);
                } else if (args.nextRegion().isPresent()) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, args.nextRegion().get().region().centrePoint(), false);
                } else {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, position, false);
                }
            });
            if (!goalPosition.isPresent()) {

            }
        } else {
            // Ghost on weapon cooldown.
            Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                    .getNearestInRadius(unit.getPosition().toPoint2d(), 15f)
                    .map(enemy -> enemy.getPosition().toPoint2d())
                    .or(() -> goalPosition);
            Optional<Point2d> retreatPosition = nearestEnemyUnit
                    .map(enemyPosition -> Utils.getBiasedRetreatPosition(
                            unit.getPosition().toPoint2d(),
                            enemyPosition,
                            args.centreOfMass().or(() -> args.previousRegion().map(RegionData::region).map(Region::centrePoint)),
                            2.5f))
                    .or(() -> goalPosition);
            retreatPosition.ifPresent(retreatPoint2d -> {
                args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, retreatPoint2d, false);
            });
        }
    }

    public static void handleMedivacMicro(Unit unit, DefaultArmyTaskBehaviourStateHandler.BaseArgs args,
                                          Point2dMap<Unit> enemyUnitMap) {
        // Medivacs move to the centre of mass or away from the enemy unit if too close.
        Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(), 6f)
                .map(enemy -> enemy.getPosition().toPoint2d());
        Optional<Point2d> retreatPosition = nearestEnemyUnit
                .map(enemyPosition -> Utils.getBiasedRetreatPosition(
                        unit.getPosition().toPoint2d(),
                        enemyPosition, args.previousRegion().map(RegionData::region).map(Region::centrePoint),
                        1.5f));
        args.centreOfMass().ifPresent(centreOfMass -> {
            if (unit.getWeaponCooldown().isEmpty() || unit.getWeaponCooldown().get() < 0.1f) {
                args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, centreOfMass, false);
            } else {
                args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, retreatPosition.orElse(centreOfMass), false);
            }
        });
        double distanceToCentreOfMass = args.centreOfMass()
                .map(centreOfMass -> centreOfMass.distance(unit.getPosition().toPoint2d()))
                .orElse(0.0);
        // Boost if far away.
        if (args.agentWithData().gameData().unitHasAbility(unit.getTag(), Abilities.EFFECT_MEDIVAC_IGNITE_AFTERBURNERS) &&
                distanceToCentreOfMass > 20f) {
            args.agentWithData().actions().unitCommand(unit, Abilities.EFFECT_MEDIVAC_IGNITE_AFTERBURNERS, false);
        }

    }

    public static void handleWidowmineBurrowedMicro(Unit unit, DefaultArmyTaskBehaviourStateHandler.BaseArgs args,
                                                    Point2dMap<Unit> enemyUnitMap) {
        Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(), 15f)
                .map(enemy -> enemy.getPosition().toPoint2d());
        if (nearestEnemyUnit.isEmpty()) {
            args.agentWithData().actions().unitCommand(unit, Abilities.BURROW_UP_WIDOWMINE, false);
        }
    }

    public static void handleWidowmineMicro(Unit unit, DefaultArmyTaskBehaviourStateHandler.BaseArgs args,
                                            Point2dMap<Unit> enemyUnitMap) {
        Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(), 10f)
                .map(enemy -> enemy.getPosition().toPoint2d());
        if (nearestEnemyUnit.isPresent() && nearestEnemyUnit.get().distance(unit.getPosition().toPoint2d()) < 10f) {
             args.agentWithData().actions().unitCommand(unit, Abilities.BURROW_DOWN_WIDOWMINE, false);
        } else if (args.centreOfMass().isPresent()) {
            args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, args.centreOfMass().get(), false);
        }
    }
}
