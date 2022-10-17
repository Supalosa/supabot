package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.analysis.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.engagement.TerranBioThreatCalculator;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.message.*;
import com.supalosa.bot.task.terran.ImmutableScanRequestTaskMessage;
import com.supalosa.bot.task.terran.OrbitalCommandManagerTask;
import com.supalosa.bot.utils.UnitFilter;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A permanent bio army that constantly asks for reinforcements.
 */
public class TerranBioArmyTask extends DefaultArmyTask {

    private int numMedivacs = 0;
    private int basePriority;

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = 0L;

    public TerranBioArmyTask(String armyName, int basePriority) {
        super(armyName, basePriority, new TerranBioThreatCalculator());
        this.basePriority = basePriority;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        super.onStep(taskManager, data, agent);
        long gameLoop = agent.observation().getGameLoop();
        numMedivacs = 0;
        armyUnits.forEach(tag -> {
            UnitInPool unit = agent.observation().getUnit(tag);
            if (unit != null) {
                if (unit.unit().getType() == Units.TERRAN_MEDIVAC) {
                    numMedivacs++;
                }
            }
        });

        if (gameLoop > desiredCompositionUpdatedAt + 22L) {
            desiredCompositionUpdatedAt = gameLoop;
            updateBioArmyComposition();
        }
    }

