package com.supalosa.bot.utils;

import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.supalosa.bot.task.BuildStructureTask;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskVisitor;
import com.supalosa.bot.task.TaskWithUnits;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Visitor that returns a summary of the unit types in construction.
 */
public class CalculateCurrentConstructionTasksVisitor implements TaskVisitor<Map<Ability, Integer>> {

    private Map<Ability, Integer> result = new HashMap<>();

    @Override
    public void visit(Task task) {
        if (task instanceof BuildStructureTask) {
            result.merge(((BuildStructureTask)task).getAbility(), 1, Integer::sum);
        }
    }

    @Override
    public Map<Ability, Integer> getResult() {
        return Collections.unmodifiableMap(result);
    }
}
