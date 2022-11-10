package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.DisplayType;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.task.army.micro.TerranMicro;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.task.terran.ImmutableScanRequestTaskMessage;
import com.supalosa.bot.utils.Point2dMap;
import org.immutables.value.Value;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The default behaviour for a Terran Bio Army.
 */
public class TerranBioArmyTaskBehaviour extends BaseDefaultArmyTaskBehaviour<
        TerranBioArmyTaskBehaviour.AttackContext,
        TerranBioArmyTaskBehaviour.DisengagingContext,
        TerranBioArmyTaskBehaviour.RegroupingContext,
        TerranBioArmyTaskBehaviour.IdleContext> {

    interface StimContext {
        long maxUnitsToStim();
        long currentUnitsStimmed();
        AtomicLong remainingUnitsToStim();
    }

    @Value.Immutable
    interface AttackContext extends StimContext {
        Point2dMap<Unit> enemyUnitMap();
    }

    @Value.Immutable
    interface DisengagingContext extends StimContext {
        Point2dMap<Unit> enemyUnitMap();
        Optional<Point2d> nextRetreatPoint();
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
        //this(new AttackHandler(), new DisengagingHandler(), new RegroupingHandler(), new IdleHandler());
        this(new AttackHandler(), new DisengagingHandler(), new AttackHandler(), new AttackHandler());
    }

    private static void handleMicro(Unit unit,
                                    Point2dMap<Unit> enemyUnitMap,
                                    DefaultArmyTaskBehaviourStateHandler.BaseArgs args,
                                    StimContext stimContext,
                                    Optional<Point2d> goalPosition) {
        if (unit.getType() == Units.TERRAN_MARINE || unit.getType() == Units.TERRAN_MARAUDER) {
            TerranMicro.handleMarineMarauderMicro(unit, goalPosition, args, enemyUnitMap, stimContext.remainingUnitsToStim());
        } else if (unit.getType() == Units.TERRAN_GHOST) {
            TerranMicro.handleGhostMicro(unit, goalPosition, args, enemyUnitMap);
        } else if (unit.getType() == Units.TERRAN_WIDOWMINE) {
            TerranMicro.handleWidowmineMicro(unit, goalPosition, args, enemyUnitMap);
        } else if (unit.getType() == Units.TERRAN_WIDOWMINE_BURROWED) {
            TerranMicro.handleWidowmineBurrowedMicro(unit, args, enemyUnitMap);
        } else if (unit.getType() == Units.TERRAN_MEDIVAC || unit.getType() == Units.TERRAN_RAVEN) {
            TerranMicro.handleMedivacMicro(unit, goalPosition, args, enemyUnitMap);
        } else if (unit.getType() == Units.TERRAN_VIKING_FIGHTER) {
            TerranMicro.handleVikingFighterMicro(unit, goalPosition, args, enemyUnitMap);
        } else if (unit.getType() == Units.TERRAN_VIKING_ASSAULT) {
            TerranMicro.handleVikingAssaultMicro(unit, goalPosition, args, enemyUnitMap);
        } else if (unit.getType() == Units.TERRAN_LIBERATOR) {
            TerranMicro.handleLiberatorMicro(unit, goalPosition, args, enemyUnitMap);
        } else if (unit.getType() == Units.TERRAN_LIBERATOR_AG) {
            TerranMicro.handleLiberatorAgMicro(unit, goalPosition, args, enemyUnitMap);
        } else {
            TerranMicro.handleDefaultMicro(unit, goalPosition, args, enemyUnitMap);
        }
    }

    private static class AttackHandler implements DefaultArmyTaskBehaviourStateHandler<AttackContext> {

        // Time to spend in a region per child army on the way.
        private static final long DELAY_TIME_IN_REGION = 22L * 2;

        @Override
        public void onEnterState(BaseArgs args) {

        }

        @Override
        public AttackContext onArmyStep(BaseArgs args) {
            long currentsUnitsStimmed = args.unitsInArmy().stream()
                    .filter(unit -> unit.getBuffs().contains(Buffs.STIMPACK) || unit.getBuffs().contains(Buffs.STIMPACK_MARAUDER))
                    .count();
            long maxUnitsToStim = (long)args.enemyVirtualArmy().threat();
            Point2dMap<Unit> enemyUnitMap = constructEnemyUnitMap(args);

            // Request if creep is nearby and there's no base.
            args.centreOfMass().ifPresent(centreOfMass -> {
                long scanRequiredBefore = args.agentWithData().observation().getGameLoop() + 22L * 2;
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
            handleMicro(unit, enemyUnitMap, args, context, goalPosition);
            return context;
        }

        @Override
        public AggressionState getNextState(AttackContext context, BaseArgs args) {
            if (args.attackPosition().isPresent()) {
                if (args.predictedFightPerformance() == FightPerformance.BADLY_LOSING ||
                        args.fightPerformance() == FightPerformance.BADLY_LOSING ||
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
        public boolean shouldMoveFromRegion(AgentWithData agentWithData,
                                            RegionData currentRegionData,
                                            Optional<RegionData> nextRegion,
                                            Optional<Double> dispersion,
                                            List<DefaultArmyTask> childArmies,
                                            long timeSpentInRegion,
                                            DefaultArmyTask currentArmy) {
            // Wait for child armies that are larger than us.
            long strongerChildArmies = childArmies
                    .stream()
                    .filter(childArmy -> childArmy.getPower() > currentArmy.getPower())
                    .count();
            if (timeSpentInRegion < strongerChildArmies * DELAY_TIME_IN_REGION) {
                return false;
            }
            // max 30s attacking a base (prevent it from getting stuck)
            return dispersion.orElse(0.0) <= 3.0 &&
                    (currentRegionData.hasEnemyBase() == false || timeSpentInRegion > 30 * 22L);
        }
    }

    private static Point2dMap<Unit> constructEnemyUnitMap(DefaultArmyTaskBehaviourStateHandler.BaseArgs args) {
        Point2dMap<Unit> enemyUnitMap = new Point2dMap<>(unit -> unit.getPosition().toPoint2d());
        args.enemyVirtualArmy().unitTags().forEach(tag -> {
           UnitInPool maybeEnemyUnit = args.agentWithData().observation().getUnit(tag);
           if (maybeEnemyUnit != null && maybeEnemyUnit.unit().getDisplayType() == DisplayType.VISIBLE) {
               enemyUnitMap.insert(maybeEnemyUnit.unit());
           }
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
            Optional<Region> previousRegion = args.previousRegion()
                    .map(RegionData::region);
            Optional<Region> retreatRegion = args.retreatRegion()
                    .map(RegionData::region);
            boolean isInRetreatRegion = previousRegion.map(Region::regionId).equals(retreatRegion.map(Region::regionId));
            Optional<Point2d> goalPosition = isInRetreatRegion ?
                    args.retreatPosition() :
                    previousRegion.map(Region::centrePoint).or(() -> args.retreatPosition());
            return ImmutableDisengagingContext.builder()
                    .enemyUnitMap(constructEnemyUnitMap(args))
                    .nextRetreatPoint(goalPosition)
                    // TODO: consider trying to stim away.
                    .currentUnitsStimmed(0)
                    .maxUnitsToStim(0)
                    .remainingUnitsToStim(new AtomicLong(0L))
                    .build();
        }

        @Override
        public DisengagingContext onArmyUnitStep(DisengagingContext context, Unit unit, BaseArgs args) {
            Point2dMap<Unit> enemyUnitMap = context.enemyUnitMap();
            Optional<Point2d> goalPosition = context.nextRetreatPoint();
            // If we're close enough to the centre of mass, and the army is together, we fight.
            if (args.centreOfMass().isPresent() &&
                    args.centreOfMass().get().distance(unit.getPosition().toPoint2d()) < 5f &&
                    args.dispersion().orElse(0.0) <= 2.0) {
                handleMicro(unit, enemyUnitMap, args, context, goalPosition);
            } else {
                // Everyone runs away.
                goalPosition.ifPresent(position ->
                        args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, position, false));
            }
            return context;
        }

        @Override
        public AggressionState getNextState(DisengagingContext context, BaseArgs args) {
            if (args.predictedFightPerformance() == FightPerformance.WINNING || args.predictedFightPerformance() == FightPerformance.STABLE) {
                return AggressionState.ATTACKING;
            } else {
                return AggressionState.RETREATING;
            }
        }

        @Override
        public boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData, Optional<RegionData> nextRegion, Optional<Double> dispersion, List<DefaultArmyTask> childArmies, long timeSpentInRegion, DefaultArmyTask currentArmy) {
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
        public boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData, Optional<RegionData> nextRegion, Optional<Double> dispersion, List<DefaultArmyTask> childArmies, long timeSpentInRegion, DefaultArmyTask currentArmy) {
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
        public boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData, Optional<RegionData> nextRegion, Optional<Double> dispersion, List<DefaultArmyTask> childArmies, long timeSpentInRegion, DefaultArmyTask currentArmy) {
            return true;
        }
    }
}
