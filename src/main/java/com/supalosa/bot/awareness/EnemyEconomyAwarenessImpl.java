package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.DisplayType;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.ObjectUtils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class EnemyEconomyAwarenessImpl implements EnemyEconomyAwareness {

    private static final long TOWNHALL_BUILD_TIME = 1590L; // 71 seconds

    // 0-180 seconds - confident the enemy has expanded.
    private static final long HIGH_CERTAINTY_SCOUTING = 180 * 22L;

    // 180-360 seconds - low confidence the enemy hasn't expanded.
    private static final long LOW_CERTAINTY_SCOUTING = 360 * 22L;

    // 180+ seconds
    //private static final long NO_CERTAINTY_SCOUTING = 300 * 22L;

    private Estimation estimatedEnemyMineralIncome = Estimation.none();
    private Estimation estimatedEnemyBases = Estimation.highConfidence(1);

    private long estimationsUpdatedAt = 0L;
    private static final long ESTIMATION_UPDATE_INTERVAL = 22L;

    private int observedEnemyBasesComplete = 0;
    private Map<Tag, UnitInPool> observedEnemyBasesIncomplete = new HashMap<>();
    private Optional<RegionData> leastConfidentEnemyExpansion = Optional.empty();

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
            EstimationConfidence confidence = updateScoutingConfidence(agentWithData);
            estimatedEnemyBases = ImmutableEstimation.builder()
                    .estimation(observedEnemyBasesComplete + (int)probablyFinishedBases)
                    .confidence(confidence)
                    .build();
        }
    }
    @Override
    public Estimation estimatedEnemyMineralIncome() {
        return this.estimatedEnemyMineralIncome;
    }

    @Override
    public Estimation estimatedEnemyBases() {
        return this.estimatedEnemyBases;
    }

    @Override
    public Optional<RegionData> getLeastConfidentEnemyExpansion() {
        return this.leastConfidentEnemyExpansion;
    }

    /**
     * Calculate the confidence that we've actually seen the enemy's expansions.
     */
    EstimationConfidence updateScoutingConfidence(AgentWithData agentWithData) {
        MapAwareness mapAwareness = agentWithData.mapAwareness();
        Map<Integer, Long> possibleExpansionToLastScoutedTime = new HashMap<>();
        mapAwareness.getExpansionLocations().ifPresent(expansionLocations -> {
            expansionLocations.forEach(expansion -> {
               Optional<RegionData> maybeRegionData = mapAwareness.getRegionDataForPoint(expansion.position());
                maybeRegionData.ifPresent(regionData -> {
                    // If we know the enemy has a base here, skip it.
                    if (regionData.hasEnemyBase()) {
                        return;
                    }
                    // If we don't control the region, the enemy might have a base here.
                    if (!regionData.isPlayerControlled()) {
                        possibleExpansionToLastScoutedTime.put(regionData.region().regionId(), regionData.lastScoutedAtGameLoop());
                    }
                });
            });
        });
        Optional<Integer> leastConfident = possibleExpansionToLastScoutedTime.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue()))
                .map(Map.Entry::getKey);

        this.leastConfidentEnemyExpansion = leastConfident
                .flatMap(regionId -> mapAwareness.getRegionDataForId(regionId));

        if (leastConfident.isEmpty()) {
            return EstimationConfidence.HIGH;
        } else {
            // Get the max unscouted time for places the enemy bases might be.
            long maxUnscoutedTime = agentWithData.observation().getGameLoop() - leastConfident
                    .map(possibleExpansionToLastScoutedTime::get).orElse(0L);
            if (maxUnscoutedTime > LOW_CERTAINTY_SCOUTING) {
                return EstimationConfidence.NONE;
            } else if (maxUnscoutedTime > HIGH_CERTAINTY_SCOUTING) {
                return EstimationConfidence.LOW;
            } else {
                return EstimationConfidence.HIGH;
            }
        }
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
    }
}
