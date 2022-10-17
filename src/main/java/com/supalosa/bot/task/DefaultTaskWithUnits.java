package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.TaskResult;
import com.supalosa.bot.task.TaskWithUnits;
import com.supalosa.bot.task.army.TerranBioArmyTask;

import java.util.*;
import java.util.stream.Collectors;

public abstract class DefaultTaskWithUnits implements TaskWithUnits {

    protected Map<UnitType, Integer> currentComposition = new HashMap<>();
    protected Set<Tag> armyUnits = new HashSet<>();
    protected int basePriority;

    public DefaultTaskWithUnits(int basePriority) {
        this.basePriority = basePriority;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        currentComposition.clear();
        armyUnits = armyUnits.stream().filter(tag -> {
                    UnitInPool unit = agent.observation().getUnit(tag);
                    if (unit != null) {
                        currentComposition.put(
                                unit.unit().getType(),
                                currentComposition.getOrDefault(unit.unit().getType(), 0) + 1);
                    }
                    return (unit != null && unit.isAlive());
                })
                .collect(Collectors.toSet());
    }

    @Override
    public void debug(S2Agent agent) {

    }

    protected int getAmountOfUnit(UnitType type) {
        return currentComposition.getOrDefault(type, 0);
    }

    /**
     * Default implementation of wantsUnit that looks at the current and desired composition.
     */
    @Override
    public boolean wantsUnit(Unit unit) {
        return requestingUnitTypes().stream().anyMatch(request ->
                request.unitType().equals(unit.getType()) && getAmountOfUnit(unit.getType()) < request.amount());
    }

    @Override
    public boolean addUnit(Tag unitTag) {
        return armyUnits.add(unitTag);
    }

    @Override
    public boolean hasUnit(Tag unitTag) {
        return armyUnits.contains(unitTag);
    }

    @Override
    public int getSize() {
        return armyUnits.size();
    }

    @Override
    public int getPriority() {
        return basePriority;
    }

    /**
     * Take all units from the other task.
     */
    public void takeAllFrom(DefaultTaskWithUnits otherArmy) {
        if (otherArmy == this) {
            return;
        }
        this.armyUnits.addAll(otherArmy.armyUnits);
        otherArmy.armyUnits.clear();
    }
}
