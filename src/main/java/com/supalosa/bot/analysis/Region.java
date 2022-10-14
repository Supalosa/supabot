package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import org.immutables.value.Value;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Value.Immutable
public interface Region {
    int regionId();
    List<Integer> connectedRegions();
    Optional<Integer> getRampId();
    Set<Point2d> getTiles();
}