    private void updateBioArmyComposition() {
        List<UnitTypeRequest> result = new ArrayList<>();
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARINE)
                .productionAbility(Abilities.TRAIN_MARINE)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .amount(40)
                .build()
        );
        if (armyUnits.size() > 5) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_MARAUDER)
                    .productionAbility(Abilities.TRAIN_MARAUDER)
                    .producingUnitType(Units.TERRAN_BARRACKS)
                    .needsTechLab(true)
                    .amount(40)
                    .build()
            );
        }
        if (armyUnits.size() > 10) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_WIDOWMINE)
                    .alternateForm(Units.TERRAN_WIDOWMINE_BURROWED)
                    .productionAbility(Abilities.TRAIN_WIDOWMINE)
                    .producingUnitType(Units.TERRAN_FACTORY)
                    .needsTechLab(true)
                    .amount(10)
                    .build()
            );
        }
        if (armyUnits.size() > 10 && numMedivacs < armyUnits.size() * 0.1) {
            result.add(ImmutableUnitTypeRequest.builder()
                    .unitType(Units.TERRAN_MEDIVAC)
                    .productionAbility(Abilities.TRAIN_MEDIVAC)
                    .producingUnitType(Units.TERRAN_STARPORT)
                    .amount((int)(Math.min(10, armyUnits.size() * 0.1)))
                    .build()
            );
        }
        desiredComposition = result;
    }

    @Override
    public boolean isComplete() {
        // This army is a permanent one.
        return false;
    }

    @Override
    protected AggressionState attackCommand(S2Agent agent,
                                 AgentData data,
                                 Optional<Point2d> centreOfMass,
                                 Optional<Point2d> suggestedAttackMovePosition,
                                 Optional<Point2d> suggestedRetreatMovePosition,
                                 Optional<Army> maybeEnemyArmy) {
        AggressionState parentState = super.attackCommand(agent, data, centreOfMass,
                suggestedAttackMovePosition, suggestedRetreatMovePosition, maybeEnemyArmy);

        ObservationInterface observationInterface = agent.observation();
        ActionInterface actionInterface = agent.actions();

        if (parentState == AggressionState.ATTACKING && armyUnits.size() > 0) {
            Optional<Point2d> positionToAttackMove = suggestedAttackMovePosition;
            // Stutter step towards/away from enemy.
            // TODO: the retreat position should actually be pathfinded away from the enemy.
            // Move towards the enemy if we're winning, otherwise kite them.
            Optional<Point2d> movePoint = suggestedRetreatMovePosition;
            Optional<Point2d> attackPoint = positionToAttackMove.isPresent() ? positionToAttackMove : retreatPosition;
            if (movePoint.isPresent()) {
                // If we're in a kill zone, randomise the move point (by a lot) to spread the army.
                Optional<RegionData> maybeRegionData = data.mapAwareness().getRegionDataForPoint(movePoint.get());
                if (maybeRegionData.isPresent()) {
                    double killzoneFactor = maybeRegionData.get().killzoneFactor();
                    if (killzoneFactor > 1.0) {
                        float randomX = movePoint.get().getX() + (ThreadLocalRandom.current().nextFloat() - 0.5f) * (float)killzoneFactor;
                        float randomY = movePoint.get().getY() + (ThreadLocalRandom.current().nextFloat() - 0.5f) * (float)killzoneFactor;
                        movePoint = Optional.of(Point2d.of(Math.max(0f, randomX), Math.max(0f, randomY)));
                    }
                }
                Optional<Point2d> finalMovePoint = movePoint;
                armyUnits.forEach(tag -> {
                    UnitInPool unit = observationInterface.getUnit(tag);
                    if (unit != null) {
                        // Empty weapon = no attack - so just scan-attack there.
                        if (unit.unit().getWeaponCooldown().isEmpty() ||
                                (unit.unit().getWeaponCooldown().isPresent() && unit.unit().getWeaponCooldown().get() < 0.01f)) {
                            actionInterface.unitCommand(tag, Abilities.ATTACK, attackPoint.get(), false);
                        } else {
                            actionInterface.unitCommand(tag, Abilities.MOVE, finalMovePoint.get(), false);
                        }
                    }
                });
            }
            if (maybeEnemyArmy.isPresent()) {
                calculateStimPack(observationInterface, actionInterface, maybeEnemyArmy, armyUnits);
                calculateWidowMines(centreOfMass, maybeEnemyArmy, observationInterface, actionInterface);
            } else {
                if (getAmountOfUnit(Units.TERRAN_WIDOWMINE_BURROWED) > 0) {
                    actionInterface.unitCommand(armyUnits, Abilities.BURROW_UP_WIDOWMINE, false);
                }
            }
            // Scan if creep is nearby and there is no known base here.
            // Also scan highground if killzone threat is high.
            if (centreOfMass.isPresent()) {
                final long scanRequiredBefore = observationInterface.getGameLoop() + 66L;
                Optional<RegionData> thisRegion = centreOfMass.flatMap(point2d -> data.mapAwareness().getRegionDataForPoint(point2d));
                thisRegion.ifPresent(region -> {
                    if (region.estimatedCreepPercentage() > 0.25f && !region.hasEnemyBase()) {
                        requestScannerSweep(data, centreOfMass.get(), scanRequiredBefore)
                                .forEach(promise -> {
                            promise.thenAccept(response -> {
                                if (response instanceof OrbitalCommandManagerTask.ScanRequestTaskMessageResponse) {
                                    if (response.isSuccess()) {
                                        Optional<Point2d> scannedPoint = ((OrbitalCommandManagerTask.ScanRequestTaskMessageResponse) response).scannedPoint();
                                        System.out.println("Bio army got a scan back [creep scan].");
                                    } else {
                                        System.out.println("Bio army scan was rejected [creep scan].");
                                    }
                                }
                            });
                        });
                    }
                    if (region.killzoneFactor() > 2.5) {
                        // Find the (highest threat X lowest visibility) region near us and scan the midpoint between current region
                        // and that region.
                        List<RegionData> connectedRegionsByThreat = region.region().connectedRegions().stream()
                                .map(nextRegionId -> data.mapAwareness().getRegionDataForId(nextRegionId))
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .sorted(Comparator.comparing((RegionData nextRegion) ->
                                        nextRegion.enemyThreat() * (1.0 - nextRegion.visibilityPercent())
                                ).reversed())
                                .collect(Collectors.toList());
                        if (connectedRegionsByThreat.size() > 0) {
                            RegionData head = connectedRegionsByThreat.get(0);
                            Point2d midPoint = head.region().centrePoint().add(region.region().centrePoint()).div(2f);
                            requestScannerSweep(data, midPoint, scanRequiredBefore)
                                    .forEach(promise -> {
                                        promise.thenAccept(response -> {
                                            if (response instanceof OrbitalCommandManagerTask.ScanRequestTaskMessageResponse) {
                                                if (response.isSuccess()) {
                                                    Optional<Point2d> scannedPoint = ((OrbitalCommandManagerTask.ScanRequestTaskMessageResponse) response).scannedPoint();
                                                    System.out.println("Bio army got a scan back [highground scan].");
                                                } else {
                                                    System.out.println("Bio army scan was rejected [highground scan].");
                                                }
                                            }
                                        });
                                    });
                        }
                    }

                });
            }
        }
        return parentState;
    }

    private List<TaskPromise> requestScannerSweep(AgentData data, Point2d scanPosition, long scanRequiredBefore) {
        return data.taskManager().dispatchMessage(this,
                ImmutableScanRequestTaskMessage.builder()
                        .point2d(scanPosition)
                        .requiredBefore(scanRequiredBefore)
                        .build());
    }

    @Override
    protected AggressionState retreatCommand(S2Agent agent,
                                            AgentData data,
                                            Optional<Point2d> centreOfMass,
                                            Optional<Point2d> suggestedAttackMovePosition,
                                            Optional<Point2d> suggestedRetreatMovePosition,
                                            Optional<Army> maybeEnemyArmy) {
        AggressionState parentState = super.retreatCommand(agent, data, centreOfMass,
                suggestedAttackMovePosition, suggestedRetreatMovePosition, maybeEnemyArmy);

        ObservationInterface observationInterface = agent.observation();
        ActionInterface actionInterface = agent.actions();
        // Burrow widow mines to cover the retreat.
        calculateWidowMines(centreOfMass, maybeEnemyArmy, observationInterface, actionInterface);
        return parentState;
    }

    private void calculateWidowMines(Optional<Point2d> centreOfMass, Optional<Army> maybeEnemyArmy,
                                     ObservationInterface observationInterface, ActionInterface actionInterface) {
        if (centreOfMass.isPresent() && maybeEnemyArmy.isPresent() && (getAmountOfUnit(Units.TERRAN_WIDOWMINE) > 0 || getAmountOfUnit(Units.TERRAN_WIDOWMINE_BURROWED) > 0)) {
            armyUnits.forEach(tag -> {
                UnitInPool unitInPool = observationInterface.getUnit(tag);
                if (unitInPool.unit().getType() != Units.TERRAN_WIDOWMINE && unitInPool.unit().getType() != Units.TERRAN_WIDOWMINE_BURROWED) {
                    return;
                }
                Point2d position = unitInPool.unit().getPosition().toPoint2d();
                List<UnitInPool> nearEnemies = observationInterface.getUnits(
                        UnitFilter.builder()
                                .alliance(Alliance.ENEMY)
                                .unitTypes(Constants.ARMY_UNIT_TYPES)
                                .inRangeOf(position)
                                .range(15f)
                                .build());
                boolean anyInRange = false;
                for (UnitInPool nearEnemy : nearEnemies) {
                    if (nearEnemy.unit().getPosition().toPoint2d().distance(position) < 10f) {
                        anyInRange = true;
                    }
                }
                if (anyInRange) {
                    actionInterface.unitCommand(tag, Abilities.BURROW_DOWN_WIDOWMINE, false);
                } else if (nearEnemies.size() == 0) {
                    actionInterface.unitCommand(tag, Abilities.BURROW_UP_WIDOWMINE, false);
                }
            });
        }
    }

    private void calculateStimPack(ObservationInterface observationInterface, ActionInterface actionInterface,
                           Optional<Army> maybeEnemyArmy, Set<Tag> unitsToAttackWith) {
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
        int stimsRequested = Math.max(0, (int) maybeEnemyArmy.get().size() - stimmedMarines.get() - stimmedMarauders.get());
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
        return false;
    }

    @Override
    public void debug(S2Agent agent) {
        centreOfMass.ifPresent(point2d -> {
            float z = agent.observation().terrainHeight(point2d);
            Point point = Point.of(point2d.getX(), point2d.getY(), z);
            agent.debug().debugSphereOut(point, aggressionState == AggressionState.REGROUPING ? 5f : 10f, Color.YELLOW);
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
    public void onUnitIdle(UnitInPool unitTag) {

    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
    }
}
