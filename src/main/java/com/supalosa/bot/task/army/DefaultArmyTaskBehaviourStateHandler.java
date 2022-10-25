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
        @Value.Parameter
        AgentWithData agentWithData();
        @Value.Parameter
        List<Unit> unitsInArmy();
        @Value.Parameter
        List<Army> enemyArmies();
        @Value.Parameter
        Optional<Point2d> attackPosition();
        @Value.Parameter
        Optional<Region> currentRegion();
        @Value.Parameter
        Optional<Region> nextRegion();
        @Value.Parameter
        Optional<Region> targetRegion();
    }

    /**
     * Called when the army first enters this state.
     *
     * @param agentWithData The agent and data structures with which to operate on the game.
     * @param enemyArmies A list of enemy Armies nearby.
     * @param currentRegion The current region that the army is in.
     * @param nextRegion The next region that the army should path to.
     * @param targetRegion The target region that we've been ordered to attack.
     */
    void onEnterState(BaseArgs args);

    /**
     * Called before any units are processed by the army. This allows you to construct state that can be shared across
     * the whole army.
     *
     * @param agentWithData The agent and data structures with which to operate on the game.
     * @param enemyArmies A list of enemy Armies nearby.
     * @param currentRegion The current region that the army is in.
     * @param nextRegion The next region that the army should path to.
     * @param targetRegion The target region that we've been ordered to attack.
     * @return A context object that should be passed to each unit.
     */
    T onArmyStep(BaseArgs args);

    /**
     * Called for each unit in the army while it is in this state. This is where units will move, attack etc.
     * You can reuse the context passed by the {@code onArmyStep} method and even mutate it before the next unit
     * uses it. Note: there is no defined order in which this method is called over the army.
     *
     * @param agentWithData The agent and data structures with which to operate on the game.
     * @param unit The unit to operate on.
     * @param context A context object constructed in {@code onArmyStep} that should be passed to each unit.
     * @param enemyArmies A list of enemy Armies nearby.
     * @param currentRegion The current region that the army is in.
     * @param nextRegion The next region that the army should path to.
     * @param targetRegion The target region that we've been ordered to attack.
     * @return A context object that should be passed to the next unit.
     */
    T onArmyUnitStep(T context, Unit unit, BaseArgs args);

    /**
     * Returns the aggression state that the army should move into next.
     *
     * @param agentWithData The agent and data structures with which to operate on the game.
     * @param context The context passed from {@code onArmyStep} and through all the units.
     * @return The next state of the army - which may just be the initial state.
     */
    AggressionState getNextState(T context, BaseArgs args);

    /**
     * Returns whether the unit should move on from the current region or stay (and continue to engage).
     *
     * @param agentWithData The agent and data structures with which to operate on the game.
     * @param context The context passed from {@code onArmyStep} and through all the units.
     * @param currentRegion The current region that the army is in, or none if we can't calculate it.
     * @param nextRegion The next region to travel to, or none if we're already in the target region.
     * @return If we should move to another region.
     */
    boolean shouldMoveFromRegion(AgentWithData agentWithData, RegionData currentRegionData, Region currentRegion, Optional<Region> nextRegion);
}
