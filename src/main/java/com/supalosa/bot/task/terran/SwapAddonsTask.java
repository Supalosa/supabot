package com.supalosa.bot.task.terran;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.task.BaseTask;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.TaskResult;
import com.supalosa.bot.task.army.TerranWorkerRushDefenceTask;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.utils.UnitComparator;
import com.supalosa.bot.utils.UnitFilter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A task to swap addons between structures.
 */
public class SwapAddonsTask extends BaseTask {

    enum State {
        RAISING_STRUCTURES,
        TRANSFERRING,
    }

    private final String taskKey;

    private UnitType structureType1;
    private UnitType structureType2;

    private Optional<UnitInPool> structure1 = Optional.empty();
    private Optional<Point2d> initialPosition1 = Optional.empty();
    private Optional<UnitInPool> structure2 = Optional.empty();
    private Optional<Point2d> initialPosition2 = Optional.empty();

    int targetRepairers = 1;
    private long lastRepairCountCheck = 0L;

    private boolean isComplete = false;
    private boolean aborted = false;

    private State state;

    public SwapAddonsTask(UnitType structureType1, UnitType structureType2) {
        this.structureType1 = structureType1;
        this.structureType2 = structureType2;
        this.taskKey = "SWAP." + UUID.randomUUID();
        this.state = State.RAISING_STRUCTURES;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        if (structure1.isEmpty()) {
            structure1 = taskManager.findFreeUnitForTask(this, agent.observation(), UnitFilter.mine(structureType1));
            if (structure1.isPresent()) {
                initialPosition1 = structure1.map(UnitInPool::unit).map(Unit::getPosition).map(Point::toPoint2d);
            }
        }
        if (structure2.isEmpty()) {
            structure2 = taskManager.findFreeUnitForTask(this, agent.observation(), UnitFilter.mine(structureType2));
            if (structure2.isPresent()) {
                initialPosition2 = structure2.map(UnitInPool::unit).map(Unit::getPosition).map(Point::toPoint2d);
            }
        }
        if (structure1.isEmpty() || structure2.isEmpty()) {
            return;
        }
        UnitInPool newStructure1 = agent.observation().getUnit(structure1.get().getTag());
        if (newStructure1 == null) {
            isComplete = true;
            onFailure();
            return;
        } else {
            structure1 = Optional.of(newStructure1);
        }
        UnitInPool newStructure2 = agent.observation().getUnit(structure2.get().getTag());
        if (newStructure2 == null) {
            isComplete = true;
            onFailure();
            return;
        } else {
            structure2 = Optional.of(newStructure2);
        }
        if (state == State.RAISING_STRUCTURES) {
            // We don't do this because getAddOnTag returns null for in-progress addons right now.
            /*if (structure1.get().unit().getAddOnTag().isEmpty() && structure2.get().unit().getAddOnTag().isEmpty()) {
                isComplete = false;
                onFailure();
                return;
            }*/
            if (!(structure1.get().unit().getFlying().orElse(false))) {
                if (structure1.get().unit().getOrders().isEmpty()) {
                    agent.actions().unitCommand(structure1.get().unit(), Abilities.LIFT, false);
                } else {
                    agent.actions().unitCommand(structure1.get().unit(), Abilities.CANCEL_LAST, false);
                }
            } else {
                agent.actions().unitCommand(structure1.get().unit(), Abilities.MOVE, initialPosition2.get(), false);
            }
            if (!(structure2.get().unit().getFlying().orElse(false))) {
                if (structure2.get().unit().getOrders().isEmpty()) {
                    agent.actions().unitCommand(structure2.get().unit(), Abilities.LIFT, false);
                } else {
                    agent.actions().unitCommand(structure2.get().unit(), Abilities.CANCEL_LAST, false);
                }
            } else {
                agent.actions().unitCommand(structure2.get().unit(), Abilities.MOVE, initialPosition1.get(), false);
            }
            if (structure1.get().unit().getFlying().orElse(false) && structure2.get().unit().getFlying().orElse(false)) {
                state = State.TRANSFERRING;
            }
        } else {
            agent.actions().unitCommand(structure1.get().unit(), Abilities.LAND, initialPosition2.get(), false);
            agent.actions().unitCommand(structure2.get().unit(), Abilities.LAND, initialPosition1.get(), false);
            if (!structure1.get().unit().getFlying().orElse(false) && !structure2.get().unit().getFlying().orElse(false)) {
                isComplete = true;
                onComplete();
            }
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
        if (!(otherTask instanceof SwapAddonsTask)) {
            return false;
        }
        SwapAddonsTask task = (SwapAddonsTask)otherTask;
        return (task.structure1.equals(this.structure1) && task.structure2.equals(this.structure2));
    }

    @Override
    public void debug(S2Agent agent) {
        if (this.structure1.isPresent()) {
            Point point3d = structure1.get().unit().getPosition();
            Color color = Color.YELLOW;
            agent.debug().debugSphereOut(point3d, 1.0f, color);
            agent.debug().debugTextOut("Swap", point3d, Color.WHITE, 10);
            if (this.structure2.isPresent()) {
                Point point3d2 = structure2.get().unit().getPosition();
                agent.debug().debugSphereOut(point3d, 1.0f, color);
                agent.debug().debugTextOut("Swap", point3d, Color.WHITE, 10);
                agent.debug().debugLineOut(point3d.add(Point.of(0f, 0f, 0.5f)), point3d2.add(Point.of(0f, 0f, 0.5f)), Color.TEAL);
            }
        }
    }

    @Override
    public String getDebugText() {
        return "Swapping " + structure1.map(unit -> unit.unit().getType()) + " and " + structure2.map(unit -> unit.unit().getType());
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        // Abort repair task if worker rush detected.
        if (message instanceof TerranWorkerRushDefenceTask.WorkerRushDetected) {
            this.aborted = true;
            return Optional.empty();
        } else {
            return Optional.empty();
        }
    }
}
