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
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Expansions;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.task.*;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskMessageResponse;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.utils.UnitFilter;
import com.supalosa.bot.utils.Utils;
import org.immutables.value.Value;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages mules/scans.
 */
public class OrbitalCommandManagerTask extends DefaultTaskWithUnits {

    @Value.Immutable
    public interface ScanRequestTaskMessage extends TaskMessage {
        /**
         * Point that scan is requested.
         */
        Point2d point2d();

        /**
         * Game loop that the scan is required before.
         */
        long requiredBefore();
    }

    @Value.Immutable
    public interface ScanRequestTaskMessageResponse extends TaskMessageResponse {
        /**
         * The point that was actually scanned (if one was used).
         */
        Optional<Point2d> scannedPoint();
    }

    private List<UnitInPool> enemySiegeTanks = new ArrayList<>();
    private Map<Point, List<UnitInPool>> siegeTankClusters = new HashMap<>();
    private long lastSiegeTankSeenAt = 0L;

    private long siegeTankClustersCalculatedAt = 0L;

    private final Map<ScanRequestTaskMessage, TaskPromise> scanRequests = new HashMap<>();
    private final Map<Point2d, Long> scannedClusters = new HashMap<>();
    private long scanRequestsUpdatedAt = 0L;
    private Map<Point2d, List<Point2d>> scanRequestClusters = new HashMap<>();

    public OrbitalCommandManagerTask(int priority) {
        super(priority);
    }

    @Override
    public void onStepImpl(TaskManager taskManager, AgentWithData agentWithData) {
        long gameLoop = agentWithData.observation().getGameLoop();

        if (getAssignedUnits().size() == 0) {
            return;
        }

        enemySiegeTanks = agentWithData.observation().getUnits(
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
                Optional<UnitInPool> ccWithEnergy = getAssignedUnits().stream()
                        .map(tag -> agentWithData.observation().getUnit(tag))
                        .filter(unitInPool -> unitInPool != null && unitInPool.unit().getEnergy().isPresent() && unitInPool.unit().getEnergy().get() > 50f)
                        .findFirst();
                ccWithEnergy.ifPresent(cc -> {
                    if (agentWithData.observation().getVisibility(target2d) != Visibility.VISIBLE) {
                        agentWithData.actions().unitCommand(cc.unit(), Abilities.EFFECT_SCAN, target2d, false);
                    } else {
                        agentWithData.actions().unitCommand(cc.unit(), Abilities.EFFECT_CALL_DOWN_MULE, target2d, false);
                    }
                });
            }
        }

        if (gameLoop > scanRequestsUpdatedAt + 11L) {
            scanRequestsUpdatedAt = gameLoop;
            if (scanRequests.size() > 0) {
                List<Point2d> requestedPoints = scanRequests.keySet().stream()
                        .filter(request -> request.requiredBefore() > gameLoop)
                        .map(request -> request.point2d())
                        .collect(Collectors.toList());
                scanRequestClusters = Utils.clusterPoints(requestedPoints, 10f);
                Set<ScanRequestTaskMessage> toRemove = new HashSet<>();
                scanRequests.forEach((request, promise) -> {
                    if (gameLoop > request.requiredBefore()) {
                        promise.complete(ImmutableScanRequestTaskMessageResponse.builder()
                                .respondingTask(this)
                                .isSuccess(false)
                                .build());
                        toRemove.add(request);
                    }
                });
                toRemove.forEach(key -> scanRequests.remove(key));
            }
        }

        if (siegeTankClusters.size() == 0) {
            // land mules and scan cloaked items
            float reserveCcEnergy = (scanRequests.size() > 0 || agentWithData.fightManager().hasSeenCloakedOrBurrowedUnits() ? 100f : 50f);
            Set<Point2d> scanClusters = new HashSet<>(agentWithData.fightManager().getCloakedOrBurrowedUnitClusters());
            scanClusters.addAll(scanRequestClusters.keySet());
            new HashMap<>(scannedClusters).forEach((scannedCluster, time) -> {
                // Scan lasts for 12.3 seconds.
                if (gameLoop > time + 22L * 12) {
                    scannedClusters.remove(scannedCluster);
                }
            });
            agentWithData.observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    UnitInPool.isUnit(Units.TERRAN_ORBITAL_COMMAND).test(unitInPool)).forEach(unit -> {
                if (unit.unit().getEnergy().isPresent() && unit.unit().getEnergy().get() > reserveCcEnergy) {
                    Optional<Unit> nearestMineral = Utils.findNearestMineralPatch(agentWithData.observation(), unit.unit().getPosition().toPoint2d());
                    nearestMineral.ifPresent(mineral -> {
                        agentWithData.actions().unitCommand(unit.unit(), Abilities.EFFECT_CALL_DOWN_MULE, mineral, false);
                    });
                }
                if (scanClusters.size() > 0 && unit.unit().getEnergy().isPresent() && unit.unit().getEnergy().get() > 50f) {
                    Set<ScanRequestTaskMessage> scanRequestsToRemove = new HashSet<>();
                    scanClusters.stream().filter(scanPoint ->
                            // Return scan points that are not near an already scanned point.
                            !scannedClusters.keySet().stream()
                                    .anyMatch(alreadyScannedPoint -> alreadyScannedPoint.distance(scanPoint) < 8f)
                    ).findFirst().ifPresent(scanPoint -> {
                        agentWithData.actions().unitCommand(unit.unit(), Abilities.EFFECT_SCAN, scanPoint, false);
                        scannedClusters.put(scanPoint, gameLoop);
                        scanClusters.remove(scanPoint);
                        scanRequests.forEach((request, promise) -> {
                            if (scanPoint.distance(request.point2d()) < 12.0f) {
                                promise.complete(ImmutableScanRequestTaskMessageResponse.builder()
                                        .scannedPoint(scanPoint)
                                        .respondingTask(this)
                                        .isSuccess(true)
                                        .build());
                                scanRequestsToRemove.add(request);
                            }
                        });
                    });
                    scanRequestsToRemove.forEach(key -> scanRequests.remove(key));
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
        return "OCMgr";
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
        return "Orbital Command Manager";
    }

    @Override
    public boolean wantsUnit(Unit unit) {
        return unit.getType() == Units.TERRAN_ORBITAL_COMMAND;
    }

    @Override
    public List<UnitTypeRequest> requestingUnitTypes() {
        return new ArrayList<>();
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        if (message instanceof ScanRequestTaskMessage) {
            ScanRequestTaskMessage request = (ScanRequestTaskMessage)message;
            if (!scanRequests.containsKey(request)) {
                TaskPromise promise = new TaskPromise();
                scanRequests.put(request, promise);
                return Optional.of(promise);
            }
        }
        return Optional.empty();
    }
}
