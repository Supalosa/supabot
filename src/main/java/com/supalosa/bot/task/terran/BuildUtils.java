package com.supalosa.bot.task.terran;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.GameData;
import com.supalosa.bot.utils.UnitFilter;
import com.supalosa.bot.utils.Utils;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class BuildUtils {

    public static Optional<Unit> getBuildableGeyser(
            ObservationInterface observationInterface) {

        List<Unit> commandCentres = observationInterface.getUnits(
                        UnitFilter.builder()
                                .alliance(Alliance.SELF)
                                .unitTypes(Constants.TERRAN_CC_TYPES).build())
                .stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        final Predicate<Unit> geyserFilter = unit ->
            commandCentres.stream().anyMatch(otherUnit -> unit.getPosition().distance(otherUnit.getPosition()) < 10f);
        List<Unit> refineries = observationInterface.getUnits(
                        UnitFilter.builder()
                                .alliance(Alliance.SELF)
                                .unitType(Units.TERRAN_REFINERY)
                                .filter(geyserFilter)
                                .build())
                .stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        List<Unit> neutralGeysers = observationInterface.getUnits(
                        UnitFilter.builder()
                                .alliance(Alliance.NEUTRAL)
                                .unitTypes(Constants.VESPENE_GEYSER_TYPES)
                                .filter(geyserFilter)
                                .build())
                .stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        for (Unit commandCentre : commandCentres) {
            List<Unit> refineriesNear = refineries.stream()
                    .filter(refinery -> refinery.getPosition().distance(commandCentre.getPosition()) < 10.0f)
                    .collect(Collectors.toList());
            Set<Point2d> refineriesNearPositions = unitsToPointSet(refineriesNear);
            List<Unit> geysersNear = neutralGeysers.stream()
                    .filter(neutralGeyser -> neutralGeyser.getPosition().distance(commandCentre.getPosition()) < 10.0f)
                    .collect(Collectors.toList());
            if (refineriesNear.size() < geysersNear.size()) {
                for (Unit geyser : geysersNear) {
                    Point2d geyserPosition = geyser.getPosition().toPoint2d();
                    if (!refineriesNearPositions.contains(geyserPosition)) {
                        return Optional.of(geyser);
                    }
                }
                break;
            }
        }
        return Optional.empty();
    }

    private static Set<Point2d> unitsToPointSet(List<Unit> units) {
        return units.stream().map(unit -> unit.getPosition().toPoint2d()).collect(Collectors.toSet());
    }

    public static void reassignGasWorkers(S2Agent agent, int minMineralWorkersPerCc, int maxGasWorkers) {
        List<Unit> commandCentres = agent.observation().getUnits(unitInPool ->
                        unitInPool.unit().getAlliance() == Alliance.SELF &&
                                (Constants.ALL_TOWN_HALL_TYPES.contains(unitInPool.unit().getType()))).stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        final Predicate<Unit> geyserFilter = unit ->
                commandCentres.stream().anyMatch(otherUnit -> unit.getPosition().distance(otherUnit.getPosition()) < 10f);
        List<Unit> refineries = agent.observation().getUnits(UnitFilter.builder()
                        .alliance(Alliance.SELF)
                        .unitType(Units.TERRAN_REFINERY)
                        .filter(geyserFilter)
                        .build()).stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        Set<Unit> ccsDoneThisRun = new HashSet<>();
        int assignedGasWorkers = refineries.stream().mapToInt(unit -> unit.getAssignedHarvesters().orElse(0)).sum();
        int totalGasWorkersAllowed = refineries.stream().mapToInt(unit -> unit.getIdealHarvesters().orElse(0)).sum();
        AtomicInteger requiredGasWorkers = new AtomicInteger(Math.min(totalGasWorkersAllowed, maxGasWorkers) - assignedGasWorkers);
        refineries.forEach(refinery -> {
            if (refinery.getAssignedHarvesters().isPresent() &&
                    refinery.getIdealHarvesters().isPresent()) {
                Optional<Unit> nearCc = commandCentres.stream().filter(cc -> cc.getPosition().distance(refinery.getPosition()) < 10f).findAny();
                int delta = refinery.getIdealHarvesters().get() - refinery.getAssignedHarvesters().get();
                if (nearCc.isPresent()) {
                    int desiredHarvesters = Math.min(minMineralWorkersPerCc, nearCc.get().getIdealHarvesters().orElse(0));
                    int currentHarvesters = nearCc.get().getAssignedHarvesters().orElse(0);
                    if (currentHarvesters <= desiredHarvesters) {
                        // remove harvesters
                        delta = -(desiredHarvesters - currentHarvesters);
                    }
                    if (ccsDoneThisRun.contains(nearCc.get())) {
                        return;
                    }
                }
                if (delta > 0) {
                    List<Unit> nearbyScvs = agent.observation().getUnits(unitInPool ->
                                    unitInPool.unit().getAlliance().equals(Alliance.SELF) &&
                                            unitInPool.unit().getType() == Units.TERRAN_SCV &&
                                            UnitInPool.isCarryingMinerals().test(unitInPool) &&
                                            unitInPool.unit().getPosition().distance(refinery.getPosition()) < 8.0f)
                            .stream().map(UnitInPool::unit).collect(Collectors.toList());
                    for (int i = 0; i < Math.min(nearbyScvs.size(), delta); ++i) {
                        if (requiredGasWorkers.get() <= 0) {
                            return;
                        }
                        agent.actions().unitCommand(nearbyScvs.get(i), Abilities.SMART, refinery, false);
                        requiredGasWorkers.decrementAndGet();
                    }
                    ccsDoneThisRun.add(nearCc.get());
                } else if (nearCc.isPresent() && delta < 0) {
                    List<Unit> nearbyScvs = agent.observation().getUnits(unitInPool ->
                                    unitInPool.unit().getOrders().stream().anyMatch(order -> order.getTargetedUnitTag().equals(Optional.of(refinery.getTag()))) &&
                                            unitInPool.unit().getAlliance().equals(Alliance.SELF) &&
                                            unitInPool.unit().getType() == Units.TERRAN_SCV &&
                                            unitInPool.unit().getPosition().distance(refinery.getPosition()) < 8.0f)
                            .stream().map(UnitInPool::unit).collect(Collectors.toList());

                    Optional<Unit> nearMinerals = Utils.findNearestMineralPatch(agent.observation(), nearCc.get().getPosition().toPoint2d());
                    for (int i = 0; i < Math.min(nearbyScvs.size(), Math.abs(delta)); ++i) {
                        agent.actions().unitCommand(nearbyScvs.get(i), Abilities.SMART, nearMinerals.get(), false);
                    }
                    ccsDoneThisRun.add(nearCc.get());
                }
            }
        });
    }

    public static void defaultTerranRamp(AgentWithData agentWithData) {
        // Open or close the ramp.
        agentWithData.structurePlacementCalculator().ifPresent(spc -> {
            AtomicBoolean rampClosed = new AtomicBoolean(false);
            spc.getFirstSupplyDepot(agentWithData.observation()).ifPresent(supplyDepot -> {
                if (agentWithData.observation().getUnits(Alliance.ENEMY).stream()
                        .anyMatch(enemyUnit -> enemyUnit
                                .getUnit()
                                .filter(uip -> uip.getPosition().distance(supplyDepot.unit().getPosition()) < 8)
                                .isPresent())) {
                    rampClosed.set(true);
                }
                if (!rampClosed.get() && supplyDepot.unit().getType() == Units.TERRAN_SUPPLY_DEPOT) {
                    agentWithData.actions().unitCommand(supplyDepot.getTag(), Abilities.MORPH_SUPPLY_DEPOT_LOWER, false);
                } else if (rampClosed.get() && supplyDepot.unit().getType() == Units.TERRAN_SUPPLY_DEPOT_LOWERED) {
                    agentWithData.actions().unitCommand(supplyDepot.getTag(), Abilities.MORPH_SUPPLY_DEPOT_RAISE, false);
                }
            });
            spc.getSecondSupplyDepot(agentWithData.observation()).ifPresent(supplyDepot -> {
                if (!rampClosed.get() && supplyDepot.unit().getType() == Units.TERRAN_SUPPLY_DEPOT) {
                    agentWithData.actions().unitCommand(supplyDepot.getTag(), Abilities.MORPH_SUPPLY_DEPOT_LOWER, false);
                } else if (rampClosed.get() && supplyDepot.unit().getType() == Units.TERRAN_SUPPLY_DEPOT_LOWERED) {
                    agentWithData.actions().unitCommand(supplyDepot.getTag(), Abilities.MORPH_SUPPLY_DEPOT_RAISE, false);
                }
            });
            for (UnitInPool supplyDepot : agentWithData.observation().getUnits(UnitFilter.mine(Units.TERRAN_SUPPLY_DEPOT))) {
                agentWithData.actions().unitCommand(supplyDepot.unit(), Abilities.MORPH_SUPPLY_DEPOT_LOWER, false);
            }
        });
    }

    public static void rebalanceWorkers(S2Agent agent) {
        // rebalance workers
        Map<Tag, Integer> ccToWorkerCount = new HashMap<>();
        int totalWorkers = agent.observation().getFoodWorkers();
        int ccCount = agent.observation().getUnits(UnitFilter.mine(Constants.ALL_TOWN_HALL_TYPES)).size();
        if (ccCount > 0) {
            int averageWorkers = totalWorkers / ccCount;
            Set<Unit> givers = new HashSet<>();
            Map<Unit, Integer> takers = new HashMap<>();
            agent.observation().getUnits(Alliance.SELF,
                    unitInPool -> Constants.TERRAN_CC_TYPES.contains(unitInPool.unit().getType())).forEach(ccInPool -> {
                ccInPool.getUnit().ifPresent(cc -> {
                    if (cc.getBuildProgress() < 0.9) {
                        return;
                    }
                    cc.getAssignedHarvesters().ifPresent(assigned -> {
                        ccToWorkerCount.put(cc.getTag(), assigned);
                        if (assigned > averageWorkers + 4 || (cc.getIdealHarvesters().isPresent() && assigned > cc.getIdealHarvesters().get() + 4)) {
                            givers.add(cc);
                        } else if (cc.getIdealHarvesters().isPresent() && assigned < cc.getIdealHarvesters().get()) {
                            takers.put(cc, cc.getIdealHarvesters().get() - assigned);
                        }
                    });
                });
            });
            if (givers.size() > 0 && takers.size() > 0) {
                Queue<Tag> donatedWorkers = new LinkedList<>();
                agent.observation().getUnits(Alliance.SELF, UnitInPool.isUnit(Units.TERRAN_SCV)).forEach(scvInPool -> {
                    scvInPool.getUnit().ifPresent(scv -> {
                        givers.forEach(giver -> {
                            if (scv.getPosition().distance(giver.getPosition()) < 10) {
                                if (donatedWorkers.size() < averageWorkers) {
                                    donatedWorkers.add(scv.getTag());
                                }
                            }
                        });
                    });
                });
                takers.entrySet().forEach(taker -> {
                    Unit takerCc = taker.getKey();
                    int takerAmount = taker.getValue();
                    Optional<Unit> nearestMineralPatch = Utils.findNearestMineralPatch(agent.observation(), takerCc.getPosition().toPoint2d());
                    if (donatedWorkers.size() > 0) {
                        while (!donatedWorkers.isEmpty() && takerAmount > 0) {
                            --takerAmount;
                            Tag takenWorker = donatedWorkers.poll();
                            // Move to the patch, or the CC itself if patch is missing.
                            nearestMineralPatch.ifPresentOrElse(patch ->
                                            agent.actions().unitCommand(takenWorker, Abilities.SMART, patch, false),
                                    () -> agent.actions().unitCommand(takenWorker, Abilities.SMART, takerCc, false));
                        }
                    }
                });
            }
        }
    }
}
