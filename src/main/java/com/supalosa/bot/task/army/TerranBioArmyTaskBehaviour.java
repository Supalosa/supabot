package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.task.terran.ImmutableScanRequestTaskMessage;
import com.supalosa.bot.utils.Point2dMap;
import com.supalosa.bot.utils.Utils;
import org.immutables.value.Value;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The default behaviour for a Terran Bio Army.
 */
public class TerranBioArmyTaskBehaviour extends BaseDefaultArmyTaskBehaviour<
        TerranBioArmyTaskBehaviour.AttackContext,
        TerranBioArmyTaskBehaviour.DisengagingContext,
        TerranBioArmyTaskBehaviour.RegroupingContext,
        TerranBioArmyTaskBehaviour.IdleContext> {

    @Value.Immutable
    interface AttackContext {
        long maxUnitsToStim();
        long currentUnitsStimmed();
        AtomicLong remainingUnitsToStim();
        Point2dMap<Unit> enemyUnitMap();
    }

    @Value.Immutable
    interface DisengagingContext {
        Point2dMap<Unit> enemyUnitMap();
    }

    @Value.Immutable
    interface RegroupingContext {

    }

    @Value.Immutable
    interface IdleContext {

    }

    public TerranBioArmyTaskBehaviour(DefaultArmyTaskBehaviourStateHandler attackHandler,
                                      DefaultArmyTaskBehaviourStateHandler disengagingHandler,
                                      DefaultArmyTaskBehaviourStateHandler regroupingHandler,
                                      DefaultArmyTaskBehaviourStateHandler idleHandler) {
        super(attackHandler, disengagingHandler, regroupingHandler, idleHandler);
    }

    public TerranBioArmyTaskBehaviour() {
        super(new AttackHandler(), new DisengagingHandler(), new RegroupingHandler(), new IdleHandler());
    }

    private static class AttackHandler implements DefaultArmyTaskBehaviourStateHandler<AttackContext> {
        @Override
        public void onEnterState(BaseArgs args) {

        }

        @Override
        public AttackContext onArmyStep(BaseArgs args) {
            long currentsUnitsStimmed = args.unitsInArmy().stream()
                    .filter(unit -> unit.getBuffs().contains(Buffs.STIMPACK) || unit.getBuffs().contains(Buffs.STIMPACK_MARAUDER))
                    .count();
            long maxUnitsToStim = args.enemyArmies().stream().reduce(0L, (val, army) -> val + (long)army.threat(), (v1, v2) -> v1 + v2);
            Point2dMap<Unit> enemyUnitMap = constructEnemyUnitMap(args);

            // Request scan if no enemy army units nearby.
            args.centreOfMass().ifPresent(centreOfMass -> {
                long scanRequiredBefore = args.agentWithData().observation().getGameLoop() + 22L * 5;
                if (enemyUnitMap.getNearestInRadius(centreOfMass, 20f).isEmpty()) {
                    Optional<RegionData> maybeCurrentRegion = args.currentRegion();
                    maybeCurrentRegion.ifPresent(currentRegion -> {
                        if (args.centreOfMass().isPresent() &&
                                currentRegion.estimatedCreepPercentage() > 0.25f &&
                                !currentRegion.hasEnemyBase()) {
                            requestScannerSweep(args.task(), args.agentWithData(), centreOfMass, scanRequiredBefore);
                        }
                    });
                }
            });
            return ImmutableAttackContext.builder()
                    .currentUnitsStimmed(currentsUnitsStimmed)
                    .maxUnitsToStim(maxUnitsToStim)
                    .remainingUnitsToStim(new AtomicLong(Math.max(0L, maxUnitsToStim - currentsUnitsStimmed)))
                    .enemyUnitMap(enemyUnitMap)
                    .build();
        }

        @Override
        public AttackContext onArmyUnitStep(AttackContext context, Unit unit, BaseArgs args) {
            if (unit.getWeaponCooldown().isEmpty() || unit.getWeaponCooldown().get() < 0.01f) {
                args.attackPosition().ifPresent(attackPosition -> {
                    if (args.currentRegion().equals(args.targetRegion())) {
                        args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, attackPosition, false);
                    } else if (args.nextRegion().isPresent()) {
                        args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, args.nextRegion().get().region().centrePoint(), false);
                    } else {
                        args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, attackPosition, false);
                    }
                });
                if (unit.getType() == Units.TERRAN_GHOST && unit.getEnergy().orElse(0f) > 50f) {
                    Optional<Unit> nearestEnergyTarget = context.enemyUnitMap()
                            .getNearestInRadius(unit.getPosition().toPoint2d(), 11.5f,
                                    enemy -> enemy.getEnergy().isPresent() && enemy.getEnergyMax().isPresent() && enemy.getEnergy().get() > enemy.getEnergyMax().get() / 2);
                    if (nearestEnergyTarget.isPresent()) {
                        args.agentWithData().actions().unitCommand(unit, Abilities.EFFECT_EMP, nearestEnergyTarget.get().getPosition().toPoint2d(), false);
                    } else {
                        // Find nearest biological target with 145 hp or more.
                        Optional<Unit> nearestBiologicalTarget = context.enemyUnitMap()
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

            } else if (unit.getType() == Units.TERRAN_MARINE || unit.getType() == Units.TERRAN_MARAUDER) {
                // Stutter-step micro. The more we're winning, the smaller the stutter radius.
                float stutterRadius = 10f;
                if (args.fightPerformance() == FightPerformance.STABLE) {
                    stutterRadius = 7.5f;
                } else if (args.fightPerformance() == FightPerformance.WINNING) {
                    stutterRadius = 3f;
                }
                // If not really winning, then stutter step backwards.
                Optional<Point2d> nearestEnemyUnit = context
                        .enemyUnitMap()
                        .getNearestInRadius(unit.getPosition().toPoint2d(), 10f)
                        .map(enemy -> enemy.getPosition().toPoint2d());
                float finalStutterRadius = stutterRadius;
                // If the nearest enemy is within stutterRadius, walk away, otherwise walk towards it.
                Optional<Point2d> retreatPosition = nearestEnemyUnit
                        .filter(enemyPosition -> enemyPosition.distance(unit.getPosition().toPoint2d()) < finalStutterRadius)
                        .map(enemyPosition -> Utils.getRetreatPosition(unit.getPosition().toPoint2d(), enemyPosition, 1.5f));
                retreatPosition.ifPresent(retreatPoint2d -> {
                    args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, retreatPoint2d, false);
                });
                // Marine and marauder stimpack usage.
                if (!unit.getBuffs().contains(Buffs.STIMPACK) &&
                        !unit.getBuffs().contains(Buffs.STIMPACK_MARAUDER) &&
                        context.remainingUnitsToStim().get() > 0) {
                    if (args.agentWithData().gameData().unitHasAbility(unit.getTag(), Abilities.EFFECT_STIM)) {
                        context.remainingUnitsToStim().decrementAndGet();
                        args.agentWithData().actions().unitCommand(unit, Abilities.EFFECT_STIM, false);
                    }
                }
            } else if (unit.getType() == Units.TERRAN_GHOST) {
                Optional<Point2d> nearestEnemyUnit = context
                        .enemyUnitMap()
                        .getNearestInRadius(unit.getPosition().toPoint2d(), 15f)
                        .map(enemy -> enemy.getPosition().toPoint2d());

                Optional<Point2d> retreatPosition = nearestEnemyUnit
                        .map(enemyPosition -> Utils.getRetreatPosition(unit.getPosition().toPoint2d(), enemyPosition, 2.5f));
                retreatPosition.ifPresent(retreatPoint2d -> {
                    args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, retreatPoint2d, false);
                });
            }
            if (unit.getType() == Units.TERRAN_WIDOWMINE) {
                Optional<Point2d> nearestEnemyUnit = context
                        .enemyUnitMap()
                        .getNearestInRadius(unit.getPosition().toPoint2d(), 10f)
                        .map(enemy -> enemy.getPosition().toPoint2d());
                if (nearestEnemyUnit.isPresent() && nearestEnemyUnit.get().distance(unit.getPosition().toPoint2d()) < 10f) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.BURROW_DOWN_WIDOWMINE, false);
                }
            } else if (unit.getType() == Units.TERRAN_WIDOWMINE_BURROWED) {
                Optional<Point2d> nearestEnemyUnit = context
                        .enemyUnitMap()
                        .getNearestInRadius(unit.getPosition().toPoint2d(), 15f)
                        .map(enemy -> enemy.getPosition().toPoint2d());
                if (nearestEnemyUnit.isEmpty()) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.BURROW_UP_WIDOWMINE, false);
                }

            } else if (unit.getType() == Units.TERRAN_MEDIVAC || unit.getType() == Units.TERRAN_RAVEN) {
                // Medivacs move to the centre of mass.
                args.centreOfMass().ifPresent(centreOfMass -> {
                    args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, false);
                    // Boost if far away.
                    if (args.agentWithData().gameData().unitHasAbility(unit.getTag(), Abilities.EFFECT_MEDIVAC_IGNITE_AFTERBURNERS) &&
                            unit.getPosition().toPoint2d().distance(centreOfMass) > 20f) {
                        args.agentWithData().actions().unitCommand(unit, Abilities.EFFECT_MEDIVAC_IGNITE_AFTERBURNERS, false);
                    }
                });
            }
            return context;
        }

        @Override
        public AggressionState getNextState(AttackContext context, BaseArgs args) {
            if (args.attackPosition().isPresent()) {
                if (args.fightPerformance() == FightPerformance.BADLY_LOSING ||
                        args.fightPerformance() == FightPerformance.SLIGHTLY_LOSING) {
                    return AggressionState.RETREATING;
                } else {
                    return AggressionState.ATTACKING;
                }
            } else {
                return AggressionState.IDLE;
            }
        }

        @Override
        public boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData, Optional<RegionData> nextRegion, Optional<Double> dispersion) {
            return dispersion.orElse(0.0) <= 2.0 && currentRegionData.hasEnemyBase() == false;
        }
    }

    private static Point2dMap<Unit> constructEnemyUnitMap(DefaultArmyTaskBehaviourStateHandler.BaseArgs args) {
        Point2dMap<Unit> enemyUnitMap = new Point2dMap<>(unit -> unit.getPosition().toPoint2d());
        args.enemyArmies().forEach(enemyArmy -> {
           enemyArmy.unitTags().forEach(tag -> {
               UnitInPool maybeEnemyUnit = args.agentWithData().observation().getUnit(tag);
               if (maybeEnemyUnit != null) {
                   enemyUnitMap.insert(maybeEnemyUnit.unit());
               }
           });
        });
        return enemyUnitMap;
    }

    private static List<TaskPromise> requestScannerSweep(DefaultArmyTask task, AgentData data, Point2d scanPosition, long scanRequiredBefore) {
        return data.taskManager().dispatchMessage(task,
                ImmutableScanRequestTaskMessage.builder()
                        .point2d(scanPosition)
                        .requiredBefore(scanRequiredBefore)
                        .build());
    }

    private static class DisengagingHandler implements DefaultArmyTaskBehaviourStateHandler<DisengagingContext> {
        @Override
        public void onEnterState(BaseArgs args) {

        }

        @Override
        public DisengagingContext onArmyStep(BaseArgs args) {
            return ImmutableDisengagingContext.builder().enemyUnitMap(constructEnemyUnitMap(args)).build();
        }

        @Override
        public DisengagingContext onArmyUnitStep(DisengagingContext context, Unit unit, BaseArgs args) {
            if (unit.getWeaponCooldown().isEmpty() || unit.getWeaponCooldown().get() < 0.1f) {
                if (args.nextRegion().isPresent()) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, args.nextRegion().get().region().centrePoint(), false);
                }
            } else {
                // If not really winning, then stutter step backwards.
                Optional<Point2d> nearestEnemyUnit = context
                        .enemyUnitMap()
                        .getNearestInRadius(unit.getPosition().toPoint2d(), 10f)
                        .map(enemy -> enemy.getPosition().toPoint2d());
                Optional<Point2d> retreatPosition = nearestEnemyUnit
                        .map(enemyPosition -> Utils.getRetreatPosition(unit.getPosition().toPoint2d(), enemyPosition, 1.5f));
                retreatPosition.ifPresent(retreatPoint2d -> {
                    args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, retreatPoint2d, false);
                });
                if (unit.getType() == Units.TERRAN_GHOST && unit.getBuffs().contains(Buffs.GHOST_CLOAK)) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.BEHAVIOR_CLOAK_OFF_GHOST, false);
                }
            }
            if (unit.getType() == Units.TERRAN_MEDIVAC || unit.getType() == Units.TERRAN_RAVEN) {
                // Medivacs boost to retreat.
                args.centreOfMass().ifPresent(centreOfMass -> {
                    args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, false);
                    // Boost if far away.
                    if (args.agentWithData().gameData().unitHasAbility(unit.getTag(), Abilities.EFFECT_MEDIVAC_IGNITE_AFTERBURNERS) &&
                            unit.getPosition().toPoint2d().distance(centreOfMass) > 20f) {
                        args.agentWithData().actions().unitCommand(unit, Abilities.EFFECT_MEDIVAC_IGNITE_AFTERBURNERS, false);
                    }
                });
            }
            return context;
        }

        @Override
        public AggressionState getNextState(DisengagingContext context, BaseArgs args) {
            if (args.fightPerformance() == FightPerformance.WINNING) {
                return AggressionState.ATTACKING;
            } else {
                return AggressionState.RETREATING;
            }
        }

        @Override
        public boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData, Optional<RegionData> nextRegion, Optional<Double> dispersion) {
            return true;
        }
    }

    private static class RegroupingHandler implements DefaultArmyTaskBehaviourStateHandler<RegroupingContext> {
        @Override
        public void onEnterState(BaseArgs args) {

        }

        @Override
        public RegroupingContext onArmyStep(BaseArgs args) {
            return null;
        }

        @Override
        public RegroupingContext onArmyUnitStep(RegroupingContext context, Unit unit, BaseArgs args) {
            return null;
        }

        @Override
        public AggressionState getNextState(RegroupingContext context, BaseArgs args) {
            return AggressionState.ATTACKING;
        }

        @Override
        public boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData, Optional<RegionData> nextRegion, Optional<Double> dispersion) {
            return true;
        }
    }

    private static class IdleHandler implements DefaultArmyTaskBehaviourStateHandler<IdleContext> {
        @Override
        public void onEnterState(BaseArgs args) {

        }

        @Override
        public IdleContext onArmyStep(BaseArgs args) {
            return null;
        }

        @Override
        public IdleContext onArmyUnitStep(IdleContext context, Unit unit, BaseArgs args) {
            args.attackPosition().ifPresent(attackPosition -> {
                if (args.currentRegion().equals(args.targetRegion())) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, attackPosition, false);
                } else if (args.nextRegion().isPresent()) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, args.nextRegion().get().region().centrePoint(), false);
                }
            });
            return null;
        }

        @Override
        public AggressionState getNextState(IdleContext context, BaseArgs args) {
            if (args.attackPosition().isPresent()) {
                return AggressionState.ATTACKING;
            } else {
                return AggressionState.IDLE;
            }
        }

        @Override
        public boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData, Optional<RegionData> nextRegion, Optional<Double> dispersion) {
            return true;
        }
    }
}
