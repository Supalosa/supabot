package com.supalosa.bot.utils;

import com.github.ocraft.s2client.protocol.data.UnitType;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskVisitor;
import com.supalosa.bot.task.TaskWithUnits;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Visitor that returns the total composition over the given TaskWithUnits, including all child armies.
 */
public class CalculateCurrentCompositionVisitor implements TaskVisitor<Map<UnitType, Integer>> {

    private Set<TaskWithUnits> visited = new HashSet<>();
    private Map<UnitType, Integer> result = new HashMap<>();

    @Override
    public void visit(Task armyTask) {
        if (armyTask instanceof TaskWithUnits && !visited.contains(armyTask)) {
            ((TaskWithUnits)armyTask).getCurrentCompositionCache().forEach((unitType, count) -> {
                result.compute(unitType, (k, v) -> v == null ? count : v + count);
            });
        }
    }

    @Override
    public Map<UnitType, Integer> getResult() {
        return Collections.unmodifiableMap(result);
    }
}
