package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.Buffs;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.utils.UnitFilter;
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

    @Value.Immutable
    interface AttackContext {
        long maxUnitsToStim();
        long currentUnitsStimmed();
        AtomicLong remainingUnitsToStim();
    }

    @Value.Immutable
    interface DisengagingContext {

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
            return ImmutableAttackContext.builder()
                    .currentUnitsStimmed(currentsUnitsStimmed)
                    .maxUnitsToStim(maxUnitsToStim)
                    .remainingUnitsToStim(new AtomicLong(Math.max(0L, maxUnitsToStim - currentsUnitsStimmed)))
                    .build();
        }

        @Override
        public AttackContext onArmyUnitStep(AttackContext context, Unit unit, BaseArgs args) {
            args.attackPosition().ifPresent(attackPosition -> {
                if (args.currentRegion().equals(args.targetRegion())) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, attackPosition, false);
                } else if (args.nextRegion().isPresent()) {
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, args.nextRegion().get().centrePoint(), false);
                }
            });
            if (!unit.getBuffs().contains(Buffs.STIMPACK) &&
                    !unit.getBuffs().contains(Buffs.STIMPACK_MARAUDER) &&
                    context.remainingUnitsToStim().get() > 0) {
                if (args.agentWithData().gameData().unitHasAbility(unit.getTag(), Abilities.EFFECT_STIM)) {
                    context.remainingUnitsToStim().decrementAndGet();
                    args.agentWithData().actions().unitCommand(unit, Abilities.EFFECT_STIM, false);
                }
            }
            return context;
        }

        @Override
        public AggressionState getNextState(AttackContext context, BaseArgs args) {
            if (args.attackPosition().isPresent()) {
                return AggressionState.ATTACKING;
            } else {
                return AggressionState.IDLE;
            }
        }

        @Override
        public boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData,
                                            Region currentRegion, Optional<Region> nextRegion) {
            return true;
        }
    }

    private static class DisengagingHandler implements DefaultArmyTaskBehaviourStateHandler<DisengagingContext> {
        @Override
        public void onEnterState(BaseArgs args) {

        }

        @Override
        public DisengagingContext onArmyStep(BaseArgs args) {
            return null;
        }

        @Override
        public DisengagingContext onArmyUnitStep(DisengagingContext context, Unit unit, BaseArgs args) {
            return null;
        }

        @Override
        public AggressionState getNextState(DisengagingContext context, BaseArgs args) {
            return AggressionState.ATTACKING;
        }

        @Override
        public boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData,
                                            Region currentRegion, Optional<Region> nextRegion) {
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
        public boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData,
                                            Region currentRegion, Optional<Region> nextRegion) {
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
                    args.agentWithData().actions().unitCommand(unit, Abilities.ATTACK, args.nextRegion().get().centrePoint(), false);
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
        public boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData,
                                            Region currentRegion, Optional<Region> nextRegion) {
            return true;
        }
    }
}
