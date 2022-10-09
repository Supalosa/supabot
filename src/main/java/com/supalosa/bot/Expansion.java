package com.supalosa.bot;

import com.github.ocraft.s2client.protocol.spatial.Point;
import org.immutables.value.Value;

@Value.Immutable
public interface Expansion {

    Point position();

    Float distanceToStart();
    Float distanceToOpponent();
}
