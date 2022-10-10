package com.supalosa.bot;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ExpansionParameters;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.google.common.cache.AbstractLoadingCache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.supalosa.bot.analysis.AnalyseMap;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.placement.StructurePlacementCalculator;
import com.supalosa.bot.task.BuildStructureTask;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.TaskManagerImpl;

import javax.annotation.CheckForNull;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SupaBot extends S2Agent {

    private final boolean isDebug;

    private List<Expansion> expansionLocations = null;

    private Optional<Point2d> knownEnemyStartLocation = Optional.empty();

    private Set<Point2d> unscoutedLocations = new HashSet<>();
    private Set<Tag> scoutingWith = new HashSet<>();
    private long scoutResetLoopTime = 0;

    private Optional<AnalysisResults> mapAnalysis = Optional.empty();
    private Optional<StructurePlacementCalculator> structurePlacementCalculator = Optional.empty();

    private TaskManager taskManager;
    private FightManager fightManager;
    private GameData gameData;

    private static final long GAS_CHECK_INTERVAL = 22L;
    private final long lastGasCheck = 0L;

    private Map<UnitType, UnitTypeData> unitTypeData = null;

    private LoadingCache<UnitType, Integer> countOfUnits = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.SECONDS)
            .build(new CacheLoader<>() {
                @Override
                public Integer load(UnitType key) {
                    int count = observation().getUnits(Alliance.SELF, UnitInPool.isUnit(key)).size();
                    return count;
                }
            });

    public SupaBot(boolean isDebug) {
        this.isDebug = isDebug;
        taskManager = new TaskManagerImpl();
        fightManager = new FightManager(this);
        this.gameData = new GameData(observation());
    }

    @Override
    public void onGameStart() {
        System.out.println("Hello world of Starcraft II bots!");

        this.unitTypeData = observation().getUnitTypeData(true);
        mapAnalysis = observation().getGameInfo().getStartRaw().map(startRaw -> AnalyseMap.analyse(
                observation(),
                startRaw));
        structurePlacementCalculator = mapAnalysis
                .map(analysisResults -> new StructurePlacementCalculator(analysisResults, observation().getStartLocation().toPoint2d()));
    }

    private void manageScouting() {

        if (knownEnemyStartLocation.isEmpty()) {
            Optional<Point2d> randomEnemyPosition = findRandomEnemyPosition();
            if (observation().getFoodUsed() > 20 && scoutingWith.size() == 0 && randomEnemyPosition.isPresent()) {
                Optional<Unit> randomScv = getRandomUnit(Units.TERRAN_SCV).flatMap(unitInPool -> unitInPool.getUnit());
                randomScv.ifPresent(scv -> {
                    scoutingWith.add(scv.getTag());
                    actions().unitCommand(scv, Abilities.MOVE, randomEnemyPosition.get(), false);
                });
            }
            scoutingWith = scoutingWith.stream().filter(tag -> observation().getUnit(tag).isAlive()).collect(Collectors.toSet());
            observation().getGameInfo().getStartRaw().ifPresent(startRaw -> {
                // Note: startRaw.getStartLocations() is actually potential `enemy` locations.
                // If there's only one enemy location, the opponent is there.
                Set<Point2d> enemyStartLocations = startRaw.getStartLocations();
                if (enemyStartLocations.size() == 1) {
                    knownEnemyStartLocation = enemyStartLocations.stream().findFirst();
                    System.out.println("Pre-determined enemy location at " + knownEnemyStartLocation.get());
                } else if (unitTypeData != null) {
                    List<Unit> enemyStructures = observation().getUnits(
                            unitInPool -> unitInPool.getUnit().filter(
                                    unit -> unit.getAlliance() == Alliance.ENEMY &&
                                            unitTypeData.get(unit.getType()).getAttributes().contains(UnitAttribute.STRUCTURE)
                            ).isPresent())
                            .stream()
                            .filter(unitInPool -> unitInPool.getUnit().isPresent())
                            .map(unitInPool -> unitInPool.getUnit().get())
                            .collect(Collectors.toList());
                    for (Unit enemyStructure : enemyStructures) {
                        Point2d position = enemyStructure.getPosition().toPoint2d();
                        for (Point2d enemyStartLocation : enemyStartLocations) {
                            if (position.distance(enemyStartLocation) < 10) {
                                System.out.println("Scouted enemy location at " + enemyStartLocation);
                                knownEnemyStartLocation = Optional.of(enemyStartLocation);
                                return;
                            }
                        }
                    }
                }
            });
        }

        //debug().debugIgnoreMineral();

        // Score expansions based on known enemy start location.
        if (expansionLocations == null && knownEnemyStartLocation.isPresent() && observation().getGameLoop() > 10) {
            ExpansionParameters parameters = ExpansionParameters.from(
                    List.of(6.4, 5.3, 5.1),
                    0.25,
                    15.0);
            expansionLocations = Expansions.processExpansions(
                    query(),
                    observation().getStartLocation().toPoint2d(),
                    knownEnemyStartLocation.get(),
                    Expansions.calculateExpansionLocations(observation(), query(), debug(), parameters));
        }
        if (observation().getGameLoop() > scoutResetLoopTime) {
            observation().getGameInfo().getStartRaw().ifPresent(startRaw -> {
                unscoutedLocations = new HashSet<>(startRaw.getStartLocations());
            });
            scoutResetLoopTime = observation().getGameLoop() + 22 * 300;
        }
        if (unscoutedLocations.size() > 0) {
            unscoutedLocations = unscoutedLocations.stream()
                    .filter(point -> observation().getVisibility(point) != Visibility.VISIBLE)
                    .collect(Collectors.toSet());
        }
    }

    @Override
    public void onStep() {
        /*if (observation().getGameLoop() == 0) {
            observation().getGameInfo().getStartRaw().ifPresent(startRaw -> AnalyseMap.analyse(
                    observation().getStartLocation(),
                    startRaw));
        }*/
        countOfUnits.invalidateAll();
        manageScouting();
        tryBuildSupplyDepot();
        tryBuildBarracks();
        tryBuildCommandCentre();
        tryBuildRefinery();
        int supply = observation().getFoodUsed();
        if (supply > 60) {
            tryBuildMax(Abilities.BUILD_FACTORY, Units.TERRAN_FACTORY, Units.TERRAN_SCV, 1, 1);
        }
        if (supply > 70) {
            tryBuildMax(Abilities.BUILD_ENGINEERING_BAY, Units.TERRAN_ENGINEERING_BAY, Units.TERRAN_SCV, 1, 2);
        }
        if (supply > 80) {
            tryBuildMax(Abilities.BUILD_ARMORY, Units.TERRAN_ARMORY, Units.TERRAN_SCV, 1, 1);
        }
        if (supply > 50) {
            //Map<Upgrade, UpgradeData> upgradeData = observation().getUpgradeData(false);
            Set<Upgrade> upgrades = new HashSet<>(observation().getUpgrades());
            observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    unitInPool.unit().getAddOnTag().isEmpty() &&
                    UnitInPool.isUnit(Units.TERRAN_BARRACKS).test(unitInPool)).forEach(unit -> {
                Ability ability = Abilities.BUILD_REACTOR;
                if (ThreadLocalRandom.current().nextBoolean()) {
                    ability = Abilities.BUILD_TECHLAB;
                }
                actions().unitCommand(unit.unit(), ability, unit.unit().getPosition().toPoint2d(), false);
            });
            if (!upgrades.contains(Upgrades.COMBAT_SHIELD) || !upgrades.contains(Upgrades.STIMPACK)) {
                observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                        UnitInPool.isUnit(Units.TERRAN_BARRACKS_TECHLAB).test(unitInPool)).forEach(unit -> {
                    if (unit.unit().getOrders().isEmpty()) {
                        if (!upgrades.contains(Upgrades.COMBAT_SHIELD)) {
                            actions().unitCommand(unit.unit(), Abilities.RESEARCH_COMBAT_SHIELD, false);
                        }
                        if (!upgrades.contains(Upgrades.STIMPACK)) {
                            actions().unitCommand(unit.unit(), Abilities.RESEARCH_STIMPACK, false);
                        }
                    }
                });
                observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                        UnitInPool.isUnit(Units.TERRAN_ENGINEERING_BAY).test(unitInPool)).forEach(unit -> {
                    if (unit.unit().getOrders().isEmpty()) {
                        if (!upgrades.contains(Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL1)) {
                            actions().unitCommand(unit.unit(), Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL1, false);
                        } else if (!upgrades.contains(Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL2)) {
                            actions().unitCommand(unit.unit(), Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL2, false);
                        } else if (!upgrades.contains(Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL3)) {
                            actions().unitCommand(unit.unit(), Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL3, false);
                        }
                        if (!upgrades.contains(Upgrades.TERRAN_INFANTRY_ARMORS_LEVEL1)) {
                            actions().unitCommand(unit.unit(), Abilities.RESEARCH_TERRAN_INFANTRY_ARMOR_LEVEL1, false);
                        } else if (!upgrades.contains(Upgrades.TERRAN_INFANTRY_ARMORS_LEVEL2)) {
                            actions().unitCommand(unit.unit(), Abilities.RESEARCH_TERRAN_INFANTRY_ARMOR_LEVEL2, false);
                        } else if (!upgrades.contains(Upgrades.TERRAN_INFANTRY_ARMORS_LEVEL3)) {
                            actions().unitCommand(unit.unit(), Abilities.RESEARCH_TERRAN_INFANTRY_ARMOR_LEVEL3, false);
                        }
                    }
                });
            }
        }
        tryBuildScvs();
        tryBuildMarines();


        rebalanceWorkers();

        mineGas();
        Map<Ability, AbilityData> abilities = observation().getAbilityData(true);

        taskManager.onStep(this);

        fightManager.setAttackPosition(findEnemyPosition());
        fightManager.onStep();

        Optional<UnitInPool> randomCc = getRandomUnit(Units.TERRAN_COMMAND_CENTER);
        if (randomCc.isPresent()) {
            fightManager.setRegroupPosition(randomCc.map(unitInPool -> unitInPool.unit().getPosition().toPoint2d()));
        } else {
            fightManager.setRegroupPosition(Optional.empty());
        }

        // update ramp
        structurePlacementCalculator.ifPresent(spc -> {
            AtomicBoolean rampClosed = new AtomicBoolean(false);
            spc.getFirstSupplyDepot(observation()).ifPresent(supplyDepot -> {
                if (observation().getUnits(Alliance.ENEMY).stream()
                        .anyMatch(enemyUnit -> enemyUnit
                                .getUnit()
                                .filter(uip -> uip.getPosition().distance(supplyDepot.unit().getPosition()) < 5)
                                .isPresent())) {
                    rampClosed.set(true);
                }
                if (!rampClosed.get() && supplyDepot.unit().getType() == Units.TERRAN_SUPPLY_DEPOT) {
                    actions().unitCommand(supplyDepot.getTag(), Abilities.MORPH_SUPPLY_DEPOT_LOWER, false);
                } else if (rampClosed.get() && supplyDepot.unit().getType() == Units.TERRAN_SUPPLY_DEPOT_LOWERED) {
                    actions().unitCommand(supplyDepot.getTag(), Abilities.MORPH_SUPPLY_DEPOT_RAISE, false);
                }
            });
            spc.getSecondSupplyDepot(observation()).ifPresent(supplyDepot -> {
                if (!rampClosed.get() && supplyDepot.unit().getType() == Units.TERRAN_SUPPLY_DEPOT) {
                    actions().unitCommand(supplyDepot.getTag(), Abilities.MORPH_SUPPLY_DEPOT_LOWER, false);
                } else if (rampClosed.get() && supplyDepot.unit().getType() == Units.TERRAN_SUPPLY_DEPOT_LOWERED) {
                    actions().unitCommand(supplyDepot.getTag(), Abilities.MORPH_SUPPLY_DEPOT_RAISE, false);
                }
            });
            spc.getFirstSupplyDepotLocation().ifPresent(
                    spl -> drawDebugSquare(spl.getX(), spl.getY(), 1.0f, 1.0f, Color.GREEN));
            spc.getSecondSupplyDepotLocation().ifPresent(
                    spl -> drawDebugSquare(spl.getX(), spl.getY(), 1.0f, 1.0f, Color.GREEN));

            fightManager.setRegroupPosition(spc.getFirstBarracksLocation(observation().getStartLocation().toPoint2d()));
        });

        if (1 < 0 && isDebug && this.expansionLocations != null) {
            this.expansionLocations.forEach(expansion -> {
                debug().debugBoxOut(
                        expansion.position().add(Point.of(-2.5f, -2.5f, 0.1f)),
                        expansion.position().add(Point.of(2.5f, 2.5f, 0.1f)),
                        Color.WHITE);
            });
            debug().sendDebug();
        }
    }

    private long lastRebalanceAt = 0L;

    private void rebalanceWorkers() {
        long gameLoop = observation().getGameLoop();
        if (gameLoop < lastRebalanceAt + 1000L) {
            return;
        }
        lastRebalanceAt = gameLoop;
        // rebalance workers
        Map<Tag, Integer> ccToWorkerCount = new HashMap<>();
        int totalWorkers = observation().getFoodWorkers();
        int ccCount = countUnitType(Units.TERRAN_COMMAND_CENTER, Units.TERRAN_ORBITAL_COMMAND);
        if (ccCount > 0) {
            int averageWorkers = totalWorkers / ccCount;
            Set<Unit> givers = new HashSet<>();
            Set<Unit> takers = new HashSet<>();
            observation().getUnits(Alliance.SELF, UnitInPool.isUnit(Units.TERRAN_COMMAND_CENTER)).forEach(ccInPool -> {
                ccInPool.getUnit().ifPresent(cc -> {
                    if (cc.getBuildProgress() < 0.9) {
                        return;
                    }
                    cc.getAssignedHarvesters().ifPresent(assigned -> {
                        ccToWorkerCount.put(cc.getTag(), assigned);
                        if (assigned > averageWorkers + 4) {
                            givers.add(cc);
                        } else if (assigned < averageWorkers - 4) {
                            takers.add(cc);
                        }
                    });
                });
            });
            if (givers.size() > 0 && takers.size() > 0) {
                Set<Tag> donatedWorkers =  new HashSet<>();
                observation().getUnits(Alliance.SELF, UnitInPool.isUnit(Units.TERRAN_SCV)).forEach(scvInPool -> {
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
                // TODO weighted taking
                Optional<Unit> taker = takers.stream().findFirst();
                System.out.println("Rebalancing " + donatedWorkers.size() + " workers to " + taker.get());
                actions().unitCommand(donatedWorkers, Abilities.MOVE, taker.get().getPosition().toPoint2d(), false);
            }
        }
    }

    private void drawDebugSquare(float x, float y, float w, float h, Color color) {
        if (!isDebug) {
            return;
        }
        Point2d point2d = Point2d.of(x, y);
        float z = observation().terrainHeight(point2d);
        debug().debugBoxOut(
            Point.of(Math.max(0.0f, x), Math.max(0.0f, y), z + 0.1f),
                    Point.of(x+w, y+h, z + 0.1f),
                color);
    }

    private Long lastExpansionTime = 0L;
    private final Map<Expansion, Long> expansionLastAttempted = new HashMap<>();

    private boolean needsCommandCentre() {
        final int[] expansionSupply = new int[]{0, 18, 36, 80, 128, 172, 196};
        int currentSupply = observation().getFoodUsed();
        int numCcs = countUnitType(Units.TERRAN_COMMAND_CENTER, Units.TERRAN_ORBITAL_COMMAND);
        int index = Math.min(expansionSupply.length - 1, Math.max(0, numCcs));
        int nextExpansionAt = expansionSupply[index];
        if (observation().getGameLoop() < lastExpansionTime + 22L) {
            return false;
        }
        if (getNumBuildingStructure(Abilities.BUILD_COMMAND_CENTER) > 0) {
            return false;
        }
        return (currentSupply >= nextExpansionAt);
    }

    private boolean tryBuildCommandCentre() {
        if (!needsCommandCentre()) {
            return false;
        }
        if (this.expansionLocations == null || this.expansionLocations.size() == 0) {
            return false;
        }
        if (observation().getMinerals() < 400) {
            return false;
        }
        // ExpansionLocations is ordered by distance to start point.
        LinkedHashSet<Expansion> validExpansionLocations = new LinkedHashSet<>();
        long gameLoop = observation().getGameLoop();
        if (gameLoop % 100 == 0) {
            System.out.println("Expansion needed");
        }
        for (Expansion expansion : this.expansionLocations) {
            if (!observation().isPlacable(expansion.position().toPoint2d())) {
                continue;
            }
            if (expansionLastAttempted.getOrDefault(expansion, 0L) < gameLoop - (15 * 22L)) {
                validExpansionLocations.add(expansion);
            }
        }
        System.out.println("Valid locations: " + validExpansionLocations.size());

        for (Expansion validExpansionLocation : validExpansionLocations) {
            if (tryBuildStructure(Abilities.BUILD_COMMAND_CENTER, Units.TERRAN_COMMAND_CENTER, Units.TERRAN_SCV, 1, Optional.of(validExpansionLocation.position().toPoint2d()))) {
                expansionLastAttempted.put(validExpansionLocation, gameLoop);
                lastExpansionTime = gameLoop;
                System.out.println("Attempting to build command centre at " + validExpansionLocation);
                return true;
            }
        }
        return false;
    }

    private boolean needsSupplyDepot() {
        // If we are not supply capped, don't build a supply depot.
        if (observation().getFoodUsed() <= observation().getFoodCap() - 2) {
            return false;
        }
        if (observation().getFoodCap() >= 200) {
            return false;
        }
        return true;
    }

    private boolean tryBuildSupplyDepot() {
        if (!needsSupplyDepot()) {
            return false;
        }
        // Try and build a depot. Find a random TERRAN_SCV and give it the order.
        Optional<Point2d> position = Optional.empty();
        if (structurePlacementCalculator.isPresent()) {
            position = structurePlacementCalculator.get()
                    .getFirstSupplyDepotLocation();
            if (structurePlacementCalculator.get().getFirstSupplyDepot(observation()).isPresent()) {
                position = structurePlacementCalculator.get()
                        .getSecondSupplyDepotLocation();
            }
            if (structurePlacementCalculator.get().getSecondSupplyDepot(observation()).isPresent()) {
                position = Optional.empty();
            }
            if (position.isPresent() &&
                    !query().placement(Abilities.BUILD_SUPPLY_DEPOT, position.get())) {
                position = Optional.empty();
            }
            position.ifPresent(spl -> drawDebugSquare(spl.getX(), spl.getY(), 0.1f, 0.1f, Color.RED));
        }
        long numCc = countUnitType(Units.TERRAN_COMMAND_CENTER, Units.TERRAN_ORBITAL_COMMAND);
        return tryBuildStructure(Abilities.BUILD_SUPPLY_DEPOT, Units.TERRAN_SUPPLY_DEPOT, Units.TERRAN_SCV, (int)Math.min(3, numCc), position);
    }

    private boolean tryBuildScvs() {
        int numBases = countUnitType(Units.TERRAN_COMMAND_CENTER);
        int numScvs = countUnitType(Units.TERRAN_SCV);
        observation().getUnits(Alliance.SELF, UnitInPool.isUnit(Units.TERRAN_COMMAND_CENTER)).forEach(commandCentre -> {
            if (commandCentre.unit().getOrders().isEmpty()) {
                if (numScvs < Math.min(80, numBases * 22)) {
                    actions().unitCommand(commandCentre.unit(), Abilities.TRAIN_SCV, false);
                }
            }
        });
        return true;
    }

    private boolean tryBuildMarines() {
        observation().getUnits(Alliance.SELF, UnitInPool.isUnit(Units.TERRAN_BARRACKS)).forEach(barracks -> {
            if (barracks.unit().getOrders().isEmpty()) {
                if (!needsCommandCentre()) {
                    actions().unitCommand(barracks.unit(), Abilities.TRAIN_MARINE, false);
                }
            }
        });
        return true;
    }

    private boolean needsRefinery() {
        return observation().getFoodWorkers() > 24 &&
                countUnitType(Units.TERRAN_REFINERY) < countUnitType(Units.TERRAN_COMMAND_CENTER, Units.TERRAN_ORBITAL_COMMAND) * 1;
    }

    private boolean tryBuildRefinery() {
        if (!needsRefinery()) {
            return false;
        }
        List<Unit> commandCentres = observation().getUnits(unitInPool ->
                unitInPool.unit().getAlliance() == Alliance.SELF &&
                (unitInPool.unit().getType().equals(Units.TERRAN_COMMAND_CENTER) ||
                        unitInPool.unit().getType().equals(Units.TERRAN_ORBITAL_COMMAND))).stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        List<Unit> refineries = observation().getUnits(unitInPool ->
                        unitInPool.unit().getAlliance().equals(Alliance.SELF) &&
                        unitInPool.unit().getType().equals(Units.TERRAN_REFINERY) &&
                                hasUnitNearby(unitInPool.unit(), commandCentres, 10f)
                ).stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        List<Unit> neutralGeysers = observation().getUnits(unitInPool ->
                unitInPool.unit().getAlliance().equals(Alliance.NEUTRAL) &&
                Constants.VESPENE_GEYSER_TYPES.contains(unitInPool.unit().getType()) &&
                hasUnitNearby(unitInPool.unit(), commandCentres, 10f)
            ).stream()
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
                        tryBuildStructureAtTarget(Abilities.BUILD_REFINERY, Units.TERRAN_REFINERY, Units.TERRAN_SCV, 1, Optional.of(geyser));
                        return true;
                    }
                }
                break;
            }
        }
        return true;
    }

    void mineGas() {
        long gameLoop = observation().getGameLoop();

        if (gameLoop < lastGasCheck + GAS_CHECK_INTERVAL) {
            return;
        }
        List<Unit> commandCentres = observation().getUnits(unitInPool ->
                        unitInPool.unit().getAlliance() == Alliance.SELF &&
                                (unitInPool.unit().getType().equals(Units.TERRAN_COMMAND_CENTER) ||
                                        unitInPool.unit().getType().equals(Units.TERRAN_ORBITAL_COMMAND))).stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        List<Unit> refineries = observation().getUnits(unitInPool ->
                        unitInPool.unit().getAlliance().equals(Alliance.SELF) &&
                                unitInPool.unit().getType().equals(Units.TERRAN_REFINERY) &&
                                hasUnitNearby(unitInPool.unit(), commandCentres, 10f)
                ).stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        refineries.forEach(refinery -> {
            if (refinery.getAssignedHarvesters().isPresent() &&
                    refinery.getIdealHarvesters().isPresent() &&
                    refinery.getAssignedHarvesters().get() < refinery.getIdealHarvesters().get()) {
                int delta = refinery.getIdealHarvesters().get() - refinery.getAssignedHarvesters().get();
                List<Unit> nearbyScvs = observation().getUnits(unitInPool ->
                        unitInPool.unit().getAlliance().equals(Alliance.SELF) &&
                        UnitInPool.isCarryingMinerals().test(unitInPool) &&
                        unitInPool.unit().getPosition().distance(refinery.getPosition()) < 8.0f)
                        .stream().map(UnitInPool::unit).collect(Collectors.toList());
                for (int i = 0; i < Math.min(nearbyScvs.size(), delta); ++i) {
                    actions().unitCommand(nearbyScvs.get(i), Abilities.SMART, refinery, false);
                }
            }
        });
    }

    /**
     * Checks if a certain unit has other units within a certain radius.
     * @param unit The unit to check
     * @param units The list of other units to check are near the {@code unit}.
     * @param radius The radius to check
     * @return
     */
    private boolean hasUnitNearby(Unit unit, List<Unit> units, float radius) {
        return units.stream().anyMatch(otherUnit -> unit.getPosition().distance(otherUnit.getPosition()) < radius);
    }

    private Set<Point2d> unitsToPointSet(List<Unit> units) {
        return units.stream().map(unit -> unit.getPosition().toPoint2d()).collect(Collectors.toSet());
    }

    private int getNumBuildingStructure(Ability abilityTypeForStructure) {
        // If a unit already is building a supply structure of this type, do nothing.
        return observation().getUnits(Alliance.SELF, doesBuildWith(abilityTypeForStructure)).size();
    }

    private boolean tryBuildMax(Ability abilityTypeForStructure, UnitType unitTypeForStructure, UnitType unitType, int maxParallel, int max) {
        if (countUnitType(unitTypeForStructure) < max) {
            return tryBuildStructure(abilityTypeForStructure, unitTypeForStructure, unitType, maxParallel, Optional.empty());
        }
        return false;
    }

    private boolean tryBuildStructure(Ability abilityTypeForStructure, UnitType unitTypeForStructure, UnitType unitType, int maxParallel, Optional<Point2d> specificPosition) {
        return _tryBuildStructure(abilityTypeForStructure, unitTypeForStructure, unitType, maxParallel, specificPosition, Optional.empty());
    }

    private boolean tryBuildStructureAtTarget(Ability abilityTypeForStructure, UnitType unitTypeForStructure, UnitType unitType, int maxParallel, Optional<Unit> specificTarget) {
        return _tryBuildStructure(abilityTypeForStructure, unitTypeForStructure, unitType, maxParallel, Optional.empty(), specificTarget);
    }

    private boolean _tryBuildStructure(Ability abilityTypeForStructure, UnitType unitTypeForStructure, UnitType unitType, int maxParallel, Optional<Point2d> specificPosition, Optional<Unit> specificTarget) {
        BuildStructureTask maybeTask = new BuildStructureTask(
                abilityTypeForStructure,
                unitTypeForStructure,
                specificPosition,
                specificTarget,
                gameData.getUnitMineralCost(unitTypeForStructure),
                gameData.getUnitVespeneCost(unitTypeForStructure),
                Optional.empty());
        int similarCount = taskManager.countSimilarTasks(maybeTask);
        //System.out.println(unitTypeForStructure + ": " + similarCount);
        if (similarCount >= maxParallel) {
            return false;
        }

        boolean result = taskManager.addTask(maybeTask);
        return result;
    }

    private Predicate<UnitInPool> doesBuildWith(Ability abilityTypeForStructure) {
        return unitInPool -> unitInPool.unit()
                .getOrders()
                .stream()
                .anyMatch(unitOrder -> abilityTypeForStructure.equals(unitOrder.getAbility()));
    }

    private Predicate<UnitInPool> isHarvesting() {
        return unitInPool -> unitInPool.unit()
                .getOrders()
                .stream()
                .map(unitOrder -> unitOrder.getAbility())
                .anyMatch(unitOrder -> unitOrder.equals(Abilities.HARVEST_GATHER_SCV) ||
                        unitOrder.equals(Abilities.HARVEST_RETURN_SCV) ||
                        unitOrder.equals(Abilities.HARVEST_GATHER) ||
                        unitOrder.equals(Abilities.HARVEST_RETURN));
    }

    private Optional<UnitInPool> getRandomUnit(UnitType unitType) {
        List<UnitInPool> units = observation().getUnits(
                Alliance.SELF,
                UnitInPool.isUnit(unitType))
                .stream()
                .filter(isHarvesting())
                .collect(Collectors.toList());
        return units.isEmpty()
                ? Optional.empty()
                : Optional.of(units.get(ThreadLocalRandom.current().nextInt(units.size())));
    }

    @Override
    public void onUnitCreated(UnitInPool unitInPool) {
        switch ((Units) unitInPool.unit().getType()) {
            case TERRAN_MARINE:
                fightManager.addUnit(unitInPool.unit());
                break;
        }
    }

    @Override
    public void onUnitIdle(UnitInPool unitInPool) {
        Unit unit = unitInPool.unit();
        switch ((Units) unit.getType()) {
            case TERRAN_SCV:
                findNearestMineralPatch(unit.getPosition().toPoint2d()).ifPresent(mineralPath ->
                        actions().unitCommand(unit, Abilities.SMART, mineralPath, false));
                break;
            case TERRAN_MARINE:
                fightManager.onUnitIdle(unitInPool);
                break;
            default:
                break;
        }
    }


    private Optional<Unit> findNearestMineralPatch(Point2d start) {
        List<UnitInPool> units = observation().getUnits(Alliance.NEUTRAL);
        double distance = Double.MAX_VALUE;
        Unit target = null;
        for (UnitInPool unitInPool : units) {
            Unit unit = unitInPool.unit();
            if (Constants.MINERAL_TYPES.contains(unit.getType())) {
                double d = unit.getPosition().toPoint2d().distance(start);
                if (d < distance) {
                    distance = d;
                    target = unit;
                }
            }
        }
        return Optional.ofNullable(target);
    }

    private boolean tryBuildBarracks() {
        if (countUnitType(Units.TERRAN_SUPPLY_DEPOT, Units.TERRAN_SUPPLY_DEPOT_LOWERED) < 1) {
            return false;
        }
        if (needsSupplyDepot() && observation().getMinerals() < 100) {
            return false;
        }
        if (needsCommandCentre()) {
            return false;
        }

        int numBarracks = countUnitType(Units.TERRAN_BARRACKS);
        int numCc = countUnitType(Units.TERRAN_COMMAND_CENTER, Units.TERRAN_ORBITAL_COMMAND);
        if (numBarracks > numCc * 3) {
            return false;
        }
        Optional<Point2d> position = Optional.empty();

        if (numBarracks == 0 && structurePlacementCalculator.isPresent()) {
            position = structurePlacementCalculator.get()
                    .getFirstBarracksLocation(observation().getStartLocation().toPoint2d());
        }

        int maxParallel = Math.max(1, observation().getMinerals() / 250);

        return tryBuildStructure(Abilities.BUILD_BARRACKS, Units.TERRAN_BARRACKS, Units.TERRAN_SCV, maxParallel, position);
    }

    private int countUnitType(UnitType... unitType) {
        if (unitType.length == 1) {
            try {
                return countOfUnits.get(unitType[0]);
            } catch (ExecutionException e) {
                e.printStackTrace();
                return 0;
            }
        } else {
            int count = 0;
            for (UnitType type : unitType) {
                Integer countOfType = null;
                try {
                    countOfType = countOfUnits.get(type);
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
                if (countOfType != null) {
                    count += countOfType.intValue();
                }
            }
            return count;
        }
        /*if (unitType.length == 1) {
            return observation().getUnits(Alliance.SELF, UnitInPool.isUnit(unitType[0])).size();
        } else {
            Set<UnitType> unitTypes = Set.of(unitType);
            return observation().getUnits(Alliance.SELF,
                    unitInPool -> unitTypes.contains(unitInPool.unit().getType()) &&
                            unitInPool.unit().getBuildProgress() > 0.99f
            ).size();
        }*/
    }

    // Finds a worthwhile enemy position to move units towards.
    private Optional<Point2d> findEnemyPosition() {
        List<UnitInPool> enemyUnits = observation().getUnits(Alliance.ENEMY);
        if (enemyUnits.size() > 0) {
            // Move towards the closest to our base (for now)
            Point startLocation = observation().getStartLocation();
            return enemyUnits.stream()
                    .min(Comparator.comparing(unit -> unit.unit().getPosition().distance(startLocation)))
                    .map(minUnit -> {
                        //double dist = minUnit.unit().getPosition().distance(startLocation);
                        //System.out.println("Min unit " + minUnit.unit().getTag() + " (" + dist + ")");
                        return minUnit;
                    }).map(minUnit -> minUnit.unit().getPosition().toPoint2d())
                    .or(() -> findRandomEnemyPosition());
        } else {
            return findRandomEnemyPosition();
        }
    }

    // Tries to find a random location that can be pathed to on the map.
    // Returns Point2d if a new, random location has been found that is pathable by the unit.
    private Optional<Point2d> findRandomEnemyPosition() {
        if (unscoutedLocations.size() > 0) {
            return Optional.of(new ArrayList<>(unscoutedLocations)
                    .get(ThreadLocalRandom.current().nextInt(unscoutedLocations.size())));
        } else {
            return Optional.empty();
        }
    }
}
