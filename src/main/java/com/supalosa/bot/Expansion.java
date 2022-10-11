package com.supalosa.bot;

import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import org.immutables.value.Value;

import java.util.List;

@Value.Immutable
public interface Expansion {

    Point position();
    List<Point2d> resourcePositions();

    Float distanceToStart();
    Float distanceToOpponent();
}
