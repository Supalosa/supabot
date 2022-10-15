package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TaskManagerImpl implements TaskManager {

    private final Map<Tag, Task> unitToTaskMap;
    private final Map<String, Task> taskSet;
    private List<TaskWithUnits> orderedTasksNeedingUnits;

    private long unitToTaskMapCleanedAt = 0L;

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
    public void onStep(AgentData data, S2Agent agent) {
        List<Task> tasksFinishedThisStep = new ArrayList<>();
        Set<Tag> unitsReleasedThisStep = new HashSet<>();
        taskSet.forEach((key, task) -> {
            if (!task.isComplete()) {
                task.onStep(this, data, agent);
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
                UnitInPool unit = agent.observation().getUnit(tag);
                if (unit != null) {
                    dispatchUnit(unit.unit());
                }
            });
        }
        if (agent.observation().getGameLoop() > unitToTaskMapCleanedAt + 22L) {
            unitToTaskMapCleanedAt = agent.observation().getGameLoop();
            Set<Tag> missingUnits = new HashSet<>();
            unitToTaskMap.keySet().forEach(tag -> {
                if (agent.observation().getUnit(tag) == null) {
                    missingUnits.add(tag);
                }
            });
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
        final float spacing = 0.01f;
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
            }
        }
    }

    @Override
    public void dispatchUnit(Unit unit) {
        for (TaskWithUnits task : orderedTasksNeedingUnits) {
            if (!task.isComplete() && task.wantsUnit(unit)) {
                task.addUnit(unit.getTag());
                reserveUnit(unit.getTag(), task);
                return;
            }
        }
    }
}
