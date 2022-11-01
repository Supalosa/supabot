package com.supalosa.bot.task.army.micro;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.Buffs;
import com.github.ocraft.s2client.protocol.data.UnitAttribute;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.github.ocraft.s2client.protocol.unit.UnitOrder;
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

    // Used to filter out duplicate move commands.
    // TODO change this to 'tokens' or something
    private static boolean isAlreadyMovingTo(Point2d position, Optional<UnitOrder> order) {
        return isAlreadyUsingAbilityAt(Abilities.MOVE, position, order);
    }

    private static boolean isAlreadyAttackMovingTo(Point2d position, Optional<UnitOrder> order) {
        return isAlreadyUsingAbilityAt(Abilities.ATTACK, position, order);
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

    public static void handleMarineMarauderMicro(Unit unit, Optional<Point2d> goalPosition, DefaultArmyTaskBehaviourStateHandler.BaseArgs args, Point2dMap<Unit> enemyUnitMap, AtomicLong remainingUnitsToStim) {
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
            Optional<Point2d> position = getNextPosition(goalPosition, args);
            position.ifPresent(attackPosition -> {
                if (!isAlreadyAttackMovingTo(attackPosition, currentOrder)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, attackPosition, false);
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

    public static void handleVikingMicro(Unit unit, Optional<Point2d> goalPosition,
                                         DefaultArmyTaskBehaviourStateHandler.BaseArgs args, Point2dMap<Unit> enemyUnitMap) {
        Optional<UnitOrder> currentOrder = unit.getOrders().stream().findFirst();
        // Move out of range of anti-air units.
        Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(), 9f, enemy -> Constants.ANTI_AIR_UNIT_TYPES.contains(enemy.getType()))
                .map(enemy -> enemy.getPosition().toPoint2d());
        Optional<Unit> bestTarget = enemyUnitMap
                .getHighestScoreInRadius(unit.getPosition().toPoint2d(),12f,
                        enemy -> Constants.ANTIAIR_ATTACKABLE_UNIT_TYPES.contains(enemy.getType()),
                        (enemy, distance) -> distance);
        if (nearestEnemyUnit.isEmpty() && (unit.getWeaponCooldown().isEmpty() || unit.getWeaponCooldown().get() < 0.01f)) {
            // Off weapon cooldown.
            Optional<Point2d> position = getNextPosition(goalPosition, args);
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
        } else {
            // On weapon cooldown.
            // If the nearest enemy is within stutterRadius, walk away, otherwise walk towards it.
            // Bias towards the region we came from.
            Optional<Point2d> retreatPosition = nearestEnemyUnit
                    .map(enemyPosition -> Utils.getBiasedRetreatPosition(
                            unit.getPosition().toPoint2d(),
                            enemyPosition,
                            args.centreOfMass().or(() -> args.previousRegion().map(RegionData::region).map(Region::centrePoint)),
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

    public static void handleGhostMicro(Unit unit, Optional<Point2d> goalPosition,
                                        DefaultArmyTaskBehaviourStateHandler.BaseArgs args, Point2dMap<Unit> enemyUnitMap) {
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
            Optional<Point2d> position = getNextPosition(goalPosition, args);
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
                            args.centreOfMass().or(() -> args.previousRegion().map(RegionData::region).map(Region::centrePoint)),
                            2.5f))
                    .or(() -> goalPosition);
            retreatPosition.ifPresent(retreatPoint2d -> {
                if (!isAlreadyMovingTo(retreatPoint2d, currentOrder)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, retreatPoint2d, false);
                }
            });
        }
    }

    public static void handleMedivacMicro(Unit unit, Optional<Point2d> goalPosition, DefaultArmyTaskBehaviourStateHandler.BaseArgs args,
                                          Point2dMap<Unit> enemyUnitMap) {
        Optional<UnitOrder> currentOrder = unit.getOrders().stream().findFirst();
        // Medivacs move halfway between the centre of mass and the target location
        Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(), 10f)
                .map(enemy -> enemy.getPosition().toPoint2d());
        Optional<Point2d> retreatPosition = nearestEnemyUnit
                .map(enemyPosition -> Utils.getBiasedRetreatPosition(
                        unit.getPosition().toPoint2d(),
                        enemyPosition, args.previousRegion().map(RegionData::region).map(Region::centrePoint),
                        1.5f));
        Optional<Point2d> nextPositionSafe = getNextPositionSafe(goalPosition, args);
        nextPositionSafe.ifPresent(nextPosition -> {
            double distance = nextPosition.distance(unit.getPosition().toPoint2d());
            // if the medivac is far from the next position, move instead of attack-move.
            if (distance < 8f && (unit.getWeaponCooldown().isEmpty() || unit.getWeaponCooldown().get() < 0.1f)) {
                if (!isAlreadyAttackMovingTo(nextPosition, currentOrder)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, nextPosition, false);
                }
            } else {
                final Point2d movePosition = retreatPosition.orElse(nextPosition);
                if (!isAlreadyMovingTo(movePosition, currentOrder)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, movePosition, false);
                }
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
                .getNearestInRadius(unit.getPosition().toPoint2d(), 18f)
                .map(enemy -> enemy.getPosition().toPoint2d());
        if (nearestEnemyUnit.isEmpty()) {
            args.agentWithData().actions().unitCommand(unit, Abilities.BURROW_UP_WIDOWMINE, false);
        }
    }

    public static void handleWidowmineMicro(Unit unit, Optional<Point2d> goalPosition, DefaultArmyTaskBehaviourStateHandler.BaseArgs args,
                                            Point2dMap<Unit> enemyUnitMap) {
        Optional<Point2d> nearestEnemyUnit = enemyUnitMap
                .getNearestInRadius(unit.getPosition().toPoint2d(), 12f)
                .map(enemy -> enemy.getPosition().toPoint2d());
        Optional<Point2d> nextPositionSafe = getNextPositionSafe(goalPosition, args);
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
                                                     DefaultArmyTaskBehaviourStateHandler.BaseArgs args) {
        Optional<Point2d> position;
        if (!args.currentRegion().equals(args.targetRegion())) {
            position = args.nextRegion().map(region -> region.region().centrePoint());
            if (position.isEmpty()) {
                //position = args.currentRegion().map(RegionData::region).map(Region::centrePoint);
                position = args.centreOfMass();
            }
        } else {
            position = goalPosition;
        }
        return position;
    }

    /**
     * Return an appropriate next position, given the current/next region. Tries to stay near the centre of mass.
     */
    private static Optional<Point2d> getNextPositionSafe(Optional<Point2d> goalPosition,
                                                     DefaultArmyTaskBehaviourStateHandler.BaseArgs args) {
        // Disabled as what we had didn't work very well.
        return getNextPosition(goalPosition, args);
        /*
        Optional<Point2d> centreOfMass = args.centreOfMass();
        Optional<Point2d> position;
        if (!args.currentRegion().equals(args.targetRegion())) {
            position = args.nextRegion().map(region -> region.region().centrePoint());
            if (position.isEmpty()) {
                position = args.currentRegion().map(RegionData::region).map(Region::centrePoint);
            }
        } else {
            position = goalPosition;
        }
        // In order: Return the point halfway between the centre of mass and the goal point,
        // or the centre of mass,
        // or the goal point.
        Optional<Point2d> finalPosition = position;
        Optional<Point2d> result = centreOfMass.flatMap(com -> finalPosition.map(com::add).map(pos -> pos.div(2f))).or(() -> finalPosition);
        return result;*/
    }
}
