package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Expansion;
import com.supalosa.bot.SupaBot;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Region;

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
        AVOID_KILL_ZONE
    }

    Optional<RegionData> getRegionDataForPoint(Point2d point);

    Optional<List<Region>> generatePath(Region startRegion, Region endRegion, PathRules rules);

    Collection<RegionData> getAllRegionData();

    void setStartPosition(Point2d startPosition);

    Optional<Point2d> getStartPosition();

    List<Point2d> getKnownEnemyBases();

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
    Optional<Point2d> getMaybeEnemyPositionNearEnemy();

    /**
     * Returns a proposed position of an enemy near our own 'base' (starting location), if applicable.
     */
    Optional<Point2d> getMaybeEnemyPositionNearBase();

    /**
     * Returns true if we should defend a given location.
     * @TODO this should be moved to a 'decision maker' class rather than an 'awareness' class.
     */
    boolean shouldDefendLocation(Point2d location);

    /**
     * Returns the potential location of the enemy army.
     * If there are multiple armies, it is the biggest one we see.
     */
    Optional<Army> getMaybeEnemyArmy();

    void debug(S2Agent supaBot);

    Optional<Point2d> getNextScoutTarget();

    Optional<Float> getObservedCreepCoverage();

    void setMapAnalysisResults(AnalysisResults mapAnalysis);
}
