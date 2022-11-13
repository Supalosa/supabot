package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.protocol.data.UnitType;
import com.supalosa.bot.awareness.Army;

import java.util.List;

public interface ArmyTaskListener {

    void onEngagementStarted(ArmyTask task, Army enemyArmy, FightPerformance predictedResult);

    void onEngagementEnded(ArmyTask task, double powerLost, List<UnitType> unitsLost);
}
