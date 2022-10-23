package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.unit.PassengerUnit;
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
import java.util.stream.Stream;

public abstract class DefaultTaskWithUnits extends BaseTask implements TaskWithUnits {

    protected Map<UnitType, Integer> currentComposition = new HashMap<>();
    protected Set<Tag> armyUnits = new HashSet<>();
    protected int basePriority;

    public DefaultTaskWithUnits(int basePriority) {
        this.basePriority = basePriority;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        currentComposition.clear();
        Set<Tag> myPassengers = armyUnits.stream().flatMap(tag -> {
                    UnitInPool unit = agent.observation().getUnit(tag);
                    if (unit != null) {
                        return unit.unit().getPassengers().stream().map(PassengerUnit::getTag);
                    } else {
                        return Stream.empty();
                    }
                }).collect(Collectors.toSet());
        armyUnits = armyUnits.stream().filter(tag -> {
                    UnitInPool unit = agent.observation().getUnit(tag);
                    if (unit != null) {
                        currentComposition.put(
                                unit.unit().getType(),
                                currentComposition.getOrDefault(unit.unit().getType(), 0) + 1);
                        unit.unit().getPassengers().forEach(passengerUnit -> {
                            currentComposition.put(
                                unit.unit().getType(),
                                currentComposition.getOrDefault(passengerUnit.getType(), 0) + 1);
                        });
                    }
                    if (myPassengers.contains(tag)) {
                        return true;
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
    public boolean addUnit(Unit unit) {
        if (armyUnits.add(unit.getTag())) {
            currentComposition.put(
                    unit.getType(),
                    currentComposition.getOrDefault(unit.getType(), 0) + 1);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean removeUnit(Unit unit) {
        if (armyUnits.remove(unit.getTag())) {
            currentComposition.put(
                    unit.getType(),
                    currentComposition.getOrDefault(unit.getType(), 0) - 1);
            return true;
        } else {
            return false;
        }
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
    public void takeAllFrom(TaskManager taskManager, ObservationInterface observationInterface, DefaultTaskWithUnits otherArmy) {
        if (otherArmy == this) {
            return;
        }
        taskManager.reassignUnits(otherArmy, this, observationInterface, _unit -> true);
    }
}
