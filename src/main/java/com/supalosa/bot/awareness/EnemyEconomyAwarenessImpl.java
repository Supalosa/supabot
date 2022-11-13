package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.DisplayType;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.Expansion;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.ObjectUtils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class EnemyEconomyAwarenessImpl implements EnemyEconomyAwareness {

    private static final long TOWNHALL_BUILD_TIME = 1590L; // 71 seconds

    // 0-180 seconds - confident the enemy has expanded.
    private static final long HIGH_CERTAINTY_SCOUTING = 180 * 22L;

    // 180-360 seconds - low confidence the enemy hasn't expanded.
    private static final long LOW_CERTAINTY_SCOUTING = 360 * 22L;

    // 180+ seconds
    //private static final long NO_CERTAINTY_SCOUTING = 300 * 22L;

    private static final int ESTIMATED_INCOME_PER_MINERAL_PATCH_PER_MINUTE = 102;

    private Estimation estimatedEnemyMineralIncome = Estimation.none();
    private Estimation estimatedEnemyBases = Estimation.highConfidence(1);

    private long estimationsUpdatedAt = 0L;
    private static final long ESTIMATION_UPDATE_INTERVAL = 22L;

    private int numObservedEnemyBasesComplete = 0;
    private Map<Tag, UnitInPool> observedEnemyBasesIncomplete = new HashMap<>();
    private Set<Expansion> enemyExpansions = new HashSet<>();
    private Optional<RegionData> leastConfidentEnemyExpansion = Optional.empty();

    public void onStep(AgentWithData agentWithData) {
        final long gameLoop = agentWithData.observation().getGameLoop();

        if (gameLoop > estimationsUpdatedAt + ESTIMATION_UPDATE_INTERVAL) {
            estimationsUpdatedAt = gameLoop;
            numObservedEnemyBasesComplete = agentWithData.observation()
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
                    .filter(isExpansionFinished(gameLoop)).count();
            EstimationConfidence confidence = updateScoutingInfoAndGetConfidence(agentWithData);
            int numEstimatedBasesComplete = numObservedEnemyBasesComplete + (int)probablyFinishedBases;
            estimatedEnemyBases = ImmutableEstimation.builder()
                    .estimation(numEstimatedBasesComplete)
                    .confidence(confidence)
                    .build();
            // This is a placeholder implementation.
            // Each patch is worth about 102 minerals per minute.
            // A regular expansion with 24 workers will mine approx 816 minerals per minute.
            int estimatedMineralIncome = estimateMineralIncome(agentWithData);
            estimatedEnemyMineralIncome = ImmutableEstimation.builder()
                    .estimation(estimatedMineralIncome)
                    .confidence(estimatedEnemyBases.confidence())
                    .build();
        }
    }

    /**
     * Predicate to check if a given UnitInPool (which is assumed to be a townhall), is finished, given the current
     * game time.
     */
    private Predicate<UnitInPool> isExpansionFinished(long gameLoop) {
        return unit -> {
            long lastObservedProgressSteps = (long)(TOWNHALL_BUILD_TIME * unit.unit().getBuildProgress());
            long elapsedTime = gameLoop - unit.getLastSeenGameLoop();
            long estimatedProgressSteps = lastObservedProgressSteps + elapsedTime;
            float estimatedProgressPct = estimatedProgressSteps / (float)TOWNHALL_BUILD_TIME;
            return estimatedProgressPct >= 1f;
        };
    }

    private int estimateMineralIncome(AgentWithData agentWithData) {
        // This is a naive implementation that doesn't take into account mineral exhaustion and worker counts.
        List<UnitInPool> mineralPatches = agentWithData.observation().getUnits(UnitFilter.builder()
                .alliance(Alliance.NEUTRAL)
                .unitTypes(Constants.MINERAL_TYPES)
                .build());
        int mineralPatchesRemaining = (int)mineralPatches.stream().filter(mineralPatch -> enemyExpansions.stream()
                .flatMap(expansion -> expansion.resourcePositions().stream())
                .anyMatch(resourcePosition -> resourcePosition.equals(mineralPatch.unit().getPosition().toPoint2d()))).count();

        return mineralPatchesRemaining * ESTIMATED_INCOME_PER_MINERAL_PATCH_PER_MINUTE;
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
     * Also update some metadata around the enemy's expansions.
     */
    EstimationConfidence updateScoutingInfoAndGetConfidence(AgentWithData agentWithData) {
        final long gameLoop = agentWithData.observation().getGameLoop();
        MapAwareness mapAwareness = agentWithData.mapAwareness();
        Map<Integer, Long> possibleExpansionToLastScoutedTime = new HashMap<>();
        enemyExpansions.clear();
        mapAwareness.getExpansionLocations().ifPresent(expansionLocations -> {
            expansionLocations.forEach(expansion -> {
               Optional<RegionData> maybeRegionData = mapAwareness.getRegionDataForPoint(expansion.position());
                maybeRegionData.ifPresent(regionData -> {
                    // If we know the enemy has a base here, skip it.
                    if (regionData.hasEnemyBase()) {
                        // Also add it to the known expansion list.
                        enemyExpansions.add(expansion);
                        return;
                    }
                    // If we know the enemy already started building a base here, skip it.
                    Optional<UnitInPool> maybeEnemyBaseUnderConstruction = observedEnemyBasesIncomplete.values().stream()
                            .filter(unitInPool -> unitInPool.unit().getPosition().toPoint2d().equals(expansion.position())).findFirst();
                    if (maybeEnemyBaseUnderConstruction.isPresent()) {
                        if (maybeEnemyBaseUnderConstruction.filter(isExpansionFinished(gameLoop)).isPresent()) {
                            enemyExpansions.add(expansion);
                        }
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
        agent.debug().debugTextOut("Their Income: " + this.estimatedEnemyMineralIncome, Point2d.of(xPosition, yPosition), Color.WHITE, 8);
        yPosition += (spacing);
        agent.debug().debugTextOut("Our Income: " + agent.observation().getScore().getDetails().getCollectionRateMinerals(), Point2d.of(xPosition, yPosition), Color.WHITE, 8);
    }
}
