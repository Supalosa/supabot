package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;

/**
 * Base implementation of the {@code DefaultArmyTaskBehaviour} that does nothing for each hook.
 *
 * @param <A> The type of the AttackContext.
 * @param <D> The type of the DisengagingContext.
 * @param <R> The type of the RegroupingContext.
 * @param <I> The type of the IdleContext.
 */
public abstract class BaseDefaultArmyTaskBehaviour<A,D,R,I> implements DefaultArmyTaskBehaviour<A,D,R,I> {

    private final DefaultArmyTaskBehaviourStateHandler<A> attackHandler;
    private final DefaultArmyTaskBehaviourStateHandler<D> disengagingHandler;
    private final DefaultArmyTaskBehaviourStateHandler<R> regroupingHandler;
    private final DefaultArmyTaskBehaviourStateHandler<I> idleHandler;

    public BaseDefaultArmyTaskBehaviour(
            DefaultArmyTaskBehaviourStateHandler<A> attackHandler,
            DefaultArmyTaskBehaviourStateHandler<D> disengagingHandler,
            DefaultArmyTaskBehaviourStateHandler<R> regroupingHandler,
            DefaultArmyTaskBehaviourStateHandler<I> idleHandler) {
        this.attackHandler = attackHandler;
        this.disengagingHandler = disengagingHandler;
        this.regroupingHandler = regroupingHandler;
        this.idleHandler = idleHandler;
    }

    @Override
    public void onUnitAdded(Unit unit) {}

    @Override
    public void onUnitDamaged(Unit unit, float previousHealth, float newHealth) {}

    @Override
    public void onUnitLost(Unit unit) {}

    @Override
    public void onEnemyUnitDamaged(Unit unit, float previousHealth, float newHealth) {}

    @Override
    public void onEnemyUnitLost(Unit unit, Point2d previousLocation) {}

    @Override
    public DefaultArmyTaskBehaviourStateHandler getAttackHandler() {
        return attackHandler;
    }

    @Override
    public DefaultArmyTaskBehaviourStateHandler getDisengagingHandler() {
        return disengagingHandler;
    }

    @Override
    public DefaultArmyTaskBehaviourStateHandler getRegroupingHandler() {
        return regroupingHandler;
    }

    @Override
    public DefaultArmyTaskBehaviourStateHandler getIdleHandler() {
        return idleHandler;
    }
}
