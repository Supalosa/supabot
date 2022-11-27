package com.supalosa.bot.utils;

import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.supalosa.bot.builds.Build;
import com.supalosa.bot.task.BuildStructureTask;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskVisitor;
import com.supalosa.bot.task.TaskWithUnits;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Visitor that returns a summary of the unit types in construction.
 */
public class CalculateCurrentConstructionTasksVisitor implements TaskVisitor<Map<Ability, Integer>> {

    private Map<Ability, Integer> result = new HashMap<>();
    private final Predicate<BuildStructureTask> predicate;


    public CalculateCurrentConstructionTasksVisitor() {
        this.predicate = (_task) -> true;
    }

    public CalculateCurrentConstructionTasksVisitor(Predicate<BuildStructureTask> predicate) {
        this.predicate = predicate;
    }

    @Override
    public void visit(Task task) {
        if (task instanceof BuildStructureTask && this.predicate.test((BuildStructureTask)task)) {
            result.merge(((BuildStructureTask)task).getAbility(), 1, Integer::sum);
        }
    }

    @Override
    public Map<Ability, Integer> getResult() {
        return Collections.unmodifiableMap(result);
    }
}
