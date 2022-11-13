package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.DisplayType;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.ObjectUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class EnemyEconomyAwareness {

    private static final long TOWNHALL_BUILD_TIME = 1590L; // 71 seconds

    private Estimation estimatedEnemyMineralIncome = Estimation.none();
    private Estimation estimatedEnemyBases = Estimation.none();

    private long estimationsUpdatedAt = 0L;
    private static final long ESTIMATION_UPDATE_INTERVAL = 22L * 5;

    private int observedEnemyBasesComplete = 0;
    private Map<Tag, UnitInPool> observedEnemyBasesIncomplete = new HashMap<>();

    public void onStep(AgentWithData agentWithData) {
        long gameLoop = agentWithData.observation().getGameLoop();

        if (gameLoop > estimationsUpdatedAt + ESTIMATION_UPDATE_INTERVAL) {
            estimationsUpdatedAt = gameLoop;
            observedEnemyBasesComplete = agentWithData.observation()
                    .getUnits(UnitFilter.builder().unitTypes(Constants.ALL_TOWN_HALL_TYPES).alliance(Alliance.ENEMY).build())
                    .size();
            // Look for town halls under construction.
            List<UnitInPool> incompleteTownHalls = agentWithData.observation().getUnits(UnitFilter.builder()
                    .unitTypes(Constants.ALL_TOWN_HALL_TYPES)
                    .alliance(Alliance.ENEMY)
                    .filter(unit -> unit.getBuildProgress() < 1f && unit.getDisplayType() == DisplayType.VISIBLE)
                    .build());
            if (incompleteTownHalls.size() > 0) {
                // Look for new town halls and add them to the set.
                incompleteTownHalls.stream()
                        .filter(unitInPool -> !observedEnemyBasesIncomplete.containsKey(unitInPool.getTag()))
                        .forEach(incompleteTownHall -> observedEnemyBasesIncomplete.put(incompleteTownHall.getTag(), incompleteTownHall));
            }
            // Get the latest observation of a unit if possible, and remove completed structures.
            observedEnemyBasesIncomplete = observedEnemyBasesIncomplete.entrySet().stream()
                    .map(entry -> Map.entry(
                            entry.getKey(),
                            ObjectUtils.firstNonNull(agentWithData.observation().getUnit(entry.getKey()), entry.getValue())))
                    .filter(entry -> entry.getValue().unit().getBuildProgress() < 1f)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            // Estimate how many structures are finished.
            int probablyFinishedBases = 0;

        }
    }

    /**
     * Returns the estimated enemy mineral income per minute.
     */
    public Estimation estimatedEnemyMineralIncome() {
        return this.estimatedEnemyMineralIncome;
    }

    /**
     * Returns the estimated number of enemy bases.
     */
    public Estimation estimatedEnemyBases() {
        return this.estimatedEnemyBases;
    }

    public void debug(S2Agent agent) {

    }
}
