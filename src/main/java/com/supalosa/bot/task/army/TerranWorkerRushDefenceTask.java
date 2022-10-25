package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.analysis.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.engagement.WorkerDefenceThreatCalculator;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.utils.UnitFilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Defence against an enemy worker rush.
 */
public class TerranWorkerRushDefenceTask extends DefaultArmyTask {

    public class WorkerRushDetected implements TaskMessage {}

    private boolean isComplete = false;

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = 0L;

    public TerranWorkerRushDefenceTask(String armyName, int basePriority) {
        super(armyName, basePriority, new WorkerDefenceThreatCalculator(), new TerranBioArmyTaskBehaviour());
    }

    @Override
    public void onStep(TaskManager taskManager, AgentWithData agentWithData) {
        super.onStep(taskManager, agentWithData);
        long gameLoop = agentWithData.observation().getGameLoop();

        if (gameLoop > desiredCompositionUpdatedAt + 22L) {
            desiredCompositionUpdatedAt = gameLoop;
            updateComposition();
        }
        // This army disappears if we can't see any workers near the start position.
        List<UnitInPool> enemyUnitsNearStartPosition = agentWithData.observation().getUnits(
                UnitFilter.builder()
                        .alliance(Alliance.ENEMY)
                        .inRangeOf(agentWithData.observation().getStartLocation().toPoint2d())
                        .range(20f)
                        .build());
        if (enemyUnitsNearStartPosition.size() == 0) {
            sendChat(agentWithData.actions(), "Worker rush defence ended.");
            this.isComplete = true;
        } else {
            taskManager.dispatchMessage(this, new WorkerRushDetected());
        }
    }

    private void sendChat(ActionInterface actionInterface, String message) {
        actionInterface.sendChat("[WorkerRush] " + message, ActionChat.Channel.BROADCAST);
    }

