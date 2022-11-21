package com.supalosa.bot.task.mission;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.task.*;
import com.supalosa.bot.task.army.ArmyTask;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.task.TaskVisitor;

import java.util.*;

/**
 * Dummy task so the attacking army doesn't get stolen.
 */
public class DummyAttackTask extends DefaultTaskWithUnits implements MissionTask {

    private Region location;
    private List<ArmyTask> assignedArmies = new ArrayList<>();

    public DummyAttackTask(Region location) {
        super(100);
        this.location = location;
    }

    @Override
    public void onStepImpl(TaskManager taskManager, AgentWithData agentWithData) {
        assignedArmies.forEach(army -> {
            //agentWithData.mapAwareness().getRegionDataForId(getLocation().regionId());
            army.setTargetPosition(Optional.of(location.centrePoint()));
        });
    }

    @Override
    public Optional<TaskResult> getResult() {
        return Optional.empty();
    }

    @Override
    public boolean isComplete() {
        return false;
    }

    @Override
    public String getKey() {
        return "DummyAttack";
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        if (otherTask == this) {
            return true;
        }
        return false;
    }

    @Override
    public void debug(S2Agent agent) {
    }

    @Override
    public String getDebugText() {
        return "DummyAttack";
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
    }

    @Override
    public List<ArmyTask> getAdditionalArmies() {
        return assignedArmies;
    }

    @Override
    public List<UnitTypeRequest> requestingUnitTypes() {
        return List.of();
    }

    @Override
    public Map<UnitType, Integer> getCurrentCompositionCache() {
        return null;
    }

    @Override
    public int getSize() {
        return 0;
    }

    @Override
    public void accept(TaskVisitor visitor) {

    }

    @Override
    public Region getLocation() {
        return this.location;
    }

    @Override
    public void setLocation(Region location) {
        this.location = location;
    }

    // NOT IMPLEMENTED YET
    @Override
    public boolean wantsUnit(Unit unit) {
        return false;
    }

    @Override
    public boolean hasUnit(Tag unitTag) {
        return false;
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public void endMission() {
    }

    @Override
    public void onArmyRemoved(ArmyTask armyTask) {
        assignedArmies.remove(armyTask);
    }
}
