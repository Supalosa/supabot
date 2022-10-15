package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;

import java.util.Comparator;
import java.util.Optional;
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
    Optional<Task> getTaskForUnit(Tag unit);

    /**
     * Reserves a unit for a task, until the task is complete (isComplete: true).
     *
     * @param unit The unit to reserve
     * @param task The task to reserve the unit for
     */
    void reserveUnit(Tag unit, Task task);

    /**
     * Reserves a unit for a task, until the task is complete (isComplete: true).
     *
     * @param unit The unit to reserve
     * @param task The task to reserve the unit for
     */
    void reserveUnit(Optional<Tag> unit, Task task);

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

    void onStep(AgentData data, S2Agent agent);

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
     */
    void dispatchUnit(Unit unit);
}
