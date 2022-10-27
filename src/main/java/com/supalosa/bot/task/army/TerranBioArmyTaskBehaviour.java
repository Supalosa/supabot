package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.task.army.micro.TerranMicro;
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
            Point2dMap<Unit> enemyUnitMap = context.enemyUnitMap();
            Optional<Point2d> goalPosition = args.attackPosition();
            if (unit.getType() == Units.TERRAN_MARINE || unit.getType() == Units.TERRAN_MARAUDER) {
                TerranMicro.handleMarineMarauderMicro(unit, goalPosition, args, enemyUnitMap, context.remainingUnitsToStim());
            } else if (unit.getType() == Units.TERRAN_GHOST) {
                TerranMicro.handleGhostMicro(unit, goalPosition, args, enemyUnitMap);
            } else if (unit.getType() == Units.TERRAN_WIDOWMINE) {
                TerranMicro.handleWidowmineMicro(unit, args, enemyUnitMap);
            } else if (unit.getType() == Units.TERRAN_WIDOWMINE_BURROWED) {
                TerranMicro.handleWidowmineBurrowedMicro(unit, args, enemyUnitMap);
            } else if (unit.getType() == Units.TERRAN_MEDIVAC || unit.getType() == Units.TERRAN_RAVEN) {
                TerranMicro.handleMedivacMicro(unit, args, enemyUnitMap);
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
            Point2dMap<Unit> enemyUnitMap = context.enemyUnitMap();
            Optional<Point2d> goalPosition = args.previousRegion()
                    .or(() -> args.retreatRegion())
                    .map(RegionData::region)
                    .map(Region::centrePoint);
            if (args.dispersion().orElse(0.0) <= 2.0) {
                if (unit.getType() == Units.TERRAN_MARINE || unit.getType() == Units.TERRAN_MARAUDER) {
                    TerranMicro.handleMarineMarauderMicro(unit, goalPosition, args, enemyUnitMap, new AtomicLong(0));
                } else if (unit.getType() == Units.TERRAN_GHOST) {
                    TerranMicro.handleGhostMicro(unit, goalPosition, args, enemyUnitMap);
                } else if (unit.getType() == Units.TERRAN_WIDOWMINE) {
                    TerranMicro.handleWidowmineMicro(unit, args, enemyUnitMap);
                } else if (unit.getType() == Units.TERRAN_WIDOWMINE_BURROWED) {
                    TerranMicro.handleWidowmineBurrowedMicro(unit, args, enemyUnitMap);
                } else if (unit.getType() == Units.TERRAN_MEDIVAC || unit.getType() == Units.TERRAN_RAVEN) {
                    TerranMicro.handleMedivacMicro(unit, args, enemyUnitMap);
                }
            } else {
                goalPosition.ifPresent(position ->
                        args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, position, false));
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
