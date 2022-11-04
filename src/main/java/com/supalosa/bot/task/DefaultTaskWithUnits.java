package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.utils.TaskWithUnitsVisitor;

import java.util.*;

public abstract class DefaultTaskWithUnits extends BaseTask implements TaskWithUnits {

    protected Map<UnitType, Integer> currentCompositionCache = new HashMap<>();
    protected int basePriority;
    protected Collection<Tag> assignedUnits;

    private final List<UnitType> unitsOfTypeToRemove;

    public DefaultTaskWithUnits(int basePriority) {
        super();
        this.basePriority = basePriority;
        this.assignedUnits = Collections.emptyList();
        this.unitsOfTypeToRemove = new ArrayList<>();
    }

    @Override
    public final void removeUnitOfType(UnitType unitType) {
        this.unitsOfTypeToRemove.add(unitType);
    }

    protected abstract void onStepImpl(TaskManager taskManager, AgentWithData agentWithData);

    @Override
    public final void onStep(TaskManager taskManager, AgentWithData agentWithData) {
        assignedUnits = taskManager.getAssignedUnitsForTask(this);
        currentCompositionCache.clear();
        Set<Tag> unitsToRemove = new HashSet<>();
        assignedUnits.forEach(tag -> {
            UnitInPool unit = agentWithData.observation().getUnit(tag);
            if (unit != null) {
                if (unitsOfTypeToRemove.size() > 0 && unitsOfTypeToRemove.contains(unit.unit().getType())) {
                    unitsOfTypeToRemove.remove(unit.unit().getType());
                    unitsToRemove.add(unit.getTag());
                } else {
                    currentCompositionCache.compute(unit.unit().getType(), (k, v) -> v == null ? 1 : v + 1);
                    unit.unit().getPassengers().forEach(passengerUnit -> {
                        currentCompositionCache.compute(passengerUnit.getType(), (k, v) -> v == null ? 1 : v + 1);
                    });
                }
            }
        });
        if (unitsToRemove.size() > 0) {
            unitsToRemove.forEach(tag -> taskManager.releaseUnit(tag, this));
            assignedUnits = getAssignedUnits();
        }
        onStepImpl(taskManager, agentWithData);
    }

    @Override
    public void debug(S2Agent agent) {

    }

    protected int getAmountOfUnit(UnitType type) {
        return currentCompositionCache.getOrDefault(type, 0);
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
    public boolean hasUnit(Tag unitTag) {
        return getAssignedUnits().contains(unitTag);
    }

    @Override
    public int getSize() {
        return getAssignedUnits().size();
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

    @Override
    public void accept(TaskWithUnitsVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public Map<UnitType, Integer> getCurrentCompositionCache() {
        return Collections.unmodifiableMap(this.currentCompositionCache);
    }

    /**
     * Returns the Tags of the units assigned to this task.
     */
    protected final Collection<Tag> getAssignedUnits() {
        return this.assignedUnits;
    }

    @Override
    public final void onUnitAdded(Unit unitTag) {
        this.currentCompositionCache.compute(unitTag.getType(), (k, v) -> v == null ? 1 : v + 1);
    }

    @Override
    public final void onUnitRemoved(Unit unitTag) {
        this.currentCompositionCache.compute(unitTag.getType(), (k, v) -> v == null ? 0 : v - 1);
    }
}
