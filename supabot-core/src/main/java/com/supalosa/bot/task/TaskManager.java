package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.task.army.ArmyTask;
import com.supalosa.bot.task.army.DefaultArmyTask;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskMessageResponse;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.task.mission.MissionTask;
import com.supalosa.bot.task.mission.TaskWithArmy;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Overarching manager for coordinating between tasks.
 */
public interface TaskManager {
    /**
     * Returns the task that a unit is assigned to (if applicable).
     *
     * @param unit Unit to test
     * @return The task for that unit, or none if unassigned
     */
    Optional<Task> getTaskForUnit(Unit unit);

    /**
     * Reserves a unit for a task, until the task is complete (isComplete: true).
     *
     * @param unit The unit to reserve
     * @param task The task to reserve the unit for
     */
    void reserveUnit(Unit unit, Task task);

    /**
     * Reserves a unit for a task, until the task is complete (isComplete: true).
     *
     * @param unit The unit to reserve
     * @param task The task to reserve the unit for
     */
    void reserveUnit(Optional<Unit> unit, Task task);

    /**
     * Release a unit, as long as the provided Task matches the task the unit is assigned to.
     *
     * @param unit The unit to release
     * @param task The task the unit should have been assigned to.
     */
    void releaseUnit(Tag unit, Task task);

    /**
     * Finds a free unit that is not reserved. It will be reserved for the task until that task is complete.
     *
     * @param observationInterface Interface to test with.
     * @param predicate Predicate that is used to find appropriate unit.
     * @return Optional unit if unreserved unit is found matching predicate, or empty.
     */
    Optional<UnitInPool> findFreeUnitForTask(Task task,
                                             ObservationInterface observationInterface,
                                             Predicate<UnitInPool> predicate);

    /**
     * Finds a free unit that is not reserved. It will be reserved for the task until that task is complete.
     *
     * @param observationInterface Interface to test with.
     * @param predicate Predicate that is used to find appropriate unit.
     * @param comparator Comparator used to order candidates.
     * @return Optional unit if unreserved unit is found matching predicate, or empty.
     */
    Optional<UnitInPool> findFreeUnitForTask(Task task,
                                             ObservationInterface observationInterface,
                                             Predicate<UnitInPool> predicate,
                                             Comparator<UnitInPool> comparator);

    /**
     * Finds a free army for a task that needs an army (such as a defensive/offensive task).
     *
     * @param missionTask Task that needs an army.
     * @param predicate Predicate to filter the armies.
     * @return An army that is matches the predicate. If there are multiple matches, the one with the closest
     * centre of mass is chosen. An army without a centre of mass will not be tested.
     */
    Optional<ArmyTask> findFreeArmyForTask(MissionTask missionTask, Predicate<ArmyTask> predicate);

    /**
     * Finds a free army for a task that needs an army (such as a defensive/offensive task).
     *
     * @param missionTask Task that needs an army.
     * @param predicate Predicate to filter the armies.
     * @param comparator Comparator to sort the armies.
     * @return An army that is matches the predicate. The first entry (after sorting by comparator) is returned.
     */
    Optional<ArmyTask> findFreeArmyForTask(MissionTask missionTask, Predicate<ArmyTask> predicate, Comparator<ArmyTask> comparator);

    void reserveArmy(MissionTask missionTask, ArmyTask armyTask);

    /**
     * Returns the tags of all the units assigned to the given task.
     */
    Collection<Tag> getAssignedUnitsForTask(TaskWithUnits task);

    void onStep(AgentWithData agentWithData);

    /**
     * Adds a task to be tracked and executed by the task manager.
     *
     * @param task Task to add.
     * @param maxParallel Max parallel 'similar' tasks to run.
     * @return True if the task is accepted, false if not.
     */
    boolean addTask(Task task, int maxParallel);

    int countSimilarTasks(Task task);

    void debug(S2Agent agent);

    /**
     * Dispatch a unit to the tasks that might want it.
     *
     * @return True if the unit was assigned.
     */
    boolean dispatchUnit(Unit unit);

    /**
     * Dispatch a message to all other tasks. Multiple tasks can respond.
     *
     * @param task The task sending the message.
     * @param message The message to send.
     * @return A list of promises from tasks that responded.
     */
    List<TaskPromise> dispatchMessage(Task task, TaskMessage message);

    /**
     * Move units (matching a predicate) from one task to another.
     * @return The amount of units moved.
     */
    int reassignUnits(TaskWithUnits from, TaskWithUnits to, ObservationInterface observationInterface, Predicate<Unit> predicate);

    int totalReservedMinerals();

    int totalReservedVespene();

    boolean hasTask(Task task);

    long countTasks(Predicate<Task> filter);

    <T> T visitTasks(TaskVisitor<T> visitor);
}
