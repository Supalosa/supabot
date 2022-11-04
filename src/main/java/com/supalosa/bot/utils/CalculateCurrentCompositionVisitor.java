package com.supalosa.bot.utils;

import com.github.ocraft.s2client.protocol.data.UnitType;
import com.supalosa.bot.task.TaskWithUnits;

import java.util.*;

/**
 * Visitor that returns the total composition over the given TaskWithUnits, including all child armies.
 */
public class CalculateCurrentCompositionVisitor implements TaskWithUnitsVisitor<Map<UnitType, Integer>> {

    private Set<TaskWithUnits> visited = new HashSet<>();
    private Map<UnitType, Integer> result = new HashMap<>();

    @Override
    public void visit(TaskWithUnits armyTask) {
        if (!visited.contains(armyTask)) {
            armyTask.getCurrentCompositionCache().forEach((unitType, count) -> {
                result.compute(unitType, (k, v) -> v == null ? count : v + count);
            });
        }
    }

    @Override
    public Map<UnitType, Integer> getResult() {
        return Collections.unmodifiableMap(result);
    }
}
