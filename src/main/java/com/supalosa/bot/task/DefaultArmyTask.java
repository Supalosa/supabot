package com.supalosa.bot.task;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.analysis.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.awareness.RegionData;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class DefaultArmyTask implements ArmyTask {

    public Set<Tag> armyUnits = new HashSet<>();
    public Optional<Point2d> targetPosition = Optional.empty();
    public Optional<Point2d> retreatPosition = Optional.empty();

    private final Map<Tag, Float> rememberedUnitHealth = new HashMap<>();

    private Optional<Point2d> centreOfMass = Optional.empty();
    private boolean isRegrouping = false;

    private MapAwareness.PathRules pathingRules = MapAwareness.PathRules.AVOID_KILL_ZONE;
    private Optional<Region> currentRegion = Optional.empty();
    private Optional<Region> targetRegion = Optional.empty();
    private List<Region> regionWaypoints = new ArrayList<>();
    private Optional<Region> waypointsCalculatedFrom = Optional.empty();
    private Optional<Region> waypointsCalculatedAgainst = Optional.empty();
    private long waypointsCalculatedAt = 0L;

    private long centreOfMassLastUpdated = 0L;

    private int numMedivacs = 0;

    private final String armyName;

    public DefaultArmyTask(String armyName) {
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
        armyUnits = armyUnits.stream().filter(tag -> {
                    UnitInPool unit = agent.observation().getUnit(tag);
                    if (unit != null) {
                        armyPositions.add(unit.unit().getPosition().toPoint2d());
                        if (unit.unit().getType() == Units.TERRAN_MEDIVAC) {
                            numMedivacs++;
                        }
                    }
                    return (unit != null && unit.isAlive());
                })
                .collect(Collectors.toSet());

        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > centreOfMassLastUpdated + 22L) {
            centreOfMassLastUpdated = gameLoop;
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

        // Handle pathfinding.
        targetRegion = targetPosition.flatMap(position ->
                data.mapAwareness().getRegionDataForPoint(position).map(RegionData::region));
        // TODO if regrouping is current region valid?
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

        if (gameLoop > waypointsCalculatedAt + 44L) {
            waypointsCalculatedAt = gameLoop;
            // Target has changed, clear pathfinding.
            if (!waypointsCalculatedAgainst.equals(targetRegion)) {
                waypointsCalculatedAgainst = Optional.empty();
                regionWaypoints.clear();
            }
            // Calculate path every time - TODO probably don't need to.
            if (currentRegion.isPresent() && targetRegion.isPresent() && !currentRegion.equals(targetRegion)) {
                Optional<List<Region>> maybePath = data
                        .mapAwareness()
                        .generatePath(currentRegion.get(), targetRegion.get(), pathingRules);
                maybePath.ifPresent(path -> {
                    regionWaypoints = path;
                    waypointsCalculatedFrom = currentRegion;
                    waypointsCalculatedAgainst = targetRegion;
                });
            }
        }
    }

    private void attackCommand(ObservationInterface observationInterface,
                               ActionInterface actionInterface,
                               Optional<Point2d> centreOfatMass,
                               Optional<Army> maybeEnemyArmy) {
        Set<Tag> unitsToAttackWith = new HashSet<>(armyUnits);
        boolean attackWithAll = false;
        if (unitsToAttackWith.size() > 0) {
            // TODO better detection of 'move-only' units.
            Set<Tag> unitsThatMustMove = observationInterface.getUnits(unitInPool ->
                    unitsToAttackWith.contains(unitInPool.getTag()) && unitInPool.unit().getType() == Units.TERRAN_MEDIVAC
            ).stream().map(unitInPool -> unitInPool.getTag()).collect(Collectors.toSet());
            if (centreOfMass.isPresent()) {
                List<Unit> farUnits = new ArrayList<>();
                List<Unit> nearUnits = observationInterface.getUnits(unitInPool ->
                                unitsToAttackWith.contains(unitInPool.getTag())).stream()
                        .map(uip -> uip.unit())
                        .filter(unit -> {
                            if (unit.getPosition().toPoint2d().distance(centreOfMass.get()) < (isRegrouping ? 5f : 10f)) {
                                return true;
                            } else {
                                farUnits.add(unit);
                                return false;
                            }
                        })
                        .collect(Collectors.toList());
                if (nearUnits.size() > unitsToAttackWith.size() * 0.75) {
                    isRegrouping = false;
                } else if (nearUnits.size() < unitsToAttackWith.size() * 0.35) {
                    isRegrouping = true;
                }
                if (!isRegrouping) {
                    attackWithAll = true;
                } else {
                    // Units far away from the centre of mass should run there.
                    if (farUnits.size() > 0) {
                        // Bit of a hack but sometimes attacking and moving helps
                        Ability ability = ThreadLocalRandom.current().nextBoolean() ? Abilities.ATTACK : Abilities.MOVE;
                        centreOfMass.ifPresent(point2d ->
                                actionInterface.unitCommand(farUnits, ability, point2d, false));
                    }
                    // Units near the centre of mass can attack move.
                    if (nearUnits.size() > 0) {
                        targetPosition.ifPresent(point2d ->
                                actionInterface.unitCommand(nearUnits, Abilities.ATTACK, point2d, false));
                    }
                }
            } else {
                attackWithAll = true;
            }
            if (attackWithAll) {
                unitsToAttackWith.removeAll(unitsThatMustMove);
                Optional<Point2d> positionToAttackMove = targetPosition;
                // Handle pathfinding.
                if (waypointsCalculatedAgainst.isPresent() && regionWaypoints.size() > 0) {
                    Region head = regionWaypoints.get(0);
                    if (targetRegion.isPresent() && targetRegion.get().equals(head)) {
                        // Arrived; attack the target.
                        positionToAttackMove = targetPosition;
                    } else {
                        // Attack move to centre of the next region.
                        positionToAttackMove = Optional.of(head.centrePoint());
                    }
                } else {
                    //System.out.println("No waypoint target or no points (" + regionWaypoints.size() + ")");
                }
                if (unitsToAttackWith.size() > 0) {
                    positionToAttackMove.ifPresentOrElse(point2d ->
                                    actionInterface.unitCommand(unitsToAttackWith, Abilities.ATTACK, point2d, false),
                            () -> retreatPosition.ifPresent(point2d ->
                                    actionInterface.unitCommand(unitsToAttackWith,
                                    Abilities.MOVE, point2d, false)));
                }
                if (unitsThatMustMove.size() > 0) {
                    centreOfMass.ifPresentOrElse(point2d ->
                                    actionInterface.unitCommand(unitsThatMustMove, Abilities.ATTACK, point2d, false),
                            () -> retreatPosition.ifPresent(point2d ->
                                    actionInterface.unitCommand(unitsToAttackWith, Abilities.MOVE, point2d, false)));
                }
                if (maybeEnemyArmy.isPresent()) {
                    // TODO this belongs in a method.
                    AtomicInteger stimmedMarines = new AtomicInteger(0);
                    AtomicInteger stimmedMarauders = new AtomicInteger(0);
                    Set<Tag> marinesWithoutStim = observationInterface.getUnits(unitInPool ->
                            unitsToAttackWith.contains(unitInPool.getTag()) &&
                                    (unitInPool.unit().getType() == Units.TERRAN_MARINE) &&
                                    unitInPool.unit().getPosition().toPoint2d().distance(maybeEnemyArmy.get().position()) < 10f &&
                                    unitInPool.unit().getHealth().filter(health -> health > 25f).isPresent()
                    ).stream().filter(unitInPool -> {
                        if (unitInPool.unit().getBuffs().contains(Buffs.STIMPACK)) {
                            stimmedMarines.incrementAndGet();
                            return false;
                        } else {
                            return true;
                        }
                    }).map(unitInPool -> unitInPool.getTag()).collect(Collectors.toSet());

                    Set<Tag> maraudersWithoutStim = observationInterface.getUnits(unitInPool ->
                            unitsToAttackWith.contains(unitInPool.getTag()) &&
                                    (unitInPool.unit().getType() == Units.TERRAN_MARAUDER) &&
                                    unitInPool.unit().getPosition().toPoint2d().distance(maybeEnemyArmy.get().position()) < 10f &&
                                    unitInPool.unit().getHealth().filter(health -> health > 40f).isPresent()
                    ).stream().filter(unitInPool -> {
                        if (unitInPool.unit().getBuffs().contains(Buffs.STIMPACK_MARAUDER)) {
                            stimmedMarauders.incrementAndGet();
                            return false;
                        } else {
                            return true;
                        }
                    }).map(unitInPool -> unitInPool.getTag()).collect(Collectors.toSet());
                    // Stim 1:1 ratio
                    int stimsRequested = Math.max(0, (int)maybeEnemyArmy.get().size() - stimmedMarines.get() - stimmedMarauders.get());
                    marinesWithoutStim =
                            marinesWithoutStim.stream().limit(stimsRequested).collect(Collectors.toSet());
                    if (marinesWithoutStim.size() > 0) {
                        actionInterface.unitCommand(marinesWithoutStim, Abilities.EFFECT_STIM_MARINE, false);
                        stimsRequested -= marinesWithoutStim.size();
                    }
                    maraudersWithoutStim = maraudersWithoutStim.stream().limit(stimsRequested).collect(Collectors.toSet());
                    if (maraudersWithoutStim.size() > 0) {
                        actionInterface.unitCommand(maraudersWithoutStim, Abilities.EFFECT_STIM_MARAUDER, false);
                    }
                }
            }
        }
    }

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
    public List<UnitTypeRequest> requestingUnitTypes() {
        // TODO cache this.
        List<UnitTypeRequest> result = new ArrayList<>();
        result.add(ImmutableUnitTypeRequest.builder()
                        .unitType(Units.TERRAN_MARINE)
                        .productionAbility(Abilities.TRAIN_MARINE)
                        .producingUnitType(Units.TERRAN_BARRACKS)
                        .amount(1000)
                        .build()
                );
        if (armyUnits.size() > 40) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_MARAUDER)
                    .productionAbility(Abilities.TRAIN_MARAUDER)
                    .producingUnitType(Units.TERRAN_BARRACKS)
                    .amount(10)
                    .build()
            );
        }
        if (armyUnits.size() > 10 && numMedivacs < armyUnits.size() * 0.1) {
            if (numMedivacs < armyUnits.size() * 0.05) {
                result.clear();
            }
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_MEDIVAC)
                    .productionAbility(Abilities.TRAIN_MEDIVAC)
                    .producingUnitType(Units.TERRAN_STARPORT)
                    .amount(10)
                    .build()
            );
        }
        return result;
    }

    /**
     * Take all units from the other army. The other army becomes an empty army.
     */
    public void takeAllFrom(DefaultArmyTask otherArmy) {
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
    public boolean isComplete() {
        return false;
    }

    @Override
    public String getKey() {
        return "ATTACK." + armyName;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        return false;
    }

    @Override
    public void debug(S2Agent agent) {
        centreOfMass.ifPresent(point2d -> {
            float z = agent.observation().terrainHeight(point2d);
            Point point = Point.of(point2d.getX(), point2d.getY(), z);
            agent.debug().debugSphereOut(point, isRegrouping ? 5f : 10f, Color.YELLOW);
            agent.debug().debugTextOut(this.armyName, point, Color.WHITE, 8);
        });
        targetPosition.ifPresent(point2d -> {
            float z = agent.observation().terrainHeight(point2d);
            Point point = Point.of(point2d.getX(), point2d.getY(), z);
            agent.debug().debugSphereOut(point, 1f, Color.RED);
            agent.debug().debugTextOut(this.armyName, point, Color.WHITE, 8);
        });
        retreatPosition.ifPresent(point2d -> {
            float z = agent.observation().terrainHeight(point2d);
            Point point = Point.of(point2d.getX(), point2d.getY(), z);
            agent.debug().debugSphereOut(point, 1f, Color.YELLOW);
            agent.debug().debugTextOut(this.armyName, point, Color.WHITE, 8);
        });
        if (centreOfMass.isPresent() && regionWaypoints.size() > 0) {
            Point2d startPoint = centreOfMass.get();
            Point lastPoint = Point.of(startPoint.getX(), startPoint.getY(), agent.observation().terrainHeight(startPoint)+1f);
            for (Region waypoint : regionWaypoints) {
                Point2d nextPoint = waypoint.centrePoint();
                Point newPoint = Point.of(nextPoint.getX(), nextPoint.getY(), agent.observation().terrainHeight(nextPoint)+1f);

                agent.debug().debugSphereOut(newPoint, 1f, Color.WHITE);
                agent.debug().debugLineOut(lastPoint, newPoint, Color.WHITE);
                lastPoint = newPoint;
            }
        }
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
}
