package com.supalosa.bot.task;

import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.production.UnitTypeRequest;

import java.util.List;
import java.util.Map;

/**
 * A Task that requests units from the TaskManager.
 * Note that the TaskManager is the one who actually tracks the ownership of units.
 */
public interface TaskWithUnits extends Task {

    /**
     * Returns true if this task wants to reserve the given unit. It is called when a unit is trained and passed
     * through every task, in a first-come-first-served basis.
     */
    boolean wantsUnit(Unit unit);

    /**
     * Returns true if this army has the given unit in it.
     */
    boolean hasUnit(Tag unitTag);

    /**
     * Called when a unit is added to the task. Allows for updating internal bookkeeping.
     */
    void onUnitAdded(Unit unitTag);

    /**
     * Called when a unit is removed from the task. Allows for updating internal bookkeeping.
     */
    void onUnitRemoved(Unit unitTag);

    /**
     * Returns an arbitrary value of priority, with higher figures getting units first.
     */
    int getPriority();

    /**
     * Return the desired composition of this army.
     */
    List<UnitTypeRequest> requestingUnitTypes();

    /**
     * Return the current composition of this army.
     */
    Map<UnitType, Integer> getCurrentCompositionCache();

    /**
     * Returns the number of units in this army.
     */
    int getSize();

    /**
     * Orders the unit to remove a unit of a certain type if possible, on the next update.
     */
    void removeUnitOfType(UnitType type);
}
