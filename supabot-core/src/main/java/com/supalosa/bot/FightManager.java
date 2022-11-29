package com.supalosa.bot;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.RegionData;
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
     *
     * @param region Region to defend.
     * @param defenceLevel Desired defence level of target position - see DefenceTask for mapping/
     * @param gameCycles Amount of game loops to defend for (value > 0).
     */
    void defendRegionFor(Region region, int defenceLevel, long gameCycles);
}
