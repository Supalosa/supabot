package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;

import java.util.Optional;

public interface ArmyTask extends Task {
    void setTargetPosition(Optional<Point2d> targetPosition);

    void setRetreatPosition(Optional<Point2d> retreatPosition);

    int getSize();

    boolean addUnit(Tag unitTag);

    boolean hasUnit(Tag unitTag);

    void onUnitIdle(UnitInPool unitTag);
}
