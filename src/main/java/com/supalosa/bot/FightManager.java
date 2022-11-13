package com.supalosa.bot;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.army.ArmyTask;

import java.util.Collection;
import java.util.List;

public interface FightManager {

    void onStep(TaskManager taskManager, AgentWithData agentWithData);

    List<Point2d> getCloakedOrBurrowedUnitClusters();

    boolean hasSeenCloakedOrBurrowedUnits();

    void onUnitIdle(UnitInPool unit);

    void debug(S2Agent agent);

    List<UnitTypeRequest> getRequestedUnitTypes();

    Collection<ArmyTask> getAllArmies();

    void setCanAttack(boolean canAttack);

    void reinforceAttackingArmy();

    /**
     * Orders the bot to defend for one fight.
     */
    void setDefensiveStance();
}
