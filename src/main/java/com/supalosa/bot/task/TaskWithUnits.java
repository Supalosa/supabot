package com.supalosa.bot.task;

import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.analysis.production.UnitTypeRequest;

import java.util.List;

/**
 * A Task that requests units from the TaskManager.
 */
public interface TaskWithUnits extends Task {

    /**
     * Returns true if this task wants to reserve the given unit. It is called when a unit is trained and passed
     * through every task, in a first-come-first-served basis.
     */
    boolean wantsUnit(Unit unit);

    /**
     * Adds a unit to this army. It is assumed to already be reserved for the army through the TaskManger.
     * Returns false if the unit is already in the army.
     * Note: If a unit is in multiple armies, both armies will end up controlling it.
     */
    boolean addUnit(Tag unitTag);

    /**
     * Returns true if this army has the given unit in it.
     */
    boolean hasUnit(Tag unitTag);

    /**
     * Removes the unit (if we have it) and returns true if it was removed.
     */
    boolean removeUnit(Tag unitTag);

    /**
     * Returns an arbitrary value of priority, with higher figures getting units first.
     */
    int getPriority();

    /**
     * Return the desired composition of this army.
     */
    List<UnitTypeRequest> requestingUnitTypes();

    /**
     * Returns the number of units in this army.
     */
    int getSize();
}
