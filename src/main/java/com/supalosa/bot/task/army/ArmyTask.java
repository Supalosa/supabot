package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.task.TaskWithUnits;

import java.util.List;
import java.util.Optional;

public interface ArmyTask extends TaskWithUnits {

    /**
     * Set which one of the {@code PathRules} the army will take to its path.
     */
    void setPathRules(MapAwareness.PathRules pathRules);

    /**
     * Sets the target position that this army is trying to move towards.
     * Note: the army may not accept the order.
     */
    void setTargetPosition(Optional<Point2d> targetPosition);

    /**
     * Sets the position that this army should try to retreat towards, if its micro state allows it.
     * Note: the army may not accept the order.
     */
    void setRetreatPosition(Optional<Point2d> retreatPosition);

    /**
     * Call this when a given unit (which is part of the army) goes idle.
     */
    void onUnitIdle(UnitInPool unitTag);

    /**
     * Returns a list of Regions that this army wants to move through (based on the targetPosition), or empty
     * if no path is calculated.
     */
    Optional<List<Region>> getWaypoints();

    /**
     * Returns the centre of mass of this army.
     */
    Optional<Point2d> getCentreOfMass();

    /**
     * Returns the dispersion of the army (root-mean-squared-distance).
     */
    Optional<Double> getDispersion();

    /**
     * Returns the position that this army is trying to move to.
     */
    Optional<Point2d> getTargetPosition();

    /**
     * Predict how fight against a certain army will go.
     */
    FightPerformance predictFightAgainst(Army army);

    /**
     * Estimated power of this army.
     */
    double getPower();
}
