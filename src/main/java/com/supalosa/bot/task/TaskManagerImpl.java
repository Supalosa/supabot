package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.supalosa.bot.AgentData;

import java.util.*;
import java.util.function.Predicate;

public class TaskManagerImpl implements TaskManager {

    private final Map<Tag, Task> unitToTaskMap;
    private final Map<String, Task> taskSet;

    public TaskManagerImpl() {
        this.unitToTaskMap = new HashMap<>();
        this.taskSet = new HashMap<>();
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
    public Optional<UnitInPool> findFreeUnit(ObservationInterface observationInterface, Predicate<UnitInPool> predicate) {
        return observationInterface.getUnits(unitInPool ->
                !unitToTaskMap.containsKey(unitInPool.getTag()) &&
                predicate.test(unitInPool)).stream().findAny();
    }

    @Override
    public void onStep(AgentData data, S2Agent agent) {
        List<Task> tasksFinishedThisStep = new ArrayList<>();
        taskSet.forEach((key, task) -> {
            if (!task.isComplete()) {
                task.onStep(this, data, agent);
            } else {
                tasksFinishedThisStep.add(task);
                Set<Tag> toUnset = new HashSet<>();
                unitToTaskMap.entrySet().forEach(entry -> {
                   if (entry.getValue() == task) {
                       toUnset.add(entry.getKey());
                   }
                });
                toUnset.forEach(tag -> unitToTaskMap.remove(tag));
            }
        });
        tasksFinishedThisStep.forEach(task -> {
            taskSet.remove(task.getKey());
        });
        if (agent.observation().getGameLoop() % 1000 == 0) {
            agent.actions().sendChat("TasksActive: " + taskSet.size(), ActionChat.Channel.TEAM);
        }

    }

    @Override
    public boolean addTask(Task task) {
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
        taskSet.forEach((key, task) -> {
           task.debug(agent);
        });
    }
}
