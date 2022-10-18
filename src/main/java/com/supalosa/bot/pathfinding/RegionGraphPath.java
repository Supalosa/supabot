package com.supalosa.bot.pathfinding;

import com.supalosa.bot.analysis.Region;
import org.immutables.value.Value;

import java.util.List;

@Value.Immutable
public interface RegionGraphPath {

    List<Region> getPath();

    double getWeight();
}
