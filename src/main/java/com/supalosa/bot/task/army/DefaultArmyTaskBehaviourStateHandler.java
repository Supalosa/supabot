package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.RegionData;
import org.immutables.value.Value;

import java.util.List;
import java.util.Optional;

/**
 * The handler for a specific {@code AggressionState} of an army given a {@code DefaultArmyTaskBehaviour}.
 *
 * @param <T> The context type of this state. It is an object that is constructed once each step and passed to each unit.
 */
public interface DefaultArmyTaskBehaviourStateHandler<T> {

    @Value.Immutable
    interface BaseArgs {
        /**
         * The agent and data structures with which to operate on the game.
         */
        @Value.Parameter
        AgentWithData agentWithData();

        /**
         * A list of all the live Units in this army.
         */
        @Value.Parameter
        List<Unit> unitsInArmy();

        /**
         * A list of enemy Armies nearby.
         */
        @Value.Parameter
        List<Army> enemyArmies();

        /**
         * A periodically calculated estimate of how we are performing in the fight.
         */
        @Value.Parameter
        FightPerformance fightPerformance();

        /**
         * The exact position that we are trying to attack.
         */
        @Value.Parameter
        Optional<Point2d> attackPosition();

        /**
         * The region that we are currently in.
         */
        @Value.Parameter
        Optional<Region> currentRegion();

        /**
         * The next region that we're moving to.
         */
        @Value.Parameter
        Optional<Region> nextRegion();

        /**
         * The region that we want to end up at.
         */
        @Value.Parameter
        Optional<Region> targetRegion();
    }

    /**
     * Called when the army first enters this state.
     *
     * @param args The basic arguments for all tasks.
     */
    void onEnterState(BaseArgs args);

    /**
     * Called before any units are processed by the army. This allows you to construct state that can be shared across
     * the whole army.
     *
     * @param args The basic arguments for all tasks.
     * @return A context object that should be passed to each unit.
     */
    T onArmyStep(BaseArgs args);

    /**
     * Called for each unit in the army while it is in this state. This is where units will move, attack etc.
     * You can reuse the context passed by the {@code onArmyStep} method and even mutate it before the next unit
     * uses it. Note: there is no defined order in which this method is called over the army.
     *
     * @param context A context object constructed in {@code onArmyStep} that should be passed to each unit.
     * @param unit The unit to operate on.
     * @param args The basic arguments for all tasks.
     * @return A context object that should be passed to the next unit.
     */
    T onArmyUnitStep(T context, Unit unit, BaseArgs args);

    /**
     * Returns the aggression state that the army should move into next.
     *
     * @param context The context passed from {@code onArmyStep} and through all the units.
     * @param args The basic arguments for all tasks.
     * @return The next state of the army - which may just be the initial state.
     */
    AggressionState getNextState(T context, BaseArgs args);

    /**
     * Returns whether the unit should move on from the current region or stay (and continue to engage).
     *
     * @param agentWithData The agent and data structures with which to operate on the game.
     * @param currentRegionData The data for the region that we're in.
     * @param currentRegion The current region that the army is in, or none if we can't calculate it.
     * @param nextRegion The next region to travel to, or none if we're already in the target region.
     * @return If we should move to another region.
     */
    boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData, Region currentRegion, Optional<Region> nextRegion);
}
