package com.supalosa.bot.utils;

import com.github.ocraft.s2client.protocol.data.UnitType;
import com.supalosa.bot.task.TaskWithUnits;
import com.supalosa.bot.task.army.ArmyTask;

import java.util.Map;

public interface TaskWithUnitsVisitor<T> {
    void visit(TaskWithUnits armyTask);

    T getResult();
}
