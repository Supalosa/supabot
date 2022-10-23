package com.supalosa.bot.builds;

import com.github.ocraft.s2client.protocol.data.Units;
import org.immutables.value.Value;

import java.util.Map;

/**
 * A checkpoint in the build order that must be satisfied before moving to the next stage.
 */
@Value.Immutable
public interface SimpleBuildOrderCheckpoint {

    Map<Units, Integer> expectedCountOfUnit();
}
