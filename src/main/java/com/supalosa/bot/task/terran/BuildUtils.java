package com.supalosa.bot.task.terran;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.Constants;
import com.supalosa.bot.utils.UnitFilter;
import com.supalosa.bot.utils.Utils;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
}
