package com.supalosa.bot.task.terran;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.task.BaseTask;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.TaskResult;
import com.supalosa.bot.task.army.TerranWorkerRushDefenceTask;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.utils.UnitFilter;

import java.util.Optional;
import java.util.UUID;

/**
 * A task to move a structure with liftoff abilities.
 */
public class MoveStructureTask extends BaseTask {

    enum State {
        RAISING_STRUCTURES,
        TRANSFERRING,
    }

    private final String taskKey;

    private UnitType structureType;

    private Optional<UnitInPool> structure = Optional.empty();
    private Point2d position;

    private boolean isComplete = false;

    private State state;

    public MoveStructureTask(UnitType structureType, Point2d position) {
        this.structureType = structureType;
        this.taskKey = "MOVE." + UUID.randomUUID();
        this.state = State.RAISING_STRUCTURES;
        this.position = position;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentWithData agentWithData) {
        if (structure.isEmpty()) {
            structure = taskManager.findFreeUnitForTask(this, agentWithData.observation(), UnitFilter.mine(structureType));
        }
        if (structure.isEmpty()) {
            return;
        }
        UnitInPool newStructure1 = agentWithData.observation().getUnit(structure.get().getTag());
        if (newStructure1 == null) {
            isComplete = true;
            onFailure();
            return;
        } else {
            structure = Optional.of(newStructure1);
        }
        if (state == State.RAISING_STRUCTURES) {
            // We don't do this because getAddOnTag returns null for in-progress addons right now.
            if (!(structure.get().unit().getFlying().orElse(false))) {
                if (structure.get().unit().getOrders().isEmpty()) {
                    agentWithData.actions().unitCommand(structure.get().unit(), Abilities.LIFT, false);
                } else {
                    agentWithData.actions().unitCommand(structure.get().unit(), Abilities.CANCEL_LAST, false);
                }
            } else {
                agentWithData.actions().unitCommand(structure.get().unit(), Abilities.MOVE, position, false);
            }
            if (structure.get().unit().getFlying().orElse(false)) {
                state = State.TRANSFERRING;
            }
        } else {
            agentWithData.actions().unitCommand(structure.get().unit(), Abilities.LAND, position, false);
            if (!structure.get().unit().getFlying().orElse(false)) {
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
        if (!(otherTask instanceof MoveStructureTask)) {
            return false;
        }
        MoveStructureTask task = (MoveStructureTask)otherTask;
        return (task.structure.equals(this.structure));
    }

    @Override
    public void debug(S2Agent agentWithData) {
        if (this.structure.isPresent()) {
            Point point3d = structure.get().unit().getPosition();
            Color color = Color.YELLOW;
            agentWithData.debug().debugSphereOut(point3d, 1.0f, color);
            agentWithData.debug().debugTextOut("Swap", point3d, Color.WHITE, 10);
            Point point3d2 = Point.of(position.getX(), position.getY(), agentWithData.observation().terrainHeight(position));
            agentWithData.debug().debugSphereOut(point3d, 1.0f, color);
            agentWithData.debug().debugTextOut("Swap", point3d, Color.WHITE, 10);
            agentWithData.debug().debugLineOut(point3d.add(Point.of(0f, 0f, 0.5f)), point3d2.add(Point.of(0f, 0f, 0.5f)), Color.TEAL);
        }
    }

    @Override
    public String getDebugText() {
        return "Moving " + structure.map(unit -> unit.unit().getType()) + " to " + position;
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
    }
}
