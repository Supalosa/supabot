package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.Buffs;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.analysis.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A small force of terran units that tries to avoid the enemy army.
 */
public class TerranBioHarrassArmyTask extends AbstractDefaultArmyTask {

    private int basePriority;
    private boolean isComplete = false;

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = 0L;

    public TerranBioHarrassArmyTask(String armyName, int basePriority) {
        super(armyName);
        this.basePriority = basePriority;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        super.onStep(taskManager, data, agent);
        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > desiredCompositionUpdatedAt + 22L) {
            desiredCompositionUpdatedAt = gameLoop;
            updateBioArmyComposition();
        }
        // This army disappears if the overall army is small.
        if (agent.observation().getArmyCount() < 40) {
            this.isComplete = true;
        }
    }

    private void updateBioArmyComposition() {
        List<UnitTypeRequest> result = new ArrayList<>();
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARINE)
                .productionAbility(Abilities.TRAIN_MARINE)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .amount(8)
                .build()
        );
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARAUDER)
                .productionAbility(Abilities.TRAIN_MARAUDER)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .amount(4)
                .build()
        );
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MEDIVAC)
                .productionAbility(Abilities.TRAIN_MEDIVAC)
                .producingUnitType(Units.TERRAN_STARPORT)
                .amount(2)
                .build()
        );
        desiredComposition = result;
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }

    @Override
    protected void attackCommand(ObservationInterface observationInterface,
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
    public List<UnitTypeRequest> requestingUnitTypes() {
        return desiredComposition;
    }

    @Override
    public int getPriority() {
        return basePriority;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        if (otherTask instanceof TerranBioHarrassArmyTask) {
            // only one at a time for now.
            return true;
        }
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

}
