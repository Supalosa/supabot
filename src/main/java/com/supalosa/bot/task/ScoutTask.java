package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.utils.UnitFilter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class ScoutTask implements Task {

    private final String taskKey;

    private Optional<Point2d> scoutTarget;
    private List<Tag> assignedScouters = List.of();
    private final int maxScouters;
    private int usedScouters = 0;
    int targetScouters = 1;
    private long sleepUntil = 0L;
    private int sleepCount = 0;
    private static final int MAX_SLEEP_COUNT = 10;

    private boolean isComplete = false;

    public ScoutTask(Optional<Point2d> point2d, int maxScouters) {
        this.scoutTarget = point2d;
        this.taskKey = "SCOUT." + UUID.randomUUID();
        this.maxScouters = maxScouters;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        long gameLoop = agent.observation().getGameLoop();
        if (gameLoop < sleepUntil) {
            return;
        }
        if (scoutTarget.isEmpty()) {
            sleepUntil = gameLoop + 22L;
            ++sleepCount;
            if (sleepCount > MAX_SLEEP_COUNT) {
                isComplete = true;
            } else {
                scoutTarget = data.mapAwareness().getNextScoutTarget();
            }
            return;
        }
        if (usedScouters > maxScouters) {
            isComplete = true;
            return;
        }
        if (agent.observation().getVisibility(scoutTarget.get()) == Visibility.VISIBLE) {
            scoutTarget = Optional.empty();
            sleepCount = 0;
            return;
        }
        List<Unit> scouters = assignedScouters.stream()
                .map(tag -> agent.observation().getUnit(tag))
                .filter(unitInPool -> unitInPool != null)
                .map(UnitInPool::unit)
                .collect(Collectors.toList());

        if (scouters.size() < targetScouters) {
            Optional<UnitInPool> maybeScouter = taskManager.findFreeUnitForTask(this,
                agent.observation(),
                UnitFilter.builder()
                        .alliance(Alliance.SELF)
                        .unitType(Units.TERRAN_SCV)
                        .build());
            maybeScouter.ifPresent(scouter -> {
                scouters.add(scouter.unit());
                ++usedScouters;
            });
        }

        assignedScouters = scouters.stream().map(unit -> unit.getTag()).collect(Collectors.toList());
        if (scouters.size() > 0) {
            // TODO maybe better repair task.
            agent.actions().unitCommand(scouters, Abilities.MOVE, scoutTarget.get(), false);
        }
    }

    @Override
    public Optional<TaskResult> getResult() {
        return Optional.empty();
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }

    @Override
    public String getKey() {
        return taskKey;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        if (!(otherTask instanceof ScoutTask)) {
            return false;
        }
        ScoutTask task = (ScoutTask)otherTask;
        return (task.scoutTarget.equals(this.scoutTarget));
    }

    @Override
    public void debug(S2Agent agent) {
        if (this.scoutTarget.isPresent()) {
            float height = agent.observation().terrainHeight(scoutTarget.get());
            Point point3d = Point.of(scoutTarget.get().getX(), scoutTarget.get().getY(), height);
            Color color = Color.YELLOW;
            agent.debug().debugSphereOut(point3d, 1.0f, color);
            agent.debug().debugTextOut(
                    "Scout(" + usedScouters + "/" + maxScouters + ")",
                    point3d, Color.WHITE, 10);
        }
    }

    @Override
    public String getDebugText() {
        return "Scout " + scoutTarget.map(point2d -> point2d.toString()).orElse("<unknown>") + " x(" + usedScouters + "/" + maxScouters + ")";
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
    }
}
