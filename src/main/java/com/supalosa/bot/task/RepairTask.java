package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.UnitTypeData;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.GameData;
import com.supalosa.bot.task.army.TerranWorkerRushDefenceTask;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.utils.UnitComparator;
import com.supalosa.bot.utils.UnitFilter;

import java.util.*;
import java.util.stream.Collectors;

public class RepairTask implements Task {

    private final String taskKey;

    private Optional<Tag> repairTarget = Optional.empty();
    private Set<Tag> assignedRepairers = new HashSet<>();
    int targetRepairers = 1;
    private long lastRepairerReleasedAt = 0L;
    private static final long REPAIRER_RELEASE_INTERVAL = 22L;
    private long isFinishedRepairAt = Long.MAX_VALUE;
    private static final long REPAIR_STICKY_TIME = 11L;

    private long hpObservationCyclesGainingHp = 0;

    private float previousHpObservation = -1f;
    private long previousHpObservationAt = 0L;
    private static final long HP_OBSERVATION_INTERVAL = 5L;

    private boolean isComplete = false;
    private boolean aborted = false;

    public RepairTask(Tag tag) {
        this.repairTarget = Optional.of(tag);
        this.taskKey = "REPAIR." + UUID.randomUUID();
    }

    @Override
    public void onStep(TaskManager taskManager, AgentWithData agentWithData) {
        if (repairTarget.isEmpty()) {
            isComplete = true;
            return;
        }
        UnitInPool unitToRepair = agentWithData.observation().getUnit(repairTarget.get());
        long gameLoop = agentWithData.observation().getGameLoop();
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
            if (isFinishedRepairAt > gameLoop) {
                isFinishedRepairAt = gameLoop;
            }
        } else {
            isFinishedRepairAt = gameLoop + 22L;
        }
        if (gameLoop >= isFinishedRepairAt + REPAIR_STICKY_TIME) {
            isComplete = true;
            return;
        }
        if (aborted) {
            isComplete = true;
            if (assignedRepairers.size() > 0) {
                agentWithData.actions().unitCommand(assignedRepairers, Abilities.STOP, false);
            }
            return;
        }

        List<Unit> repairers = assignedRepairers.stream()
                .map(tag -> agentWithData.observation().getUnit(tag))
                .filter(unitInPool -> unitInPool != null)
                .map(UnitInPool::unit)
                .collect(Collectors.toList());

        if (repairers.size() < targetRepairers) {
            Optional<UnitInPool> maybeRepairer = taskManager.findFreeUnitForTask(this,
                    agentWithData.observation(),
                UnitFilter.builder()
                        .alliance(Alliance.SELF)
                        .unitType(Units.TERRAN_SCV)
                        .inRangeOf(unitToRepair.unit().getPosition().toPoint2d())
                        .range(22f)
                        .build(),
                UnitComparator.builder().distanceToUnitInPool(unitToRepair).ascending(true).build());
            maybeRepairer.ifPresent(repairer -> {
                repairers.add(repairer.unit());
            });
        } else if (repairers.size() > 0 && repairers.size() > targetRepairers && gameLoop > lastRepairerReleasedAt + REPAIRER_RELEASE_INTERVAL) {
            lastRepairerReleasedAt = gameLoop;
            Unit releasedScv = repairers.remove(repairers.size() - 1);
            agentWithData.actions().unitCommand(releasedScv, Abilities.MOVE, releasedScv.getPosition().toPoint2d(), false);
            taskManager.releaseUnit(releasedScv.getTag(), this);
        }

        /*if (agentWithData.observation().getGameLoop() > lastRepairCountCheck + 11L) {
            lastRepairCountCheck = agentWithData.observation().getGameLoop();
            List<UnitInPool> unitsNearby = agentWithData.observation().getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.ENEMY)
                            .inRangeOf(unitToRepair.unit().getPosition().toPoint2d())
                            .range(unitToRepair.unit().getRadius() + 6f)
                            .build());
            targetRepairers = Math.max(1, Math.min((int)((unitToRepair.unit().getRadius() + 1)*2), unitsNearby.size()));
        }*/
        if (gameLoop > previousHpObservationAt + HP_OBSERVATION_INTERVAL) {
            float currentHpObservation = unitToRepair.unit().getHealth().orElse(previousHpObservation);
            if (previousHpObservation < 0f) {
                previousHpObservation = currentHpObservation;
            }
            if (currentHpObservation < previousHpObservation) {
                float delta = previousHpObservation - currentHpObservation;
                double healingRequiredPerObservation = (HP_OBSERVATION_INTERVAL * healingPerTick(unitToRepair.unit(), agentWithData.gameData()));
                int neededExtraRepairers = (int) Math.ceil(delta / healingRequiredPerObservation);
                targetRepairers = Math.min(4, Math.max(1, targetRepairers + neededExtraRepairers));
                hpObservationCyclesGainingHp = 0;
            } else {
                ++hpObservationCyclesGainingHp;
                if (hpObservationCyclesGainingHp >= 4) {
                    // Every 4 cycles that the structure is stable or gaining hp, remove one assigned healer.
                    targetRepairers = Math.max(1, targetRepairers - 1);
                    hpObservationCyclesGainingHp = 0L;
                }
            }
            previousHpObservation = currentHpObservation;
        }
        assignedRepairers = repairers.stream().map(unit -> unit.getTag()).collect(Collectors.toSet());
        if (repairers.size() > 0) {
            // TODO maybe better repair task.
            if (Float.compare(unitToRepair.unit().getHealth().get(), unitToRepair.unit().getHealthMax().get()) == 0) {
                agentWithData.actions().unitCommand(repairers, Abilities.STOP, false);
            } else {
                agentWithData.actions().unitCommand(repairers, Abilities.EFFECT_REPAIR, unitToRepair.unit(), false);
            }
        }
    }

    // Returns the healing per second of a single SCV.
    private float healingPerTick(Unit unit, GameData gameData) {
        float buildTimeTicks = gameData.getUnitTypeData(unit.getType()).flatMap(UnitTypeData::getBuildTime).orElse(22.4f * 60f);
        float maxHp = unit.getHealthMax().orElse(100f);
        return maxHp / buildTimeTicks;
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
