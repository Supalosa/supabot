package com.supalosa.bot.task.mission;

import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskWithUnits;
import com.supalosa.bot.task.army.ArmyTask;

import java.util.List;

/**
 * A TaskWithArmy is a type of Task that can be assigned armies.
 */
public interface TaskWithArmy extends TaskWithUnits {

    List<ArmyTask> getAdditionalArmies();

    /**
     * Units that this army wants from other armies.
     */
    List<UnitTypeRequest> requestingUnitTypes();
}
