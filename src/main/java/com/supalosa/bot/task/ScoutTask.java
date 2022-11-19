package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.github.ocraft.s2client.protocol.unit.UnitOrder;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.analysis.Tile;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.task.terran.ImmutableScanRequestTaskMessage;
import com.supalosa.bot.utils.UnitComparator;
import com.supalosa.bot.utils.UnitFilter;
import org.immutables.value.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class ScoutTask implements Task {

    private enum ScoutState {
        MOVING,
        LINGERING
    }

    private final String taskKey;

    private Optional<Point2d> scoutTarget;
    private List<Tag> assignedScouters = List.of();
    private final int maxScouters;
    private int usedScouters = 0;
    int targetScouters = 1;
    private long sleepUntil = 0L;
    private int sleepCount = 0;
    private static final int MAX_SLEEP_COUNT = 10;

    private boolean canLinger = false;

    private ScoutState state = ScoutState.MOVING;

    private boolean isComplete = false;

    private Map<Tag, Point2d> previousScouterPosition = new HashMap<>();

    public ScoutTask(Optional<Point2d> point2d, boolean isLingering, int maxScouters) {
        this.scoutTarget = point2d;
        this.taskKey = "SCOUT." + UUID.randomUUID();
        this.maxScouters = maxScouters;
        this.canLinger = isLingering;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentWithData agentWithData) {
        long gameLoop = agentWithData.observation().getGameLoop();
        if (gameLoop < sleepUntil) {
            return;
        }
        if (scoutTarget.isEmpty()) {
            sleepUntil = gameLoop + 22L;
            ++sleepCount;
            if (sleepCount > MAX_SLEEP_COUNT) {
                isComplete = true;
            } else {
                // Scout for potential expansions.
                scoutTarget = findNewScoutTarget(agentWithData);
            }
            return;
        }
        if (usedScouters > maxScouters) {
            // Too many scouters used, request a scan within 30 seconds and abort.
            scoutTarget.ifPresent(target -> {
                this.requestScannerSweep(agentWithData, target, gameLoop + 22L * 30);
            });
            isComplete = true;
            return;
        }
        if (!canLinger && agentWithData.observation().getVisibility(scoutTarget.get()) == Visibility.VISIBLE) {
            scoutTarget = findNewScoutTarget(agentWithData);
            sleepCount = 0;
            return;
        }
        List<Unit> scouters = assignedScouters.stream()
                .map(tag -> agentWithData.observation().getUnit(tag))
                .filter(unitInPool -> unitInPool != null)
                .map(UnitInPool::unit)
                .collect(Collectors.toList());

        if (scouters.size() < targetScouters) {
            Optional<UnitInPool> maybeScouter = taskManager.findFreeUnitForTask(this,
                    agentWithData.observation(),
                UnitFilter.builder()
                        .alliance(Alliance.SELF)
                        .unitTypes(Set.of(Units.TERRAN_SCV, Units.TERRAN_MARINE))
                        .build());
            maybeScouter.ifPresent(scouter -> {
                scouters.add(scouter.unit());
                ++usedScouters;
            });
        }

        assignedScouters = scouters.stream().map(unit -> unit.getTag()).collect(Collectors.toList());
        if (scouters.size() > 0) {
            if (state == ScoutState.MOVING) {
                scouters.forEach(scouter -> handleMovingScout(scouter, agentWithData));
            } else if (state == ScoutState.LINGERING) {
                scouters.forEach(scouter -> handleLingeringScout(scouter, agentWithData));
            }
        }
    }

    private void handleMovingScout(Unit scouter, AgentWithData agentWithData) {
        if (scoutTarget.isEmpty()) {
            return;
        }
        Optional<UnitOrder> currentOrder = scouter.getOrders().stream().findFirst();

        Optional<Point2d> previousPosition = Optional.ofNullable(previousScouterPosition.get(scouter.getTag()));
        previousScouterPosition.put(scouter.getTag(), scouter.getPosition().toPoint2d());

        if (previousPosition.isPresent() && previousPosition.get().equals(scouter.getPosition().toPoint2d())) {
            // Scouter is stuck, try mineral walking to target.
            Optional<UnitInPool> minerals = agentWithData.observation().getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.NEUTRAL)
                            .unitTypes(Constants.MINERAL_TYPES)
                            .inRangeOf(scoutTarget.get())
                            .range(20f)
                            .build())
                    .stream().sorted(UnitComparator.builder().distanceToPoint(scoutTarget.get()).ascending(true).build())
                    .findFirst();
            minerals.ifPresentOrElse(mineral -> {
                agentWithData.actions().unitCommand(scouter, Abilities.SMART, mineral.unit(), false);
            }, () -> {
                if (!isAlreadyMovingTo(scoutTarget.get(), currentOrder)) {
                    agentWithData.actions().unitCommand(scouter, Abilities.SMART, scoutTarget.get(), false);
                }
            });
        } else {
            if (!isAlreadyMovingTo(scoutTarget.get(), currentOrder)) {
                agentWithData.actions().unitCommand(scouter, Abilities.MOVE, scoutTarget.get(), false);
            }
        }

        if (canLinger &&
                scouter.getPosition().toPoint2d().distance(scoutTarget.get()) < 10f &&
                agentWithData.observation().getVisibility(scoutTarget.get()) == Visibility.VISIBLE) {
            state = ScoutState.LINGERING;
        }
    }

    private void handleLingeringScout(Unit scouter, AgentWithData agentWithData) {
        if (scoutTarget.isEmpty()) {
            return;
        }
        Optional<UnitOrder> currentOrder = scouter.getOrders().stream().findFirst();

        Optional<RegionData> regionToScout = agentWithData.mapAwareness().getRegionDataForPoint(scoutTarget.get());
        if (regionToScout.isPresent()) {
            RegionData regionData = regionToScout.get();
            Optional<Point> maybeCurrentTarget = currentOrder.flatMap(UnitOrder::getTargetedWorldSpacePosition);
            if (currentOrder.isEmpty() ||
                    (maybeCurrentTarget.isPresent() &&
                            scouter.getPosition().distance(maybeCurrentTarget.get()) < 1.5)) {
                // Score tiles by distance and visibility.
                int iterations = 50;
                Optional<Point2d> destination = Optional.empty();
                double maxScore = Double.MIN_VALUE;
                List<Point2d> tiles = new ArrayList<>(regionData.region().getTiles());
                Set<Point2d> visited = new HashSet<>();
                while (iterations-- > 0) {
                    Point2d maybeTile = tiles.get(ThreadLocalRandom.current().nextInt(tiles.size()));
                    if (!visited.contains(maybeTile)) {
                        visited.add(maybeTile);
                        double distance = scoutTarget.get().distance(maybeTile);
                        Visibility visibility = agentWithData.observation().getVisibility(maybeTile);
                        double score = distance;
                        if (visibility == Visibility.FULL_HIDDEN || visibility == Visibility.HIDDEN) {
                            score *= 2;
                        } else if (visibility == Visibility.FOGGED) {
                            score *= 1.5;
                        }
                        if (score > maxScore) {
                            maxScore = score;
                            destination = Optional.of(maybeTile);
                        }
                    }
                }
                destination.ifPresentOrElse(target -> {
                    if (!isAlreadyMovingTo(scoutTarget.get(), currentOrder)) {
                        agentWithData.actions().unitCommand(scouter, Abilities.MOVE, target, false);
                    }
                }, () -> {
                    if (!isAlreadyMovingTo(scoutTarget.get(), currentOrder)) {
                        agentWithData.actions().unitCommand(scouter, Abilities.MOVE, scoutTarget.get(), false);
                    }
                });
            }
        } else {
            if (!isAlreadyMovingTo(scoutTarget.get(), currentOrder)) {
                agentWithData.actions().unitCommand(scouter, Abilities.MOVE, scoutTarget.get(), false);
            }
        }
    }
    private static boolean isAlreadyMovingTo(Point2d position, Optional<UnitOrder> order) {
        return isAlreadyUsingAbilityAt(Abilities.MOVE, position, order);
    }

    private static boolean isAlreadyUsingAbilityAt(Ability ability, Point2d position, Optional<UnitOrder> order) {
        // Do not allow the same ability to be used within 0.5 distance of the existing order.
        return order.filter(unitOrder -> unitOrder.getAbility().equals(ability))
                .flatMap(UnitOrder::getTargetedWorldSpacePosition)
                .map(Point::toPoint2d)
                .map(position::distance)
                .filter(distance -> distance < 0.5)
                .isPresent();
    }

    private Optional<Point2d> findNewScoutTarget(AgentWithData agentWithData) {
        return agentWithData.enemyAwareness().getLeastConfidentEnemyExpansion()
                .map(RegionData::region)
                .map(Region::centrePoint)
                .or(() -> agentWithData.mapAwareness().getNextScoutTarget());
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
        if (!(otherTask instanceof ScoutTask)) {
            return false;
        }
        ScoutTask task = (ScoutTask)otherTask;
        return (task.scoutTarget.equals(this.scoutTarget));
    }

    @Override
    public void debug(S2Agent agent) {
        if (this.scoutTarget.isPresent()) {
            float height = agent.observation().terrainHeight(scoutTarget.get());
            Point point3d = Point.of(scoutTarget.get().getX(), scoutTarget.get().getY(), height);
            Color color = Color.YELLOW;
            agent.debug().debugSphereOut(point3d, 1.0f, color);
            agent.debug().debugTextOut(
                    "Scout(" + usedScouters + "/" + maxScouters + ")",
                    point3d, Color.WHITE, 10);
        }
    }

    @Override
    public String getDebugText() {
        return "Scout " + scoutTarget.map(point2d -> point2d.toString()).orElse("<unknown>") + " x(" + usedScouters + "/" + maxScouters + ")";
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
    }

    private List<TaskPromise> requestScannerSweep(AgentData data, Point2d scanPosition, long scanRequiredBefore) {
        return data.taskManager().dispatchMessage(this,
                ImmutableScanRequestTaskMessage.builder()
                        .point2d(scanPosition)
                        .requiredBefore(scanRequiredBefore)
                        .build());
    }
}
