package com.supalosa.bot.task;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import org.immutables.value.Value;

import java.util.Optional;
import java.util.Set;

@Value.Immutable
public interface TaskResult {
    boolean isSuccessful();
    Optional<Point2d> locationResult();
    Optional<Set<Task>> producedUnit();
}