    private void updateComposition() {
        List<UnitTypeRequest> result = new ArrayList<>();
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_SCV)
                .productionAbility(Abilities.TRAIN_SCV)
                .producingUnitType(Units.TERRAN_COMMAND_CENTER)
                .amount(100)
                .build()
        );
        desiredComposition = result;
    }

    protected AggressionState attackCommand(S2Agent agent,
                                            AgentData data,
                                            Optional<Point2d> centreOfMass,
                                            Optional<Point2d> suggestedAttackMovePosition,
                                            Optional<Point2d> suggestedRetreatMovePosition,
                                            Optional<Army> maybeEnemyArmy) {
        ObservationInterface observationInterface = agent.observation();
        ActionInterface actionInterface = agent.actions();

        List<UnitInPool> units = armyUnits.stream()
                .map(tag -> observationInterface.getUnit(tag))
                .filter(unitInPool -> unitInPool != null)
                .collect(Collectors.toList());
        List<UnitInPool> enemyUnitsNearStartPosition = agent.observation().getUnits(
                UnitFilter.builder()
                        .alliance(Alliance.ENEMY)
                        .inRangeOf(agent.observation().getStartLocation().toPoint2d())
                        .range(20f)
                        .build());
        List<UnitInPool> mineralsNearStartPosition = agent.observation().getUnits(
                UnitFilter.builder()
                        .alliance(Alliance.NEUTRAL)
                        .unitTypes(Constants.MINERAL_TYPES)
                        .inRangeOf(agent.observation().getStartLocation().toPoint2d())
                        .range(10f)
                        .build());
        double averageWorkerHealth = units.stream().mapToDouble(unit ->
                        unit.unit().getHealth().orElse(0f))
                .average().orElse(0.0);
        double averageEnemyHealth = enemyUnitsNearStartPosition.stream().mapToDouble(unit ->
                        unit.unit().getHealth().orElse(0f))
                .average().orElse(0.0);
        double averageEnemyX = enemyUnitsNearStartPosition.stream().mapToDouble(unit ->
                        unit.unit().getPosition().getX())
                .average().orElse(0.0);
        double averageEnemyY = enemyUnitsNearStartPosition.stream().mapToDouble(unit ->
                        unit.unit().getPosition().getY())
                .average().orElse(0.0);
        Point2d enemyCentreOfMass = Point2d.of((float)averageEnemyX, (float)averageEnemyY);

        if (units.isEmpty()) {
            return null;
        }

        sendChat(agent.actions(), "Worker rush step [" + units.size() + " vs " + enemyUnitsNearStartPosition.size() + "]");

        actionInterface.toggleAutocast(new ArrayList<>(armyUnits), Abilities.EFFECT_REPAIR);
        units.forEach(scvInPool -> {
            if (scvInPool != null) {
                // Empty weapon = no attack - so just scan-attack there.
                if (scvInPool.unit().getHealth().orElse(0f) >= (averageWorkerHealth - 5) &&
                        scvInPool.unit().getWeaponCooldown().isEmpty() ||
                        (scvInPool.unit().getWeaponCooldown().isPresent() && scvInPool.unit().getWeaponCooldown().get() < 0.01f)) {
                    // If enemy in range, attack the lowest hp one, else repair.
                    Optional<UnitInPool> nearbyEnemyOnLowHp = enemyUnitsNearStartPosition.stream()
                            .filter(enemyUnit ->
                                    scvInPool.unit().getPosition().distance(enemyUnit.unit().getPosition()) < 1f)
                            .sorted(Comparator.comparing(unitInPool -> unitInPool.unit().getHealth().orElse(0f)))
                            .findFirst();
                    if (nearbyEnemyOnLowHp.isPresent()) {
                        actionInterface.unitCommand(scvInPool.unit(), Abilities.ATTACK, nearbyEnemyOnLowHp.get().unit(), false);
                    } else {
                        Optional<UnitInPool> nearbyScvOnLowHp = units.stream()
                                .filter(nearbyTag -> !nearbyTag.equals(scvInPool))
                                .filter(friendlyUnit ->
                                        scvInPool.unit().getPosition().distance(friendlyUnit.unit().getPosition()) < 1f)
                                .sorted(Comparator.comparing(unitInPool -> unitInPool.unit().getHealth().orElse(0f)))
                                .findFirst();
                        if (observationInterface.getMinerals() > 0 && nearbyScvOnLowHp.isPresent()) {
                            actionInterface.unitCommand(scvInPool.unit(), Abilities.EFFECT_REPAIR, nearbyScvOnLowHp.get().unit(), false);
                        } else if (suggestedAttackMovePosition.isPresent()) {
                            actionInterface.unitCommand(scvInPool.unit(), Abilities.ATTACK, suggestedAttackMovePosition.get(), false);
                        }
                    }
                } else {
                    // move towards a mineral that furthest from the centre of mass.
                    Optional<UnitInPool> bestMineral = mineralsNearStartPosition.stream()
                            .sorted(Comparator.comparing((UnitInPool mineral) ->
                                    mineral.unit().getPosition().toPoint2d().distance(enemyCentreOfMass)).reversed())
                            .findFirst();
                    if (bestMineral.isPresent()) {
                        // Drill to best mineral
                        actionInterface.unitCommand(scvInPool.unit(), Abilities.SMART, bestMineral.get().unit(), false);
                    } else if (suggestedRetreatMovePosition.isPresent()) {
                        actionInterface.unitCommand(scvInPool.unit(), Abilities.MOVE, suggestedRetreatMovePosition.get(), false);
                    }
                }
            }
        });

        return null;
    }
    @Override
    public List<UnitTypeRequest> requestingUnitTypes() {
        return desiredComposition;
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        if (otherTask instanceof TerranWorkerRushDefenceTask) {
            // only one at a time for now.
            return true;
        }
        return false;
    }

    @Override
    public void onUnitIdle(UnitInPool unitTag) {

    }
}
