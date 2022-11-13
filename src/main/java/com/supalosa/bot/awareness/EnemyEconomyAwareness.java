package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.DisplayType;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.builds.BuildOrderOutput;
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
    private static final long ESTIMATION_UPDATE_INTERVAL = 22L;

    private int observedEnemyBasesComplete = 0;
    private Map<Tag, UnitInPool> observedEnemyBasesIncomplete = new HashMap<>();

    public void onStep(AgentWithData agentWithData) {
        final long gameLoop = agentWithData.observation().getGameLoop();

        if (gameLoop > estimationsUpdatedAt + ESTIMATION_UPDATE_INTERVAL) {
            estimationsUpdatedAt = gameLoop;
            observedEnemyBasesComplete = agentWithData.observation()
                    .getUnits(UnitFilter.builder().unitTypes(Constants.ALL_TOWN_HALL_TYPES).alliance(Alliance.ENEMY).build())
                    .size();
            // Look for town halls under construction.
            List<UnitInPool> incompleteTownHalls = agentWithData.observation().getUnits(UnitFilter.builder()
                    .unitTypes(Constants.ALL_TOWN_HALL_TYPES)
                    .alliance(Alliance.ENEMY)
                    .includeIncomplete(true)
                    .filter(unit -> unit.getBuildProgress() < 1f && unit.getDisplayType() == DisplayType.VISIBLE)
                    .build());
            if (incompleteTownHalls.size() > 0) {
                // Look for new town halls and add them to the set.
                incompleteTownHalls.stream()
                        .filter(unitInPool -> !observedEnemyBasesIncomplete.containsKey(unitInPool.getTag()))
                        .forEach(incompleteTownHall -> observedEnemyBasesIncomplete.put(incompleteTownHall.getTag(), incompleteTownHall));
            }
            // Get the latest observation of a unit if possible, and remove completed structures.
            // Also remove incomplete bases where we can't see the unit at that position anymore.
            observedEnemyBasesIncomplete = observedEnemyBasesIncomplete.entrySet().stream()
                    .map(entry -> Map.entry(
                            entry.getKey(),
                            ObjectUtils.firstNonNull(agentWithData.observation().getUnit(entry.getKey()), entry.getValue())))
                    .filter(entry -> entry.getValue().unit().getBuildProgress() < 1f)
                    .filter(entry ->
                            // Ensure the position is either not visible, or the structure is still there.
                            agentWithData.observation().getVisibility(entry.getValue().unit().getPosition().toPoint2d()) != Visibility.VISIBLE ||
                            agentWithData.gameData().getEnemyStructureMap().getNearestInRadius(
                                    entry.getValue().unit().getPosition().toPoint2d(), 1f,
                                    structure -> structure.getTag().equals(entry.getKey())).isPresent())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            // Estimate how many structures are finished since we last saw them.
            long probablyFinishedBases = observedEnemyBasesIncomplete.values().stream()
                    .filter(unit -> {
                        long lastObservedProgressSteps = (long)(TOWNHALL_BUILD_TIME * unit.unit().getBuildProgress());
                        long elapsedTime = gameLoop - unit.getLastSeenGameLoop();
                        long estimatedProgressSteps = lastObservedProgressSteps + elapsedTime;
                        float estimatedProgressPct = estimatedProgressSteps / (float)TOWNHALL_BUILD_TIME;
                        return estimatedProgressPct >= 1f;
                    }).count();
            estimatedEnemyBases = Estimation.highConfidence(observedEnemyBasesComplete + (int)probablyFinishedBases);
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
        float xPosition = 0.75f;
        agent.debug().debugTextOut(
                "Economy Estimates",
                Point2d.of(xPosition, 0.2f), Color.WHITE, 8);
        final float spacing = 0.0125f;
        float yPosition = 0.21f;
        agent.debug().debugTextOut("Bases: " + this.estimatedEnemyBases, Point2d.of(xPosition, yPosition), Color.WHITE, 8);
        yPosition += (spacing);
        agent.debug().debugTextOut("Incomplete Bases: " + this.observedEnemyBasesIncomplete.size(), Point2d.of(xPosition, yPosition), Color.WHITE, 8);
        yPosition += (spacing);
        agent.debug().debugTextOut("Income: " + this.estimatedEnemyMineralIncome, Point2d.of(xPosition, yPosition), Color.WHITE, 8);
        yPosition += (spacing);
    }
}
