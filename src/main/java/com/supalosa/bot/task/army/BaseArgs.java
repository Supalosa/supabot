package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.RegionData;
import org.immutables.value.Value;

import java.util.List;
import java.util.Optional;

@Value.Immutable
public interface BaseArgs {

    /**
     * The task for which this behaviour is being called.
     */
    @Value.Parameter
    DefaultArmyTask task();

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
     * A virtual army representing all nearby armies.
     */
    @Value.Parameter
    Army enemyVirtualArmy();

    /**
     * The centre of mass of the army.
     */
    @Value.Parameter
    Optional<Point2d> centreOfMass();

    /**
     * Dispersion value (Root mean squared distance) of the army.
     */
    Optional<Double> dispersion();

    /**
     * A periodically calculated estimate of how we are performing in the fight.
     */
    @Value.Parameter
    FightPerformance fightPerformance();

    /**
     * A prediction of how we would fare in the fight against the closest army.
     */
    @Value.Parameter
    FightPerformance predictedFightPerformance();

    /**
     * The exact position that we are trying to attack.
     */
    @Value.Parameter
    Optional<Point2d> targetPosition();

    /**
     * The exact position that we are retreating to.
     */
    @Value.Parameter
    Optional<Point2d> retreatPosition();

    /**
     * The region that we are currently in.
     */
    @Value.Parameter
    Optional<RegionData> currentRegion();

    /**
     * The region that we should retreat towards (factoring in pathfinding).
     */
    @Value.Parameter
    Optional<RegionData> nextRetreatRegion();

    /**
     * The next region that we're moving to. It will be absent if {@code shouldMoveFromRegion} is returning false.
     */
    @Value.Parameter
    Optional<RegionData> nextRegion();

    /**
     * The region that we want to end up at.
     */
    @Value.Parameter
    Optional<RegionData> targetRegion();

    /**
     * The region that we retreat to.
     */
    @Value.Parameter
    Optional<RegionData> retreatRegion();
}
