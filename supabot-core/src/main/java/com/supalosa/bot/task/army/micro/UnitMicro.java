package com.supalosa.bot.task.army.micro;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.task.army.BaseArgs;
import com.supalosa.bot.utils.Point2dMap;

import java.util.Optional;

/**
 * Unit-specific implementation of micro.
 */
public interface UnitMicro {

    void run(Unit unit,
             boolean isArrived,
             Optional<Point2d> nextPoint,
             Optional<RegionData> nextRegion,
             Point2dMap<Unit> enemyUnitMap);
}
