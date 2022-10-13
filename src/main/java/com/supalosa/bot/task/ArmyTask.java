package com.supalosa.bot.task;

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
import com.supalosa.bot.awareness.Army;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ArmyTask implements Task {

    public Set<Tag> armyUnits = new HashSet<>();
    public Optional<Point2d> targetPosition = Optional.empty();
    public Optional<Point2d> retreatPosition = Optional.empty();

    private final Map<Tag, Float> rememberedUnitHealth = new HashMap<>();

    private Optional<Point2d> centreOfMass = Optional.empty();
    private long centreOfMassLastUpdated = 0L;

    private final String armyName;

    public ArmyTask(String armyName) {
        this.armyName = armyName;
    }

    public void setTargetPosition(Optional<Point2d> targetPosition) {
        this.targetPosition = targetPosition;
    }

    public void setRetreatPosition(Optional<Point2d> retreatPosition) {
        this.retreatPosition = retreatPosition;
    }

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

    }

    private boolean isRegrouping = false;

    private void attackCommand(ObservationInterface observationInterface,
                               ActionInterface actionInterface,
                               Optional<Point2d> centreOfMass,
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
                if (unitsToAttackWith.size() > 0) {
                    targetPosition.ifPresentOrElse(point2d ->
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
                    // TODO this belongs in a task.
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

    public boolean addUnit(Tag unitTag) {
        return armyUnits.add(unitTag);
    }

    public boolean hasUnit(Tag unitTag) {
        return armyUnits.contains(unitTag);
    }

    public void onUnitIdle(UnitInPool unitTag) {

    }

    public ArmyTask takeFrom(ArmyTask otherArmy) {
        if (otherArmy == this) {
            return this;
        }
        this.armyUnits.addAll(otherArmy.armyUnits);
        otherArmy.armyUnits.clear();
        return this;
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
    }

    @Override
    public String getDebugText() {
        return "Army (" + armyName + ") " + targetPosition.map(point2d -> point2d.getX() + "," + point2d.getY()).orElse("?") +
                " (" + armyUnits.size() + ")";
    }
}
