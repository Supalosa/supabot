package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import org.immutables.value.Value;

import java.util.List;

@Value.Immutable
public interface Army {
    Point2d position();
    float size();
    double threat();
    List<UnitType> composition();

    /**
     * List of all units assumed to be in this army.
     */
    List<UnitInPool> unitsInPool();
}
