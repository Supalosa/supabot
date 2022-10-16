package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.utils.UnitComparator;
import com.supalosa.bot.utils.UnitFilter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class RepairTask implements Task {

    private final String taskKey;

    private Optional<Tag> repairTarget = Optional.empty();
    private List<Tag> assignedRepairers = List.of();
    int targetRepairers = 1;
    private long lastRepairCountCheck = 0L;

    private boolean isComplete = false;

    public RepairTask(Tag tag) {
        this.repairTarget = Optional.of(tag);
        this.taskKey = "REPAIR." + UUID.randomUUID();
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        if (repairTarget.isEmpty()) {
            isComplete = true;
            return;
        }
        UnitInPool unitToRepair = agent.observation().getUnit(repairTarget.get());
        if (unitToRepair == null) {
            isComplete = true;
            repairTarget = Optional.empty();
            return;
        }
        if (unitToRepair.unit().getHealth().isEmpty() || unitToRepair.unit().getHealthMax().isEmpty()) {
            isComplete = true;
            return;
        }
        if (Float.compare(unitToRepair.unit().getHealth().get(), unitToRepair.unit().getHealthMax().get()) == 0) {
            isComplete = true;
            return;
        }

        List<Unit> repairers = assignedRepairers.stream()
                .map(tag -> agent.observation().getUnit(tag))
                .filter(unitInPool -> unitInPool != null)
                .map(UnitInPool::unit)
                .collect(Collectors.toList());

        if (repairers.size() < targetRepairers) {
            Optional<UnitInPool> maybeRepairer = taskManager.findFreeUnitForTask(this,
                agent.observation(),
                UnitFilter.builder()
                        .alliance(Alliance.SELF)
                        .unitType(Units.TERRAN_SCV)
                        .inRangeOf(unitToRepair.unit().getPosition().toPoint2d())
                        .range(15f)
                        .build(),
                UnitComparator.builder().distanceToUnitInPool(unitToRepair).ascending(true).build());
            maybeRepairer.ifPresent(repairer -> {
                repairers.add(repairer.unit());
            });
        }

        if (agent.observation().getGameLoop() > lastRepairCountCheck + 11L) {
            lastRepairCountCheck = agent.observation().getGameLoop();
            List<UnitInPool> unitsNearby = agent.observation().getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.ENEMY)
                            .inRangeOf(unitToRepair.unit().getPosition().toPoint2d())
                            .range(unitToRepair.unit().getRadius() + 3f)
                            .build());
            targetRepairers = Math.max(1, Math.min((int)(unitToRepair.unit().getRadius()+2), unitsNearby.size()));
        }
        assignedRepairers = repairers.stream().map(unit -> unit.getTag()).collect(Collectors.toList());
        if (repairers.size() > 0) {
            // TODO maybe better repair task.
            agent.actions().unitCommand(repairers, Abilities.EFFECT_REPAIR, unitToRepair.unit(), false);
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
        if (!(otherTask instanceof RepairTask)) {
            return false;
        }
        RepairTask task = (RepairTask)otherTask;
        return (task.repairTarget.equals(this.repairTarget));
    }

    @Override
    public void debug(S2Agent agent) {
        if (this.repairTarget.isPresent()) {
            UnitInPool unitInPool = agent.observation().getUnit(this.repairTarget.get());
            if (unitInPool == null) {
                return;
            }
            Point point3d = unitInPool.unit().getPosition();
            Color color = Color.YELLOW;
            agent.debug().debugSphereOut(point3d, 1.0f, color);
            agent.debug().debugTextOut(
                    "Repair(" + assignedRepairers.size() + "/" + targetRepairers + ")",
                    point3d, Color.WHITE, 10);
        }
    }

    @Override
    public String getDebugText() {
        return "Repair " + repairTarget.map(tag -> tag.getValue().toString()).orElse("<unknown>") + " x(" + assignedRepairers.size() + "/" + targetRepairers + ")";
    }
}
