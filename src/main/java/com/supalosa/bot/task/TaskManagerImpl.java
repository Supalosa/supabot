package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.PassengerUnit;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TaskManagerImpl implements TaskManager {

    private final Map<Tag, Task> unitToTaskMap;
    private final Map<String, Task> taskSet;
    private List<TaskWithUnits> orderedTasksNeedingUnits;

    private long unitToTaskMapCleanedAt = 0L;
    private long unassignedUnitsDispatchedAt = 0L;

    public TaskManagerImpl() {
        this.unitToTaskMap = new HashMap<>();
        this.taskSet = new HashMap<>();
        this.orderedTasksNeedingUnits = new ArrayList<>();
    }

    @Override
    public Optional<Task> getTaskForUnit(Tag unit) {
        return Optional.ofNullable(unitToTaskMap.get(unit));
    }

    @Override
    public void reserveUnit(Tag unit, Task task) {
        unitToTaskMap.put(unit, task);
    }

    @Override
    public void reserveUnit(Optional<Tag> unit, Task task) {
        if (unit.isPresent()) {
            reserveUnit(unit.get(), task);
        }
    }

    @Override
    public Optional<UnitInPool> findFreeUnitForTask(Task task, ObservationInterface observationInterface, Predicate<UnitInPool> predicate) {
        return findFreeUnitForTask(task, observationInterface, predicate, null);
    }

    @Override
    public Optional<UnitInPool> findFreeUnitForTask(Task task, ObservationInterface observationInterface,
                                             Predicate<UnitInPool> predicate,
                                             Comparator<UnitInPool> comparator) {
        Stream<UnitInPool> freeUnits = observationInterface.getUnits(unitInPool ->
                unitInPool.unit().getAlliance() == Alliance.SELF &&
                !unitToTaskMap.containsKey(unitInPool.getTag()) &&
                        predicate.test(unitInPool)).stream();

        if (comparator == null) {
            return freeUnits.findAny().map(unit -> {
                this.reserveUnit(unit.getTag(), task);
                return unit;
            });
        } else {
            return freeUnits.sorted(comparator).findFirst().map(unit -> {
                this.reserveUnit(unit.getTag(), task);
                return unit;
            });
        }
    }

    @Override
    public void onStep(AgentWithData agentWithData) {
        List<Task> tasksFinishedThisStep = new ArrayList<>();
        Set<Tag> unitsReleasedThisStep = new HashSet<>();
        List<Task> tasksForStep = new ArrayList<>(taskSet.values());
        tasksForStep.forEach(task -> {
            if (!task.isComplete()) {
                task.onStep(this, agentWithData);
            } else {
                tasksFinishedThisStep.add(task);
                unitToTaskMap.entrySet().forEach(entry -> {
                   if (entry.getValue() == task) {
                       unitsReleasedThisStep.add(entry.getKey());
                   }
                });
                unitsReleasedThisStep.forEach(tag -> unitToTaskMap.remove(tag));
            }
        });
        tasksFinishedThisStep.forEach(task -> {
            taskSet.remove(task.getKey());
        });
        orderedTasksNeedingUnits = taskSet.values().stream()
                .filter(task -> task instanceof TaskWithUnits) // TODO: this smells...
                .map(task -> (TaskWithUnits)task)
                .sorted(Comparator.comparing(TaskWithUnits::getPriority).reversed()
        ).collect(Collectors.toList());
        if (unitsReleasedThisStep.size() > 0) {
            unitsReleasedThisStep.forEach(tag -> {
                UnitInPool unit = agentWithData.observation().getUnit(tag);
                if (unit != null) {
                    dispatchUnit(unit.unit());
                }
            });
        }
        long gameLoop = agentWithData.observation().getGameLoop();
        if (gameLoop > unassignedUnitsDispatchedAt + 66L) {
            // Periodically assign unallocated units to tasks.
            unassignedUnitsDispatchedAt = gameLoop;
            agentWithData.observation().getUnits(Alliance.SELF).forEach(unitInPool -> {
                if (!unitToTaskMap.containsKey(unitInPool.getTag())) {
                    dispatchUnit(unitInPool.unit());
                }
            });
        }
        if (gameLoop > unitToTaskMapCleanedAt + 22L) {
            unitToTaskMapCleanedAt = gameLoop;
            Set<Tag> missingUnits = new HashSet<>();
            Set<Tag> unitsInPassengers = new HashSet<>();
            unitToTaskMap.forEach((tag, task) -> {
                UnitInPool unitInPool = agentWithData.observation().getUnit(tag);
                if (unitInPool == null) {
                    missingUnits.add(tag);
                } else if (unitInPool.unit().getPassengers().size() > 0) {
                    for (PassengerUnit passengerUnit : unitInPool.unit().getPassengers()) {
                        unitsInPassengers.add(passengerUnit.getTag());
                    }
                }
                // DEBUG
                if (task instanceof TaskWithUnits) {
                    if (!((TaskWithUnits)task).hasUnit(tag) && (unitInPool != null || unitsInPassengers.contains(tag))) {
                        System.out.println("Warning: Task " +task.getKey() + " doesn't think it has unit " + tag);
                        missingUnits.add(tag);
                    }
                }
            });
            missingUnits.removeAll(unitsInPassengers);
            missingUnits.forEach(missingUnitTag -> unitToTaskMap.remove(missingUnitTag));
        }
    }

    @Override
    public boolean addTask(Task task, int maxParallel) {
        int similarCount = this.countSimilarTasks(task);
        //System.out.println(unitTypeForStructure + ": " + similarCount);
        if (similarCount >= maxParallel) {
            return false;
        }
        if (taskSet.containsKey(task.getKey())) {
            return false;
        }
        taskSet.put(task.getKey(), task);
        return true;
    }

    @Override
    public int countSimilarTasks(Task task) {
        return (int)taskSet.values().stream().filter(otherTask -> task.isSimilarTo(otherTask)).count();
    }

    @Override
    public void debug(S2Agent agent) {
        agent.debug().debugTextOut("Tasks (" + taskSet.size() + ")", Point2d.of(0.01f, 0.0f), Color.WHITE, 8);
        final float spacing = 0.0125f;
        float yPosition = 0.01f;
        for (Map.Entry<String, Task> entry : taskSet.entrySet()) {
            Task task = entry.getValue();
            task.debug(agent);
            agent.debug().debugTextOut(task.getDebugText(), Point2d.of(0.01f, yPosition), Color.WHITE, 8);
            yPosition += (spacing);
        }
        for (Map.Entry<Tag, Task> entry : unitToTaskMap.entrySet()) {
            UnitInPool unitInPool = agent.observation().getUnit(entry.getKey());
            if (unitInPool != null) {
                agent.debug().debugSphereOut(unitInPool.unit().getPosition(), 0.5f, Color.YELLOW);
                agent.debug().debugTextOut(entry.getValue().getKey(), unitInPool.unit().getPosition(), Color.WHITE, 8);
            }
        }
    }

    @Override
    public void dispatchUnit(Unit unit) {
        for (TaskWithUnits task : orderedTasksNeedingUnits) {
            if (!task.isComplete() && task.wantsUnit(unit)) {
                task.addUnit(unit);
                reserveUnit(unit.getTag(), task);
                return;
            }
        }
    }

    @Override
    public List<TaskPromise> dispatchMessage(Task task, TaskMessage message) {
        List<TaskPromise> responses = new ArrayList<>();
        taskSet.values().forEach(respondingTask -> {
            if (task != respondingTask) {
                Optional<TaskPromise> response = respondingTask.onTaskMessage(task, message);
                response.ifPresent(promise -> responses.add(promise));
            }
        });
        return responses;
    }

    @Override
    public int reassignUnits(TaskWithUnits from, TaskWithUnits to, ObservationInterface observationInterface, Predicate<Unit> predicate) {
        AtomicInteger moved = new AtomicInteger();
        unitToTaskMap.forEach((tag, task) -> {
            if (task == from) {
                UnitInPool unitInPool = observationInterface.getUnit(tag);
                if (unitInPool == null) {
                    return;
                }
                Unit unit = unitInPool.unit();
                // TODO consider whether we want `wantsUnit`
                if (from.removeUnit(unit) && predicate.test(unit)/* && to.wantsUnit(unit)*/) {
                    to.addUnit(unit);
                    moved.incrementAndGet();
                    unitToTaskMap.put(tag, to);
                }
            }
        });
        return moved.get();
    }

    @Override
    public int totalReservedMinerals() {
        return taskSet.values().stream().mapToInt(Task::reservedMinerals).sum();
    }

    @Override
    public int totalReservedVespene() {
        return taskSet.values().stream().mapToInt(Task::reservedVespene).sum();
    }
}
