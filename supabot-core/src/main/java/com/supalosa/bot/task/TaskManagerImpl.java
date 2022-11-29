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
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.task.army.ArmyTask;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.task.mission.MissionTask;
import com.supalosa.bot.task.mission.TaskWithArmy;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TaskManagerImpl implements TaskManager {

    private final Map<Tag, Task> unitToTaskMap;
    private final Map<String, Task> taskSet;
    private List<TaskWithUnits> orderedTasksNeedingUnits;
    private Map<ArmyTask, Optional<MissionTask>> armyTaskToMissionMap;

    // Cache of task -> units. The task manager is the authoritative source for this.
    private Multimap<Task, Tag> taskToUnitCache;

    private long unitToTaskMapCleanedAt = 0L;
    private long unassignedUnitsDispatchedAt = 0L;

    public TaskManagerImpl() {
        this.unitToTaskMap = new HashMap<>();
        this.taskSet = new HashMap<>();
        this.orderedTasksNeedingUnits = new ArrayList<>();
        this.armyTaskToMissionMap = new HashMap<>();
        this.taskToUnitCache = HashMultimap.create();
    }

    @Override
    public Optional<Task> getTaskForUnit(Unit unit) {
        return Optional.ofNullable(unitToTaskMap.get(unit));
    }

    @Override
    public void reserveUnit(Unit unit, Task task) {
        unitToTaskMap.put(unit.getTag(), task);
        taskToUnitCache.put(task, unit.getTag());
        if (task instanceof TaskWithUnits) {
            ((TaskWithUnits)task).onUnitAdded(unit);
        }
    }

    @Override
    public void reserveUnit(Optional<Unit> unit, Task task) {
        if (unit.isPresent()) {
            reserveUnit(unit.get(), task);
        }
    }

    @Override
    public void releaseUnit(Tag unitTag, Task task) {
        if (unitToTaskMap.get(unitTag) == task) {
            unitToTaskMap.remove(unitTag);
            taskToUnitCache.remove(task, unitTag);
        }
    }

    @Override
    public void reserveArmy(MissionTask missionTask, ArmyTask armyTask) {
        armyTaskToMissionMap.getOrDefault(armyTask, Optional.empty()).ifPresent(task -> task.onArmyRemoved(armyTask));
        armyTaskToMissionMap.put(armyTask, Optional.of(missionTask));
    }

    @Override
    public Collection<Tag> getAssignedUnitsForTask(TaskWithUnits task) {
        return taskToUnitCache.get(task);
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
                this.reserveUnit(unit.unit(), task);
                return unit;
            });
        } else {
            return freeUnits.sorted(comparator).findFirst().map(unit -> {
                this.reserveUnit(unit.unit(), task);
                return unit;
            });
        }
    }

    @Override
    public Optional<ArmyTask> findFreeArmyForTask(MissionTask missionTask, Predicate<ArmyTask> predicate) {
        return findFreeArmyForTask(missionTask,
                predicate.and(task -> task.getCentreOfMass().isPresent()),
                Comparator.comparingDouble(task -> missionTask.getLocation().centrePoint().distance(task.getCentreOfMass().get())));
    }

    @Override
    public Optional<ArmyTask> findFreeArmyForTask(MissionTask missionTask, Predicate<ArmyTask> predicate,
                                                  Comparator<ArmyTask> comparator) {
        Optional<ArmyTask> candidate = armyTaskToMissionMap.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .filter(predicate)
                .sorted((e1, e2) -> comparator.compare(e1, e2))
                .findFirst();
        if (candidate.isPresent()) {
            armyTaskToMissionMap.put(candidate.get(), Optional.ofNullable(missionTask));
        }
        return candidate;
    }

    @Override
    public final void onStep(AgentWithData agentWithData) {
        // Create the task:unit cache.
        taskToUnitCache = HashMultimap.create(taskSet.size(), unitToTaskMap.size() / Math.max(1, taskSet.size()));
        unitToTaskMap.forEach((unit, task) -> {
            taskToUnitCache.put(task, unit);
        });
        // Run task logic, and clean up completed tasks.
        List<Task> tasksFinishedThisStep = new ArrayList<>();
        Set<Tag> unitsReleasedThisStep = new HashSet<>();
        List<Task> tasksForStep = new ArrayList<>(taskSet.values());
        tasksForStep.forEach(task -> {
            if (!task.isComplete()) {
                task.onStep(this, agentWithData);
            } else {
                tasksFinishedThisStep.add(task);
                Collection<Tag> unitsForTask = taskToUnitCache.get(task);
                unitsReleasedThisStep.addAll(unitsForTask);
                taskToUnitCache.removeAll(task);
            }
        });
        // Unassign units from tasks that were completed.
        unitsReleasedThisStep.forEach(unitToTaskMap::remove);
        tasksFinishedThisStep.forEach(task -> {
            taskSet.remove(task.getKey());
            armyTaskToMissionMap.remove(task);
        });
        // Reassign units that were assigned to tasks that don't exist anymore.
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
            // Every ~3 seconds, assign unallocated units to tasks.
            unassignedUnitsDispatchedAt = gameLoop;
            agentWithData.observation().getUnits(Alliance.SELF).forEach(unitInPool -> {
                if (!unitToTaskMap.containsKey(unitInPool.getTag())) {
                    dispatchUnit(unitInPool.unit());
                }
            });
        }
        // Remove assignments to tasks that are complete.
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
            missingUnits.forEach(unitToTaskMap::remove);
        }
        // Remove assignments to missions that are complete.
        // This can be done every tick because we don't expect lots of missions.
        armyTaskToMissionMap.forEach((armyTask, missionTask) -> {
           if (missionTask.isPresent() && missionTask.get().isComplete()) {
               armyTaskToMissionMap.put(armyTask, Optional.empty());
           }
        });
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
        if (task instanceof ArmyTask) {
            armyTaskToMissionMap.put((ArmyTask)task, Optional.empty());
        }
        return true;
    }

    @Override
    public int countSimilarTasks(Task task) {
        return (int)taskSet.values().stream().filter(otherTask -> task.isSimilarTo(otherTask)).count();
    }

    @Override
    public void debug(S2Agent agent) {
        float yPosition = 0.01f;
        final float spacing = 0.0125f;
        agent.debug().debugTextOut("ResMin: " + totalReservedMinerals() + ", ResVes: " + totalReservedVespene(),
                Point2d.of(0.01f, yPosition), Color.WHITE, 8);
        yPosition += spacing;
        agent.debug().debugTextOut("Tasks (" + taskSet.size() + ")", Point2d.of(0.01f, yPosition), Color.WHITE, 8);
        yPosition += spacing;
        for (Map.Entry<String, Task> entry : taskSet.entrySet()) {
            Task task = entry.getValue();
            task.debug(agent);
            if (yPosition < 1.0f) {
                agent.debug().debugTextOut(task.getDebugText(), Point2d.of(0.01f, yPosition), Color.WHITE, 8);
            }
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
                reserveUnit(unit, task);
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
                if (predicate.test(unit)/* && to.wantsUnit(unit)*/) {
                    moved.incrementAndGet();
                    unitToTaskMap.put(tag, to);
                    taskToUnitCache.remove(from, tag);
                    from.onUnitRemoved(unit);
                    taskToUnitCache.put(to, tag);
                    to.onUnitAdded(unit);
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

    @Override
    public boolean hasTask(Task task) {
        return taskSet.containsKey(task.getKey());
    }

    @Override
    public long countTasks(Predicate<Task> filter) {
        return taskSet.values().stream().filter(filter).count();
    }

    @Override
    public <T> T visitTasks(TaskVisitor<T> visitor) {
        taskSet.values().forEach(task -> visitor.visit(task));
        return visitor.getResult();
    }
}
