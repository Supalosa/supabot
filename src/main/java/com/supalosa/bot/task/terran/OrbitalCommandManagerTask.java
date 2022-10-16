package com.supalosa.bot.task.terran;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Expansions;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.task.DefaultTaskWithUnits;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.TaskResult;
import com.supalosa.bot.utils.UnitFilter;
import com.supalosa.bot.utils.Utils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages mules/scans.
 */
public class OrbitalCommandManagerTask extends DefaultTaskWithUnits {

    private List<UnitInPool> enemySiegeTanks = new ArrayList<>();
    private Map<Point, List<UnitInPool>> siegeTankClusters = new HashMap<>();
    private long lastSiegeTankSeenAt = 0L;

    private long siegeTankClustersCalculatedAt = 0L;

    private final Map<Point2d, Long> scannedClusters = new HashMap<>();

    public OrbitalCommandManagerTask(int priority) {
        super(priority);
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

        if (siegeTankClusters.size() == 0) {
            // land mules and scan cloaked items
            float reserveCcEnergy = (data.fightManager().hasSeenCloakedOrBurrowedUnits() ? 100f : 50f);
            Set<Point2d> scanClusters = new HashSet<>(data.fightManager().getCloakedOrBurrowedUnitClusters());
            new HashMap<>(scannedClusters).forEach((scannedCluster, time) -> {
                // Scan lasts for 12.3 seconds.
                if (gameLoop > time + 22L * 12) {
                    scannedClusters.remove(scannedCluster);
                }
            });
            agent.observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    UnitInPool.isUnit(Units.TERRAN_ORBITAL_COMMAND).test(unitInPool)).forEach(unit -> {
                if (unit.unit().getEnergy().isPresent() && unit.unit().getEnergy().get() > reserveCcEnergy) {
                    Optional<Unit> nearestMineral = Utils.findNearestMineralPatch(agent.observation(), unit.unit().getPosition().toPoint2d());
                    nearestMineral.ifPresent(mineral -> {
                        agent.actions().unitCommand(unit.unit(), Abilities.EFFECT_CALL_DOWN_MULE, mineral, false);
                    });
                }
                if (scanClusters.size() > 0) {
                    scanClusters.stream().filter(scanPoint ->
                            // Return scan points that are not near an already scanned point.
                            !scannedClusters.keySet().stream()
                                    .anyMatch(alreadyScannedPoint -> alreadyScannedPoint.distance(scanPoint) < 8f)
                    ).findFirst().ifPresent(scanPoint -> {
                        agent.actions().unitCommand(unit.unit(), Abilities.EFFECT_SCAN, scanPoint, false);
                        scannedClusters.put(scanPoint, gameLoop);
                        scanClusters.remove(scanPoint);
                    });
                }
            });
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
        return null;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        return otherTask instanceof OrbitalCommandManagerTask;
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
    public String getDebugText() {
        return "Mule Bomb";
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
