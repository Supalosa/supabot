package com.supalosa.bot.task.army;

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
import com.supalosa.bot.Constants;
import com.supalosa.bot.Expansions;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.engagement.ThreatCalculator;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.utils.UnitFilter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Steals orbital commands to scan/drop mules on enemy siege tanks.
 */
public class SiegeTankMuleBombTask extends AbstractDefaultArmyTask {

    private List<UnitInPool> enemySiegeTanks = new ArrayList<>();
    private Map<Point, List<UnitInPool>> siegeTankClusters = new HashMap<>();
    private long lastSiegeTankSeenAt = 0L;
    private boolean isComplete = false;

    private long siegeTankClustersCalculatedAt = 0L;

    public SiegeTankMuleBombTask(String armyName, ThreatCalculator threatCalculator) {
        super(armyName, threatCalculator);
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        super.onStep(taskManager, data, agent);
        long gameLoop = agent.observation().getGameLoop();

        if (armyUnits.size() == 0) {
            return;
        }

        enemySiegeTanks = agent.observation().getUnits(
                UnitFilter.builder()
                        .alliance(Alliance.ENEMY)
                        .unitType(Units.TERRAN_SIEGE_TANK_SIEGED).build());
        if (enemySiegeTanks.size() > 0) {
            lastSiegeTankSeenAt = gameLoop;
        }
        isComplete = enemySiegeTanks.size() == 0 && gameLoop > lastSiegeTankSeenAt + 22L * 10;

        if (gameLoop > siegeTankClustersCalculatedAt + 33L) {
            siegeTankClustersCalculatedAt = gameLoop;
            siegeTankClusters = Expansions.cluster(enemySiegeTanks, 3.0);
            if (siegeTankClusters.size() <= 1) {
                return;
            }
            // Sort cluster size descending.
            List<Point> clustersBySizeDescending = siegeTankClusters.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<Point, List<UnitInPool>> entry) -> entry.getValue().size()).reversed())
                    .map(entry -> entry.getKey())
                    .collect(Collectors.toList());
            // Find the second biggest cluster within siege tank range of the largest cluster.
            Point targetFound = null;
            for (int i = 0; i < clustersBySizeDescending.size(); ++i) {
                Point mainCluster = clustersBySizeDescending.get(i);
                for (int j = i; j < clustersBySizeDescending.size(); ++j) {
                    Point secondCluster = clustersBySizeDescending.get(j);
                    if (mainCluster.distance(secondCluster) < 13.0) {
                        targetFound = secondCluster;
                        break;
                    }
                }
                if (targetFound != null) {
                    break;
                }
            }
            if (targetFound != null) {
                Point2d target2d = targetFound.toPoint2d();
                if (agent.observation().getVisibility(target2d) != Visibility.VISIBLE) {
                    agent.actions().unitCommand(armyUnits.stream().findFirst().get(), Abilities.EFFECT_SCAN, target2d, false);
                } else {
                    agent.actions().unitCommand(armyUnits.stream().findFirst().get(), Abilities.EFFECT_CALL_DOWN_MULE, target2d, false);
                }
            }
        }
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        return otherTask instanceof SiegeTankMuleBombTask;
    }

    @Override
    public void debug(S2Agent agent) {
        if (siegeTankClusters.size() > 0) {
            for (Map.Entry<Point, List<UnitInPool>> entry : siegeTankClusters.entrySet()) {
                Point2d point2d = entry.getKey().toPoint2d();
                int count = entry.getValue().size();
                float z = agent.observation().terrainHeight(point2d);
                Point point = Point.of(point2d.getX(), point2d.getY(), z);
                agent.debug().debugSphereOut(point, count, Color.RED);
                agent.debug().debugTextOut("Siege Tanks (" + count + ")", point, Color.WHITE, 8);
            }
        }

    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public boolean wantsUnit(Unit unit) {
        return unit.getType() == Units.TERRAN_ORBITAL_COMMAND;
    }

    @Override
    public List<UnitTypeRequest> requestingUnitTypes() {
        return new ArrayList<>();
    }
}
