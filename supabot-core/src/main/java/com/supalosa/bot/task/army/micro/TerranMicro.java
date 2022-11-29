package com.supalosa.bot.task.army.micro;

import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.github.ocraft.s2client.protocol.unit.UnitOrder;
import com.supalosa.bot.Constants;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.task.army.BaseArgs;
import com.supalosa.bot.task.army.FightPerformance;
import com.supalosa.bot.utils.Point2dMap;
import com.supalosa.bot.utils.Utils;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class TerranMicro {

    // Used to filter out duplicate move commands.
    // TODO rather than having units submit actions, perhaps we should implement 'commands' (attack position,
    //  attack-move unit, move position, use ability on unit, use ability on position). This will allow grouping of
    // commands.
    private static boolean isAlreadyMovingTo(Point2d position, Optional<UnitOrder> order) {
        return isAlreadyUsingAbilityAt(Abilities.MOVE, position, order);
    }

    private static boolean isAlreadyAttackMovingTo(Point2d position, Optional<UnitOrder> order) {
        return isAlreadyUsingAbilityAt(Abilities.ATTACK, position, order);
    }

    private static boolean isAlreadyAttacking(Tag tag, Optional<UnitOrder> order) {
        return order.filter(unitOrder -> unitOrder.getAbility().equals(Abilities.ATTACK))
                .flatMap(UnitOrder::getTargetedUnitTag)
                .filter(orderTag -> orderTag.equals(tag))
                .isPresent();
    }

    private static boolean isAlreadyUsingAbilityAt(Ability ability, Point2d position, Optional<UnitOrder> order) {
        // Do not allow the same ability to be used within 0.5 distance of the existing order.
        return order.filter(unitOrder -> unitOrder.getAbility().equals(ability))
                    .flatMap(UnitOrder::getTargetedWorldSpacePosition)
                    .map(Point::toPoint2d)
                    .map(position::distance)
                    .filter(distance -> distance < 0.5)
                    .isPresent();
    }


    public static void handleDefaultMicro(Unit unit,

                                          Optional<Point2d> goalPosition,
                                          Optional<RegionData> goalRegion,
                                          Optional<RegionData> nextRegion,
                                          BaseArgs args,
                                          Point2dMap<Unit> enemyUnitMap) {
        Optional<UnitOrder> currentOrder = unit.getOrders().stream().findFirst();
        Optional<Point2d> position = getNextPosition(goalPosition, goalRegion, nextRegion, unit, args);
        position.ifPresent(attackPosition -> {
            if (!isAlreadyAttackMovingTo(attackPosition, currentOrder)) {
                args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, attackPosition, false);
            }
        });
    }

    private static final Set<UnitType> MAJOR_AOE_THREATS = Set.of(
            Units.TERRAN_SIEGE_TANK_SIEGED,
            Units.PROTOSS_COLOSSUS,
            Units.PROTOSS_DISRUPTOR_PHASED,
            Units.PROTOSS_ARCHON
            );

    public static void handleMarineMarauderMicro(Unit unit,
                                                 Optional<Point2d> goalPosition,
                                                 Optional<RegionData> goalRegion,
                                                 Optional<RegionData> nextRegion,
                                                 BaseArgs args,
                                                 Point2dMap<Unit> enemyUnitMap,
                                                 AtomicLong remainingUnitsToStim) {
        Optional<UnitOrder> currentOrder = unit.getOrders().stream().findFirst();
        // Stutter-step micro. The more we're winning, the smaller the stutter radius.
        float stutterRadius = 10f;
        if (args.fightPerformance() == FightPerformance.STABLE) {
            stutterRadius = 7.5f;
        } else if (args.fightPerformance() == FightPerformance.WINNING) {
            stutterRadius = 1.5f;
        }

        if (unit.getWeaponCooldown().isEmpty() || unit.getWeaponCooldown().get() < 0.01f) {
            // Off weapon cooldown.
            Optional<Point2d> position = getNextPosition(goalPosition, goalRegion, nextRegion, unit, args);
            final boolean isMarine = unit.getType().equals(Units.TERRAN_MARINE);
            final boolean canShootAir = isMarine;
            final float range = isMarine ? 5f : 6f;
            Optional<Unit> bestTarget = enemyUnitMap
                    .getHighestScoreInRadius(unit.getPosition().toPoint2d(),range,
                            enemy -> canShootAir || enemy.getFlying().orElse(false),
                            (enemy, distance) -> distance * valueWeight(enemy));
            bestTarget.ifPresentOrElse(bestTargetUnit -> {
                if (!isAlreadyAttacking(bestTargetUnit.getTag(), currentOrder)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, bestTargetUnit, false);
                }
            }, () -> {
                // No specific target, attack move there.
                position.ifPresent(attackPosition -> {
                    if (!isAlreadyAttackMovingTo(attackPosition, currentOrder)) {
                        args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, attackPosition, false);
                    }
                });
            });
        } else {
            // On weapon cooldown.
            Optional<Unit> aoeThreat = enemyUnitMap
                    .getNearestInRadius(unit.getPosition().toPoint2d(), 15f, enemy -> MAJOR_AOE_THREATS.contains(enemy.getType()));
            // Get the maximum range of the thing that could hit this unit.
            Optional<Unit> longestRangeUnit = enemyUnitMap
                    .getHighestScoreInRadius(unit.getPosition().toPoint2d(),
                            10f,
                            v -> true,
                            (enemy, distance) ->
                                    Math.max(distance, args.agentWithData().gameData().getMaxUnitRange(enemy.getType()).orElse(2.5f)));
            Optional<Point2d> nearestEnemyUnitPosition = aoeThreat.or(() -> longestRangeUnit)
                    .map(enemy -> enemy.getPosition().toPoint2d());
            // Clamp the stutter radius by the enemy's range.
            Float nearestEnemyUnitRange = longestRangeUnit.flatMap(enemy -> args.agentWithData().gameData().getMaxUnitRange(enemy.getType())).orElse(1.5f);
            float finalStutterRadius = Math.min(Math.max(1.0f, nearestEnemyUnitRange), stutterRadius);
            // If the nearest enemy is within stutterRadius, walk away, otherwise walk towards it.
            // Bias towards the region we came from.
            Optional<Point2d> retreatPosition = nearestEnemyUnitPosition
                    .filter(enemyPosition -> enemyPosition.distance(unit.getPosition().toPoint2d()) < finalStutterRadius)
                    .map(enemyPosition -> Utils.getBiasedRetreatPosition(
                            unit.getPosition().toPoint2d(),
                            enemyPosition,
                            args.centreOfMass().or(() -> args.nextRetreatRegion().map(RegionData::region).map(Region::centrePoint)),
                            1.5f))
                    .or(() -> goalPosition);
            retreatPosition.ifPresent(retreatPoint2d -> {
                if (!isAlreadyMovingTo(retreatPoint2d, currentOrder)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, retreatPoint2d, false);
                }
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


    /**
     * Scoring function for enemy value.
     */
    private static Double valueWeight(Unit enemy) {
        double weight = 1f / Math.max(1f, enemy.getHealth().orElse(1f));
        if (MAJOR_AOE_THREATS.contains(enemy.getType())) {
            weight *= 2.0;
            if (enemy.getHealth().orElse(0f) < enemy.getHealthMax().orElse(100f) * 0.2) {
                // 20%, dive on it.
                weight *= 2.0;
            }
        }
        return weight;
    }

    public static void handleVikingFighterMicro(Unit unit,
                                                Optional<Point2d> goalPosition,
                                                Optional<RegionData> goalRegion,
                                                Optional<RegionData> nextRegion,
                                                BaseArgs args,
                                                Point2dMap<Unit> enemyUnitMap) {
        Optional<UnitOrder> currentOrder = unit.getOrders().stream().findFirst();
        // Move out of range of anti-air units.
        // We are willing to get closer if we are winning the fight.
        float avoidanceRange = 9f;
        if (args.predictedFightPerformance() == FightPerformance.WINNING) {
            avoidanceRange = 5f;
        } else if (args.predictedFightPerformance() == FightPerformance.STABLE) {
            avoidanceRange = 7.5f;
        }
        Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(), avoidanceRange,
                        enemy -> Constants.ANTI_AIR_UNIT_TYPES.contains(enemy.getType()))
                .map(enemy -> enemy.getPosition().toPoint2d());
        Optional<Unit> bestTarget = enemyUnitMap
                .getHighestScoreInRadius(unit.getPosition().toPoint2d(),16f,
                        enemy -> Constants.ANTIAIR_ATTACKABLE_UNIT_TYPES.contains(enemy.getType()),
                        (enemy, distance) -> distance);
        if (nearestEnemyUnit.isEmpty() && (unit.getWeaponCooldown().isEmpty() || unit.getWeaponCooldown().get() < 0.01f)) {
            // Off weapon cooldown.
            Optional<Point2d> position = getNextPosition(goalPosition, goalRegion, nextRegion, unit, args);
            bestTarget.ifPresentOrElse(
                    target -> args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, target, false),
                    () -> position.ifPresent(attackMovePosition -> {
                        if (!args.currentRegion().equals(args.targetRegion()) && args.nextRegion().isPresent()) {
                            attackMovePosition = args.nextRegion().get().region().centrePoint();
                        }
                        if (!isAlreadyAttackMovingTo(attackMovePosition, currentOrder)) {
                            args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, attackMovePosition, false);
                        }
                    }));
            if (nearestEnemyUnit.isPresent() && args.enemyVirtualArmy().getCount(Constants.ANTIAIR_ATTACKABLE_UNIT_TYPES) == 0) {
                // No AA targets, morph to land unit.
                args.agentWithData().actions().unitCommand(unit, Abilities.MORPH_VIKING_ASSAULT_MODE, false);
            }
        } else {
            // On weapon cooldown.
            // If the nearest enemy is within stutterRadius, walk away, otherwise walk towards it.
            // Bias towards the region we came from.
            Optional<Point2d> retreatPosition = nearestEnemyUnit
                    .map(enemyPosition -> Utils.getBiasedRetreatPosition(
                            unit.getPosition().toPoint2d(),
                            enemyPosition,
                            args.centreOfMass().or(() -> args.nextRetreatRegion().map(RegionData::region).map(Region::centrePoint)),
                            1.5f))
                    .or(() -> goalPosition);
            retreatPosition.ifPresentOrElse(retreatPoint2d -> {
                if (!isAlreadyMovingTo(retreatPoint2d, currentOrder)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, retreatPoint2d, false);
                }
            }, () -> {
                if (bestTarget.isPresent() && !isAlreadyAttackMovingTo(bestTarget.get().getPosition().toPoint2d(), currentOrder)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, bestTarget.get().getPosition().toPoint2d(), false);
                }
            });
        }
    }

    public static void handleVikingAssaultMicro(Unit unit,
                                                Optional<Point2d> goalPosition,
                                                Optional<RegionData> goalRegion,
                                                Optional<RegionData> nextRegion,
                                                BaseArgs args, Point2dMap<Unit> enemyUnitMap) {
        if (args.enemyVirtualArmy().getCount(Constants.ANTIAIR_ATTACKABLE_UNIT_TYPES) > 0) {
            // AA target present, morph to fighter mode.
            args.agentWithData().actions().unitCommand(unit, Abilities.MORPH_VIKING_FIGHTER_MODE, false);
        } else {
            handleDefaultMicro(unit, goalPosition, goalRegion, nextRegion, args, enemyUnitMap);
        }
    }

    public static void handleLiberatorMicro(Unit unit,
                                            Optional<Point2d> goalPosition,
                                            Optional<RegionData> goalRegion,
                                            Optional<RegionData> nextRegion,
                                            BaseArgs args, Point2dMap<Unit> enemyUnitMap) {
        Optional<UnitOrder> currentOrder = unit.getOrders().stream().findFirst();
        // Move out of range of anti-air units.
        // We are willing to get closer if we are winning the fight.
        float avoidanceRange = 9f;
        if (args.predictedFightPerformance() == FightPerformance.WINNING) {
            avoidanceRange = 5f;
        } else if (args.predictedFightPerformance() == FightPerformance.STABLE) {
            avoidanceRange = 7.5f;
        }
        Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(), avoidanceRange,
                        enemy -> Constants.ANTI_AIR_UNIT_TYPES.contains(enemy.getType()))
                .map(enemy -> enemy.getPosition().toPoint2d());
        boolean hasLibRange = args.agentWithData().observation().getUpgrades().contains(Upgrades.LIBERATOR_AG_RANGE_UPGRADE);
        float liberatorRange = hasLibRange ? 8f : 5f;
        Optional<Unit> closestGroundTarget = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(), liberatorRange + 4f, enemy -> enemy.getFlying().orElse(false) == false);
        if (closestGroundTarget.isPresent()) {
            // Morph liberator to AG mode.
            Optional<Point2d> position = getNextPosition(goalPosition, goalRegion, nextRegion, unit, args);
            closestGroundTarget.ifPresentOrElse(
                    target -> {
                        Point2d liberatorPoint = Utils.getProjectedPosition(unit.getPosition().toPoint2d(),
                                target.getPosition().toPoint2d(), liberatorRange);
                        args.agentWithData().actions().unitCommand(unit, Abilities.MORPH_LIBERATOR_AG_MODE,
                                liberatorPoint, false);
                    },
                    () -> position.ifPresent(attackMovePosition -> {
                        if (!args.currentRegion().equals(args.targetRegion()) && args.nextRegion().isPresent()) {
                            attackMovePosition = args.nextRegion().get().region().centrePoint();
                        }
                        if (!isAlreadyAttackMovingTo(attackMovePosition, currentOrder)) {
                            args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, attackMovePosition, false);
                        }
                    }));
        } else {
            Optional<Point2d> position = getNextPosition(goalPosition, goalRegion, nextRegion, unit, args);
            if (position.isPresent()) {
                if (!isAlreadyAttackMovingTo(position.get(), currentOrder)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, position.get(), false);
                }
            }
        }
    }

    public static void handleLiberatorAgMicro(Unit unit,
                                              BaseArgs args, Point2dMap<Unit> enemyUnitMap) {
        // TODO: figure out where the liberator is shooting and check for targets in that circle.
        float liberatorRange = 10f; // 5 cast + 5 radius
        if (args.agentWithData().observation().getUpgrades().contains(Upgrades.LIBERATOR_AG_RANGE_UPGRADE)) {
            liberatorRange += 3f;
        }
        Optional<Unit> closestGroundTarget = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(),
                        liberatorRange + 5f,
                        enemy -> enemy.getFlying().orElse(false) == false);
        if (closestGroundTarget.isEmpty()) {
            // Morph liberator back to AA mode.
            args.agentWithData().actions().unitCommand(unit, Abilities.MORPH_LIBERATOR_AA_MODE, false);
        }
    }

    public static void handleGhostMicro(Unit unit,
                                        Optional<Point2d> goalPosition,
                                        Optional<RegionData> goalRegion,
                                        Optional<RegionData> nextRegion,
                                        BaseArgs args, Point2dMap<Unit> enemyUnitMap) {
        Optional<UnitOrder> currentOrder = unit.getOrders().stream().findFirst();
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
            Optional<Point2d> position = getNextPosition(goalPosition, goalRegion, nextRegion, unit, args);
            position.ifPresent(attackPosition -> {
                if (!isAlreadyAttackMovingTo(attackPosition, currentOrder)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, attackPosition, false);
                }
            });
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
                            args.centreOfMass().or(() -> args.nextRetreatRegion().map(RegionData::region).map(Region::centrePoint)),
                            2.5f))
                    .or(() -> goalPosition);
            retreatPosition.ifPresent(retreatPoint2d -> {
                if (!isAlreadyMovingTo(retreatPoint2d, currentOrder)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, retreatPoint2d, false);
                }
            });
        }
    }

    public static void handleMedivacMicro(Unit unit,
                                          Optional<Point2d> goalPosition,
                                          Optional<RegionData> goalRegion,
                                          Optional<RegionData> nextRegion,
                                          BaseArgs args,
                                          Point2dMap<Unit> enemyUnitMap) {
        Optional<UnitOrder> currentOrder = unit.getOrders().stream().findFirst();

        // Medivacs boost to avoid units in range.
        Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(), 5f)
                .map(enemy -> enemy.getPosition().toPoint2d());
        Optional<Point2d> retreatPosition = nearestEnemyUnit
                .map(enemyPosition -> Utils.getBiasedRetreatPosition(
                        unit.getPosition().toPoint2d(),
                        enemyPosition, args.nextRetreatRegion().map(RegionData::region).map(Region::centrePoint),
                        1.5f));
        Optional<Point2d> nextPositionSafe = getNextPositionSafe(goalPosition, goalRegion, nextRegion, unit, args);

        retreatPosition.ifPresentOrElse(position -> {
            // Retreat and boost away.
            if (!isAlreadyMovingTo(position, currentOrder)) {
                args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, position, false);
            }
            if (args.agentWithData().gameData().unitHasAbility(unit.getTag(), Abilities.EFFECT_MEDIVAC_IGNITE_AFTERBURNERS)) {
                args.agentWithData().actions().unitCommand(unit, Abilities.EFFECT_MEDIVAC_IGNITE_AFTERBURNERS, false);
            }
        }, () -> {
            nextPositionSafe.ifPresent(nextPosition -> {
                if (!isAlreadyAttackMovingTo(nextPosition, currentOrder)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, nextPosition, false);
                }
            });
        });
        double distanceToCentreOfMass = args.centreOfMass()
                .map(centreOfMass -> centreOfMass.distance(unit.getPosition().toPoint2d()))
                .orElse(0.0);
        // Boost if far away.
        if (args.agentWithData().gameData().unitHasAbility(unit.getTag(), Abilities.EFFECT_MEDIVAC_IGNITE_AFTERBURNERS) &&
                distanceToCentreOfMass > 5f) {
            args.agentWithData().actions().unitCommand(unit, Abilities.EFFECT_MEDIVAC_IGNITE_AFTERBURNERS, false);
        }

    }

    public static void handleWidowmineBurrowedMicro(Unit unit, BaseArgs args,
                                                    Point2dMap<Unit> enemyUnitMap) {
        Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(), 18f)
                .map(enemy -> enemy.getPosition().toPoint2d());
        if (nearestEnemyUnit.isEmpty()) {
            args.agentWithData().actions().unitCommand(unit, Abilities.BURROW_UP_WIDOWMINE, false);
        }
    }

    public static void handleWidowmineMicro(Unit unit,
                                            Optional<Point2d> goalPosition,
                                            Optional<RegionData> goalRegion,
                                            Optional<RegionData> nextRegion,
                                            BaseArgs args,
                                            Point2dMap<Unit> enemyUnitMap) {
        Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(), 12f)
                .map(enemy -> enemy.getPosition().toPoint2d());
        Optional<Point2d> nextPositionSafe = getNextPositionSafe(goalPosition, goalRegion, nextRegion, unit, args);
        if (nearestEnemyUnit.isPresent()) {
             args.agentWithData().actions().unitCommand(unit, Abilities.BURROW_DOWN_WIDOWMINE, false);
        } else if (nextPositionSafe.isPresent()) {
            Optional<UnitOrder> currentOrder = unit.getOrders().stream().findFirst();
            if (!isAlreadyMovingTo(nextPositionSafe.get(), currentOrder)) {
                args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, nextPositionSafe.get(), false);
            }
        }
    }

    /**
     * Return an appropriate next position, given the current/next region.
     */
    private static Optional<Point2d> getNextPosition(Optional<Point2d> goalPosition,
                                                     Optional<RegionData> goalRegion,
                                                     Optional<RegionData> nextRegion,
                                                     Unit unit,
                                                     BaseArgs args) {
        // If close enough to goal position, just return the target position.
        if (goalPosition.isPresent() && unit.getPosition().toPoint2d().distance(goalPosition.get()) < 8f) {
            return goalPosition;
        }
        if (!args.currentRegion().equals(goalRegion)) {
            if (nextRegion.isEmpty()) {
                //position = args.currentRegion().map(RegionData::region).map(Region::centrePoint);
                // Next region is missing. Just move to the centre of the region.
                return args.currentRegion().map(RegionData::region).map(Region::centrePoint)
                        .or(() -> args.centreOfMass());
            } else {
                return nextRegion.map(region -> region.region().centrePoint());
            }
        }
        // Already in the target region, go to the goal position.
        return goalPosition;
    }

    /**
     * Return an appropriate next position, given the current/next region. Tries to stay near the centre of mass.
     */
    private static Optional<Point2d> getNextPositionSafe(Optional<Point2d> goalPosition,
                                                         Optional<RegionData> goalRegion,
                                                         Optional<RegionData> nextRegion,
                                                         Unit unit,
                                                         BaseArgs args) {
        // Disabled as what we had didn't work very well.
        return getNextPosition(goalPosition, goalRegion, nextRegion, unit, args);
    }
}
