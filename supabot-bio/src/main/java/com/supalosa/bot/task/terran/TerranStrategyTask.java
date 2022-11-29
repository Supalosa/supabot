package com.supalosa.bot.task.terran;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.analysis.Ramp;
import com.supalosa.bot.strategy.Protoss2BaseGatewayRush;
import com.supalosa.bot.strategy.StrategicObservation;
import com.supalosa.bot.strategy.Zerg12PoolStrategicObservation;
import com.supalosa.bot.task.*;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Strategic task that manages scouting, switching builds etc.
 * Also handles static defensive arrangements.
 */
public class TerranStrategyTask extends BaseTask implements StrategyTask {

    private static final long OBSERVATION_CHECK_INTERVAL = 11L;

    private List<StrategicObservation> observationList = new ArrayList<>(StrategicObservation.allObservations());
    private Set<Class<? extends StrategicObservation>> seenObservations = new HashSet<>();

    private long observationsLastCheckedAt = 0L;

    @Override
    public void onStep(TaskManager taskManager, AgentWithData agentWithData) {
        long gameLoop = agentWithData.observation().getGameLoop();
        if (gameLoop > observationsLastCheckedAt + OBSERVATION_CHECK_INTERVAL) {
            observationsLastCheckedAt = gameLoop;
            observationList.forEach(strategicObservation -> {
               if (strategicObservation.apply(agentWithData)) {
                   taskManager.dispatchMessage(this, strategicObservation);
                   agentWithData.actions().sendChat(
                           "Tag:" + strategicObservation.getClass().getSimpleName(),
                           ActionChat.Channel.BROADCAST);
                   seenObservations.add(strategicObservation.getClass());
               }
            });

            observationList = observationList.stream().filter(obs -> !obs.isComplete()).collect(Collectors.toList());

            handleObservations(taskManager, agentWithData);
        }
    }

    private void handleObservations(TaskManager taskManager, AgentWithData agentWithData) {
        long gameLoop = agentWithData.observation().getGameLoop();
        if (hasSeenObservation(Zerg12PoolStrategicObservation.class)) {
            agentWithData.structurePlacementCalculator().ifPresent(spc -> {
                // For west facing ramps, which leave a gap in the wall.
                // If the first barracks can fit an addon, move it right to close the wall.
                if (spc.getMainRamp().filter(ramp ->
                        ramp.getRampDirection() == Ramp.RampDirection.NORTH_WEST ||
                                ramp.getRampDirection() == Ramp.RampDirection.SOUTH_WEST).isPresent()) {
                    spc.getFirstBarracksWithAddon(agentWithData.observation())
                            .filter(unit -> spc.canFitAddon(unit.unit())).ifPresent(barracks -> {
                                Point2d newPos = barracks.unit().getPosition().toPoint2d().add(2f, 0);
                                taskManager.addTask(new MoveStructureTask(Units.TERRAN_BARRACKS, newPos), 1);
                            });
                }
            });
        }

        if (hasSeenObservation(Protoss2BaseGatewayRush.class)) {
            // Stay home until 6:00 and build a bunker at the natural.
            long cyclesUntilDefenceEnds = Math.max(0, (long)(360 * 22.4) - gameLoop);
            if (cyclesUntilDefenceEnds > 0) {
                agentWithData.fightManager().setCanAttack(false);
                agentWithData.mapAwareness().getNaturalBaseRegion().ifPresent(naturalBaseRegion -> {
                    agentWithData.fightManager().defendRegionFor(naturalBaseRegion.region(), 2, cyclesUntilDefenceEnds);
                });
            } else {
                agentWithData.fightManager().setCanAttack(true);
            }
        }
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
        return getClass().toString();
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        return otherTask instanceof TerranStrategyTask;
    }

    @Override
    public void debug(S2Agent agent) {

    }

    @Override
    public String getDebugText() {
        return "TerranStrategyTask";
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
    }

    @Override
    public boolean hasSeenObservation(Class<? extends StrategicObservation> observation) {
        return this.seenObservations.contains(observation);
    }
}
