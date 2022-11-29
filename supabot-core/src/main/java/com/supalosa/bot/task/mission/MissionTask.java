package com.supalosa.bot.task.mission;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.task.army.ArmyTask;

/**
 * A TaskWithArmy that has a regional target and priority.
 */
public interface MissionTask extends TaskWithArmy {

    Region getLocation();

    void setLocation(Region location);

    void endMission();

    void onArmyRemoved(ArmyTask armyTask);
}
