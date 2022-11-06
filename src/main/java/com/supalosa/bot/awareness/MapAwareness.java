package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Expansion;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.pathfinding.RegionGraph;
import com.supalosa.bot.pathfinding.RegionGraphPath;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MapAwareness {

    enum PathRules {
        /**
         * Path directly to enemy base.
         */
        NORMAL,
        /**
         * Path by avoiding the enemy army.
         */
        AVOID_ENEMY_ARMY,
        /**
         * Path by avoiding 'killzones' (low ground, siege tanks, liberators)
         */
        AVOID_KILL_ZONE,

        /**
         * Air units can path more freely, but if you want to avoid enemy army, use this.
         */
        AIR_AVOID_ENEMY_ARMY
    }

    Optional<RegionData> getRegionDataForPoint(Point2d point);
    Optional<RegionData> getRegionDataForId(int regionId);

    Optional<RegionGraphPath> generatePath(Region startRegion, Region endRegion, PathRules rules);
    Optional<RegionGraph> getPathingGraph(PathRules rules);

    Collection<RegionData> getAllRegionData();

    void setStartPosition(Point2d startPosition);

    List<Point2d> getKnownEnemyBases();

    Optional<RegionData> getRandomPlayerBaseRegion();

    /**
     * Returns a list of all expansion locations if applicable.
     *
     * @return List of all expansions on the map, or empty if not calculated or empty.
     */
    Optional<List<Expansion>> getExpansionLocations();

    /**
     * Returns a list of viable expansion locations if applicable.
     *
     * @return List of expansions we should try expanding to, or empty if not calculated or empty.
     */
    Optional<List<Expansion>> getValidExpansionLocations();

    /**
     * Call this when you attempt to expand to a given location.
     *
     * @param expansion Expansion that was attempted.
     * @param whenGameLoop Game loop# of now.
     */
    void onExpansionAttempted(Expansion expansion, long whenGameLoop);

    void onStep(AgentData data, S2Agent agent);

    /**
     * Returns a proposed position near the enemy (or, if none is found, starts scouting for a location).
     */
    Optional<Point2d> getMaybeEnemyPositionNearEnemyBase();

    /**
     * Returns a proposed position of an enemy near our own 'base' (starting location), if applicable.
     */
    Optional<Point2d> getMaybeEnemyPositionNearOwnBase();

    /**
     * Returns true if we should defend a given location.
     * @TODO this should be moved to a 'decision maker' class rather than an 'awareness' class.
     */
    boolean shouldDefendLocation(Point2d location);

    Optional<Point2d> findEnemyPositionNearPoint(ObservationInterface observationInterface, Point2d point);

    void debug(S2Agent supaBot);

    Optional<Point2d> getNextScoutTarget();

    Optional<Float> getObservedCreepCoverage();

    void setMapAnalysisResults(AnalysisResults mapAnalysis);

    /**
     * Returns the point that we should rally our units and defend from.
     */
    Optional<Point2d> getDefenceLocation();

    Optional<RegionData> getMainBaseRegion();
}
