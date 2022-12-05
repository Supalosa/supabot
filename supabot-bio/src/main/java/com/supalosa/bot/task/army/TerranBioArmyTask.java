package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.engagement.TerranBioThreatCalculator;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.message.*;
import com.supalosa.bot.task.terran.ImmutableScanRequestTaskMessage;
import com.supalosa.bot.utils.UnitFilter;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A bio army with marines, marauders, medivacs and mines.
 */
public class TerranBioArmyTask extends DefaultArmyTask {

    private int basePriority;

    public TerranBioArmyTask(String armyName, String armyKey, int basePriority, Optional<TerranBioArmyTask> parentArmy) {
        super(armyName,
                armyKey,
                basePriority,
                new TerranBioThreatCalculator(),
                new TerranBioArmyTaskBehaviour(),
                parentArmy);
        this.basePriority = basePriority;
    }

    public TerranBioArmyTask(String armyName, int basePriority) {
        this(armyName, armyName, basePriority, Optional.empty());
    }

    @Override
    public DefaultArmyTask createChildArmyImpl() {
        return new TerranBioArmyTask(
                this.getArmyName() + "-child",
                this.getArmyName() + "-child" + UUID.randomUUID(),
                basePriority,
                Optional.of(this));
    }

    @Override
    public void onStepImpl(TaskManager taskManager, AgentWithData agentWithData) {
        super.onStepImpl(taskManager, agentWithData);
    }

    private List<TaskPromise> requestScannerSweep(AgentData data, Point2d scanPosition, long scanRequiredBefore) {
        return data.taskManager().dispatchMessage(this,
                ImmutableScanRequestTaskMessage.builder()
                        .point2d(scanPosition)
                        .requiredBefore(scanRequiredBefore)
                        .build());
    }

    @Override
    public int getPriority() {
        return basePriority;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        return false;
    }

    @Override
    public void debug(S2Agent agent) {
        super.debug(agent);
    }

    @Override
    public void onUnitIdle(UnitInPool unitTag) {

    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
    }
}
