package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.utils.Point2dMap;
import com.supalosa.bot.utils.UnitFilter;
import org.immutables.value.Value;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Worker rush
 */
public class TerranWorkerRushDefenceTaskBehaviour extends BaseDefaultArmyTaskBehaviour<
        TerranWorkerRushDefenceTaskBehaviour.Context,
        TerranWorkerRushDefenceTaskBehaviour.Context,
        TerranWorkerRushDefenceTaskBehaviour.Context,
        TerranWorkerRushDefenceTaskBehaviour.Context> {

    @Value.Immutable
    interface Context {
        Point2dMap<Unit> enemyUnitMap();
        Point2d enemyCentreOfMass();
        List<UnitInPool> mineralsNearStartPosition();
        List<UnitInPool> enemyUnitsNearStartPosition();
        double averageWorkerHealth();
    }

    public TerranWorkerRushDefenceTaskBehaviour(DefaultArmyTaskBehaviourStateHandler attackHandler,
                                                DefaultArmyTaskBehaviourStateHandler disengagingHandler,
                                                DefaultArmyTaskBehaviourStateHandler regroupingHandler,
                                                DefaultArmyTaskBehaviourStateHandler idleHandler) {
        super(attackHandler, disengagingHandler, regroupingHandler, idleHandler);
    }

    public TerranWorkerRushDefenceTaskBehaviour() {
        super(new Handler(), new Handler(), new Handler(), new Handler());
    }

    private static class Handler implements DefaultArmyTaskBehaviourStateHandler<Context> {
        @Override
        public void onEnterState(BaseArgs args) {
        }

        @Override
        public Context onArmyStep(BaseArgs args) {
            Point2dMap<Unit> enemyUnitMap = constructEnemyUnitMap(args);

            List<Unit> units = args.unitsInArmy();
            List<UnitInPool> enemyUnitsNearStartPosition = args.agentWithData().observation().getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.ENEMY)
                            .inRangeOf(args.agentWithData().observation().getStartLocation().toPoint2d())
                            .range(20f)
                            .build());
            List<UnitInPool> mineralsNearStartPosition = args.agentWithData().observation().getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.NEUTRAL)
                            .unitTypes(Constants.MINERAL_TYPES)
                            .inRangeOf(args.agentWithData().observation().getStartLocation().toPoint2d())
                            .range(10f)
                            .build());
            double averageWorkerHealth = units.stream().mapToDouble(unit ->
                            unit.getHealth().orElse(0f))
                    .average().orElse(0.0);
            double averageEnemyX = enemyUnitsNearStartPosition.stream().mapToDouble(unit ->
                            unit.unit().getPosition().getX())
                    .average().orElse(0.0);
            double averageEnemyY = enemyUnitsNearStartPosition.stream().mapToDouble(unit ->
                            unit.unit().getPosition().getY())
                    .average().orElse(0.0);
            Point2d enemyCentreOfMass = Point2d.of((float)averageEnemyX, (float)averageEnemyY);

            return ImmutableContext.builder()
                    .enemyCentreOfMass(enemyCentreOfMass)
                    .averageWorkerHealth(averageWorkerHealth)
                    .enemyUnitMap(enemyUnitMap)
                    .mineralsNearStartPosition(mineralsNearStartPosition)
                    .enemyUnitsNearStartPosition(enemyUnitsNearStartPosition)
                    .build();
        }

        @Override
        public Context onArmyUnitStep(Context context, Unit unit, BaseArgs args) {
            // Empty weapon = no attack - so just scan-attack there.
            double averageWorkerHealth = context.averageWorkerHealth();
            List<UnitInPool> enemyUnitsNearStartPosition = context.enemyUnitsNearStartPosition();
            if (unit.getHealth().orElse(0f) >= (averageWorkerHealth - 5) &&
                    unit.getWeaponCooldown().isEmpty() ||
                    (unit.getWeaponCooldown().isPresent() && unit.getWeaponCooldown().get() < 0.01f)) {
                // If enemy in range, attack the lowest hp one, else repair.
                Optional<UnitInPool> nearbyEnemyOnLowHp = enemyUnitsNearStartPosition.stream()
                        .filter(enemyUnit ->
                                unit.getPosition().distance(enemyUnit.unit().getPosition()) < 1f)
                        .sorted(Comparator.comparing(unitInPool -> unitInPool.unit().getHealth().orElse(0f)))
                        .findFirst();
                if (nearbyEnemyOnLowHp.isPresent()) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, nearbyEnemyOnLowHp.get().unit(), false);
                } else {
                    Optional<Unit> nearbyScvOnLowHp = args.unitsInArmy().stream()
                            .filter(nearbyScv -> !nearbyScv.getTag().equals(unit.getTag()))
                            .filter(friendlyUnit ->
                                    unit.getPosition().distance(friendlyUnit.getPosition()) < 1f)
                            .sorted(Comparator.comparing(unitInPool -> unitInPool.getHealth().orElse(0f)))
                            .findFirst();
                    if (args.agentWithData().observation().getMinerals() > 5 && nearbyScvOnLowHp.isPresent()) {
                        args.agentWithData().actions().unitCommand(unit, Abilities.EFFECT_REPAIR, nearbyScvOnLowHp.get(), false);
                    } else if (args.attackPosition().isPresent()) {
                        args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, args.attackPosition().get(), false);
                    } else {
                        // No enemy in range
                        Optional<UnitInPool> nearbyEnemy = enemyUnitsNearStartPosition.stream()
                                .filter(enemyUnit ->
                                        unit.getPosition().distance(enemyUnit.unit().getPosition()) < 10f)
                                .findFirst();
                        // Drone drill on mineral with lowest tag.
                        Optional<UnitInPool> lowestMineral = context.mineralsNearStartPosition().stream()
                                .sorted(Comparator.comparing((UnitInPool mineral) -> mineral.getTag().getValue()))
                                .findFirst();
                        Optional<UnitInPool> toAttack = nearbyEnemy.or(() -> lowestMineral);
                        toAttack.ifPresent(unitToAttack -> {
                            args.agentWithData().actions().unitCommand(unit, Abilities.SMART, unitToAttack.unit(), false);
                        });
                    }
                }
            } else {
                // move towards a mineral that furthest from the centre of mass.
                Optional<UnitInPool> bestMineral = context.mineralsNearStartPosition().stream()
                        .sorted(Comparator.comparing((UnitInPool mineral) ->
                                mineral.unit().getPosition().toPoint2d().distance(context.enemyCentreOfMass())).reversed())
                        .findFirst();
                if (bestMineral.isPresent()) {
                    // Drill to best mineral
                    args.agentWithData().actions().unitCommand(unit, Abilities.SMART, bestMineral.get().unit(), false);
                } else if (args.retreatPosition().isPresent()) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.MOVE, args.retreatPosition().get(), false);
                }
            }
            return context;
        }

        @Override
        public AggressionState getNextState(Context context, BaseArgs args) {
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
        public boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData, Optional<RegionData> nextRegion, Optional<Double> dispersion, List<DefaultArmyTask> childArmies, long timeSpentInRegion, DefaultArmyTask currentArmy) {
            return true;
        }
    }

    private static Point2dMap<Unit> constructEnemyUnitMap(DefaultArmyTaskBehaviourStateHandler.BaseArgs args) {
        Point2dMap<Unit> enemyUnitMap = new Point2dMap<>(unit -> unit.getPosition().toPoint2d());
        args.enemyVirtualArmy().unitTags().forEach(tag -> {
           UnitInPool maybeEnemyUnit = args.agentWithData().observation().getUnit(tag);
           if (maybeEnemyUnit != null) {
               enemyUnitMap.insert(maybeEnemyUnit.unit());
           }
       });
        return enemyUnitMap;
    }
}
