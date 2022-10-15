package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.TaskResult;

import java.util.*;
import java.util.stream.Collectors;

public abstract class AbstractDefaultArmyTask implements ArmyTask {

    protected final String armyName;
    protected final Map<Tag, Float> rememberedUnitHealth = new HashMap<>();
    protected Set<Tag> armyUnits = new HashSet<>();
    protected Optional<Point2d> targetPosition = Optional.empty();
    protected Optional<Point2d> retreatPosition = Optional.empty();
    protected Optional<Point2d> centreOfMass = Optional.empty();
    protected boolean isRegrouping = false;
    protected Optional<Region> currentRegion = Optional.empty();
    protected Optional<Region> targetRegion = Optional.empty();
    protected List<Region> regionWaypoints = new ArrayList<>();
    protected Optional<Region> waypointsCalculatedFrom = Optional.empty();
    protected Optional<Region> waypointsCalculatedAgainst = Optional.empty();
    protected long waypointsCalculatedAt = 0L;
    protected long centreOfMassLastUpdated = 0L;
    protected MicroState microState = MicroState.BALANCED;
    protected Map<UnitType, Integer> currentComposition = new HashMap<>();
    protected MapAwareness.PathRules pathRules = MapAwareness.PathRules.AVOID_KILL_ZONE;

    public AbstractDefaultArmyTask(String armyName) {
        this.armyName = armyName;
    }

    @Override
    public void setTargetPosition(Optional<Point2d> targetPosition) {
        this.targetPosition = targetPosition;
    }

    @Override
    public void setRetreatPosition(Optional<Point2d> retreatPosition) {
        this.retreatPosition = retreatPosition;
    }

    @Override
    public int getSize() {
        return this.armyUnits.size();
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        List<Point2d> armyPositions = new ArrayList<>();
        currentComposition.clear();
        armyUnits = armyUnits.stream().filter(tag -> {
                    UnitInPool unit = agent.observation().getUnit(tag);
                    if (unit != null) {
                        armyPositions.add(unit.unit().getPosition().toPoint2d());
                        currentComposition.put(
                                unit.unit().getType(),
                                currentComposition.getOrDefault(unit.unit().getType(), 0) + 1);
                    }
                    return (unit != null && unit.isAlive());
                })
                .collect(Collectors.toSet());

        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > centreOfMassLastUpdated + 22L) {
            centreOfMassLastUpdated = gameLoop;
            updateCentreOfMassAndAttack(data, agent, armyPositions);
        }

        // Handle pathfinding.
        updateCurrentRegions(data);

        if (gameLoop > waypointsCalculatedAt + 44L) {
            waypointsCalculatedAt = gameLoop;
            calculateNewPath(data);
        }
    }

    protected int getAmountOfUnit(UnitType type) {
        return currentComposition.getOrDefault(type, 0);
    }

    /**
     * Default implementation of wantsUnit that looks at the current and desired composition.
     */
    @Override
    public boolean wantsUnit(Unit unit) {
        return requestingUnitTypes().stream().anyMatch(request ->
                request.unitType().equals(unit.getType()) && getAmountOfUnit(unit.getType()) < request.amount());
    }

    private void calculateNewPath(AgentData data) {
        // Target has changed, clear pathfinding.
        if (!waypointsCalculatedAgainst.equals(targetRegion)) {
            waypointsCalculatedAgainst = Optional.empty();
            regionWaypoints.clear();
        }
        // Calculate path every time. TODO: probably don't need to do this, cut down in the future.
        if (currentRegion.isPresent() && targetRegion.isPresent() && !currentRegion.equals(targetRegion)) {
            Optional<List<Region>> maybePath = data
                    .mapAwareness()
                    .generatePath(currentRegion.get(), targetRegion.get(), pathRules);
            maybePath.ifPresent(path -> {
                regionWaypoints = path;
                waypointsCalculatedFrom = currentRegion;
                waypointsCalculatedAgainst = targetRegion;
            });
        }
    }

    private void updateCurrentRegions(AgentData data) {
        targetRegion = targetPosition.flatMap(position ->
                data.mapAwareness().getRegionDataForPoint(position).map(RegionData::region));
        // TODO if regrouping, is current region valid? It will end up thinking the unit is in a region halfway between
        // the clumps.
        currentRegion = centreOfMass.flatMap(centre ->
                data.mapAwareness().getRegionDataForPoint(centre).map(RegionData::region));
        if (currentRegion.isPresent() && regionWaypoints.size() > 0 && (
                currentRegion.get().equals(regionWaypoints.get(0)) ||
                        (centreOfMass.isPresent() && regionWaypoints.get(0).centrePoint().distance(centreOfMass.get()) < 2.5f)
        )) {
            // Arrived at the head waypoint.
            regionWaypoints.remove(0);
            if (regionWaypoints.size() > 0) {
                waypointsCalculatedFrom = Optional.of(regionWaypoints.get(0));
            } else {
                // Finished path.
                waypointsCalculatedAgainst = Optional.empty();
                waypointsCalculatedFrom = Optional.empty();
            }
        }
    }

    private void updateCentreOfMassAndAttack(AgentData data, S2Agent agent, List<Point2d> armyPositions) {
        OptionalDouble averageX = armyPositions.stream().mapToDouble(point -> point.getX()).average();
        OptionalDouble averageY = armyPositions.stream().mapToDouble(point -> point.getY()).average();
        centreOfMass = Optional.empty();
        if (averageX.isPresent() && averageY.isPresent()) {
            centreOfMass = Optional.of(Point2d.of((float)averageX.getAsDouble(), (float)averageY.getAsDouble()));
        }
        attackCommand(
                agent.observation(),
                agent.actions(),
                centreOfMass,
                data.mapAwareness().getMaybeEnemyArmy());
    }

    /**
     * Override this to handle how the army's units handle being told to attack or move.
     * It's basically a periodic 'update' command. Maybe I should rename it.
     */
    protected abstract void attackCommand(ObservationInterface observationInterface,
                                          ActionInterface actionInterface,
                                          Optional<Point2d> centreOfatMass,
                                          Optional<Army> maybeEnemyArmy);

    @Override
    public boolean addUnit(Tag unitTag) {
        return armyUnits.add(unitTag);
    }

    @Override
    public boolean hasUnit(Tag unitTag) {
        return armyUnits.contains(unitTag);
    }

    @Override
    public void onUnitIdle(UnitInPool unitTag) {

    }

    @Override
    public void setPathRules(MapAwareness.PathRules pathRules) {
        this.pathRules = pathRules;
    }

    /**
     * Take all units from the other army. The other army becomes an empty army.
     */
    public void takeAllFrom(TerranBioArmyTask otherArmy) {
        if (otherArmy == this) {
            return;
        }
        this.armyUnits.addAll(otherArmy.armyUnits);
        otherArmy.armyUnits.clear();
    }

    @Override
    public Optional<TaskResult> getResult() {
        return Optional.empty();
    }

    @Override
    public String getKey() {
        return "ATTACK." + armyName;
    }

    @Override
    public String getDebugText() {
        return "Army (" + armyName + ") " + targetPosition.map(point2d -> point2d.getX() + "," + point2d.getY()).orElse("?") +
                " (" + armyUnits.size() + ")";
    }

    @Override
    public Optional<List<Region>> getWaypoints() {
        return Optional.of(this.regionWaypoints);
    }

    @Override
    public Optional<Point2d> getCentreOfMass() {
        return centreOfMass;
    }

    @Override
    public Optional<Point2d> getTargetPosition() {
        return targetPosition;
    }

    @Override
    public void setMicroState(MicroState microState) {
        this.microState = microState;
    }
}
