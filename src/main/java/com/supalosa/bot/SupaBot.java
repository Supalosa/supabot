package com.supalosa.bot;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.ChatReceived;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.github.ocraft.s2client.protocol.unit.UnitOrder;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.supalosa.bot.analysis.AnalyseMap;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.awareness.MapAwarenessImpl;
import com.supalosa.bot.placement.StructurePlacementCalculator;
import com.supalosa.bot.task.BuildStructureTask;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.TaskManagerImpl;
import com.supalosa.bot.utils.UnitComparator;
import com.supalosa.bot.utils.UnitFilter;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SupaBot extends S2Agent implements AgentData {

    private static final long GAS_CHECK_INTERVAL = 22L;
    private final TaskManager taskManager;
    private final FightManager fightManager;
    private final GameData gameData;
    private final MapAwareness mapAwareness;
    private final long lastGasCheck = 0L;
    private final LoadingCache<UnitType, Integer> countOfUnits = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.SECONDS)
            .build(new CacheLoader<>() {
                @Override
                public Integer load(UnitType key) {
                    int count = observation().getUnits(Alliance.SELF, UnitInPool.isUnit(key)).size();
                    return count;
                }
            });
    private boolean isDebug;
    private Optional<AnalysisResults> mapAnalysis = Optional.empty();
    private Optional<StructurePlacementCalculator> structurePlacementCalculator = Optional.empty();
    private Map<UnitType, UnitTypeData> unitTypeData = null;
    // HACK until threat built
    private boolean crisisMode = false;
    private long lastRebalanceAt = 0L;
    private Long lastExpansionTime = 0L;

    public SupaBot(boolean isDebug) {
        this.isDebug = isDebug;
        this.taskManager = new TaskManagerImpl();
        this.fightManager = new FightManager(this);
        this.mapAwareness = new MapAwarenessImpl();
        this.gameData = new GameData(observation());
    }

    @Override
    public void onGameStart() {
        this.unitTypeData = observation().getUnitTypeData(true);
        mapAnalysis = observation().getGameInfo().getStartRaw().map(startRaw -> AnalyseMap.analyse(
                observation(),
                startRaw));
        structurePlacementCalculator = mapAnalysis
                .map(analysisResults -> new StructurePlacementCalculator(analysisResults, gameData,
                        observation().getStartLocation().toPoint2d()));
        this.mapAwareness.setStartPosition(observation().getStartLocation().toPoint2d());
    }

    @Override
    public void onStep() {
        // TESTING
        /*SpatialIndex<Unit> unitMap = new FlexibleQuadTree<>();
        observation().getUnits().forEach(unitInPool -> {
            Point2d point2d = unitInPool.unit().getPosition().toPoint2d();
            unitMap.insert(unitInPool.unit(), point2d.getX(), point2d.getY());
        });*/

        countOfUnits.invalidateAll();
        mapAwareness.onStep(this, this);
        if (structurePlacementCalculator.isPresent()) {
            structurePlacementCalculator.get().onStep(this, this);
        }
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
        if (supply > 100) {
            tryBuildMax(Abilities.BUILD_ARMORY, Units.TERRAN_ARMORY, Units.TERRAN_SCV, 1, 1);
        }
        if (supply > 80) {
            tryBuildMax(Abilities.BUILD_STARPORT, Units.TERRAN_STARPORT, Units.TERRAN_SCV, 1, supply > 160 ? 2 : 1);
            observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    unitInPool.unit().getAddOnTag().isEmpty() &&
                    UnitInPool.isUnit(Units.TERRAN_STARPORT).test(unitInPool)).forEach(unit -> {
                actions().unitCommand(unit.unit(), Abilities.BUILD_REACTOR, unit.unit().getPosition().toPoint2d(),
                        false);
            });
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
                            actions().unitCommand(unit.unit(), Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL1,
                                    false);
                        } else if (!upgrades.contains(Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL2)) {
                            actions().unitCommand(unit.unit(), Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL2,
                                    false);
                        } else if (!upgrades.contains(Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL3)) {
                            actions().unitCommand(unit.unit(), Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL3,
                                    false);
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

        // upgrade orbital/planetaries
        int numCcs = countUnitType(Constants.TERRAN_CC_TYPES_ARRAY);
        observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                UnitInPool.isUnit(Units.TERRAN_COMMAND_CENTER).test(unitInPool)).forEach(unit -> {
            Ability ability = Abilities.MORPH_ORBITAL_COMMAND;
            if (numCcs > 3) {
                ability = Abilities.MORPH_PLANETARY_FORTRESS;
            }
            actions().unitCommand(unit.unit(), ability, false);
        });
        // land mules
        float reserveCcEnergy = (fightManager.hasSeenCloakedOrBurrowedUnits() ? 85f : 50f);
        Set<Point2d> scanClusters = new HashSet<>(fightManager.getCloakedOrBurrowedUnitClusters());
        observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                UnitInPool.isUnit(Units.TERRAN_ORBITAL_COMMAND).test(unitInPool)).forEach(unit -> {
            if (unit.unit().getEnergy().isPresent() && unit.unit().getEnergy().get() > reserveCcEnergy) {
                Optional<Unit> nearestMineral = findNearestMineralPatch(unit.unit().getPosition().toPoint2d());
                nearestMineral.ifPresent(mineral -> {
                    actions().unitCommand(unit.unit(), Abilities.EFFECT_CALL_DOWN_MULE, mineral, false);
                });
            }
            if (scanClusters.size() > 0) {
                scanClusters.stream().findFirst().ifPresent(scanPoint -> {
                    actions().unitCommand(unit.unit(), Abilities.EFFECT_SCAN, scanPoint, false);
                    scanClusters.remove(scanPoint);
                });
            }
        });
        tryBuildScvs();
        int marineCount = countUnitType(Units.TERRAN_MARINE);
        tryBuildUnit(Abilities.TRAIN_MEDIVAC, Units.TERRAN_MEDIVAC, Units.TERRAN_STARPORT, Optional.of(Math.min(10,
                marineCount / 6)));
        if (marineCount > 10) {
            tryBuildUnit(Abilities.TRAIN_MARAUDER, Units.TERRAN_MARAUDER, Units.TERRAN_BARRACKS,
                    Optional.of(Math.min(25, marineCount / 2)));
        }
        if (observation().getMinerals() > 100) {
            tryBuildUnit(Abilities.TRAIN_MARINE, Units.TERRAN_MARINE, Units.TERRAN_BARRACKS, Optional.empty());
        }
        //observation().getRawObservation().getRaw().get().getMapState().getCreep();
        rebalanceWorkers();

        mineGas();
        taskManager.onStep(this, this);

        fightManager.setAttackPosition(mapAwareness.getMaybeEnemyPositionNearEnemy());
        fightManager.onStep(taskManager, this);

        Optional<UnitInPool> randomCc = getRandomUnit(Units.TERRAN_COMMAND_CENTER);
        if (randomCc.isPresent()) {
            fightManager.setDefencePosition(randomCc.map(unitInPool -> unitInPool.unit().getPosition().toPoint2d()));
        } else {
            fightManager.setDefencePosition(Optional.empty());
        }

        // Open or close the ramp.
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
            if (isDebug) {
                spc.getFirstSupplyDepotLocation().ifPresent(
                        spl -> drawDebugSquare(spl.getX() - 1.0f, spl.getY() - 1.0f, 2.0f, 2.0f, Color.GREEN));
                spc.getSecondSupplyDepotLocation().ifPresent(
                        spl -> drawDebugSquare(spl.getX() - 1.0f, spl.getY() - 1.0f, 2.0f, 2.0f, Color.GREEN));
            }
            // Defend from behind the barracks, or else the position of the barracks.
            Optional<Point2d> defencePosition = spc
                    .getMainRamp()
                    .map(ramp -> ramp.projection(5.0f))
                    .orElse(spc.getFirstBarracksLocation(observation().getStartLocation().toPoint2d()));
            fightManager.setDefencePosition(defencePosition);
        });

        Optional<Point2d> nearestEnemy = mapAwareness.getMaybeEnemyPositionNearBase();
        if (nearestEnemy.isPresent() && mapAwareness.shouldDefendLocation(nearestEnemy.get())) {
            fightManager.setDefencePosition(nearestEnemy);
        }

        // HACK - crisis mode (which affects whether marines are built or not)
        if (observation().getFoodUsed() < 24) {
            Point start = observation().getStartLocation();
            long unitsNearBase = observation().getUnits(Alliance.ENEMY).stream().filter(unitInPool ->
                    unitInPool.unit().getPosition().distance(start) < 30
            ).count();
            boolean wasCrisis = crisisMode;
            crisisMode = unitsNearBase > 10;
            if (!wasCrisis && crisisMode) {
                actions().sendChat("Crisis mode", ActionChat.Channel.TEAM);
            } else if (wasCrisis && !crisisMode) {
                actions().sendChat("No longer in crisis mode", ActionChat.Channel.TEAM);
            }
        }

        List<ChatReceived> chat = observation().getChatMessages();
        for (ChatReceived chatReceived : chat) {
            if (chatReceived.getMessage().contains("debug")) {
                this.isDebug = !isDebug;
                // send one more debug command to flush the buffer.
                debug().sendDebug();
                actions().sendChat("Debug: " + isDebug, ActionChat.Channel.TEAM);
            }
        }

        if (isDebug) {
            if (this.taskManager != null) {
                this.taskManager.debug(this);
            }
            this.structurePlacementCalculator.ifPresent(spc -> spc.debug(this));
            int miningBases = countMiningBases();
            debug().debugTextOut("Bases: " + miningBases, Point2d.of(0.95f, 0.2f), Color.WHITE, 8);
            debug().sendDebug();
        }
    }

    private void sendTeamChat(String message) {
        actions().sendChat(message, ActionChat.Channel.TEAM);
    }

    private void rebalanceWorkers() {
        long gameLoop = observation().getGameLoop();
        if (gameLoop < lastRebalanceAt + 1000L) {
            return;
        }
        lastRebalanceAt = gameLoop;
        // rebalance workers
        Map<Tag, Integer> ccToWorkerCount = new HashMap<>();
        int totalWorkers = observation().getFoodWorkers();
        int ccCount = countUnitType(Constants.TERRAN_CC_TYPES_ARRAY);
        if (ccCount > 0) {
            int averageWorkers = totalWorkers / ccCount;
            Set<Unit> givers = new HashSet<>();
            Map<Unit, Integer> takers = new HashMap<>();
            observation().getUnits(Alliance.SELF,
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
                takers.entrySet().forEach(taker -> {
                    Unit takerCc = taker.getKey();
                    int takerAmount = taker.getValue();
                    Optional<Unit> nearestMineralPatch = findNearestMineralPatch(takerCc.getPosition().toPoint2d());
                    if (donatedWorkers.size() > 0) {
                        while (!donatedWorkers.isEmpty() && takerAmount > 0) {
                            --takerAmount;
                            Tag takenWorker = donatedWorkers.poll();
                            // Move to the patch, or the CC itself if patch is missing.
                            nearestMineralPatch.ifPresentOrElse(patch ->
                                actions().unitCommand(takenWorker, Abilities.SMART, patch, false),
                                () -> actions().unitCommand(takenWorker, Abilities.SMART, takerCc, false));
                        }
                    }
                });
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
                Point.of(x + w, y + h, z + 0.1f),
                color);
    }

    private boolean needsCommandCentre() {
        // Expand every 18 workers.
        final int[] expansionNumWorkers = new int[]{0, 18, 36, 54, 72, 90};
        int currentSupply = observation().getFoodWorkers();
        int numCcs = countMiningBases();
        int index = Math.min(expansionNumWorkers.length - 1, Math.max(0, numCcs));
        int nextExpansionAt = expansionNumWorkers[index];
        if (observation().getGameLoop() < lastExpansionTime + 22L) {
            return false;
        }
        if (getNumBuildingStructure(Abilities.BUILD_COMMAND_CENTER) > 0) {
            return false;
        }
        // TEMP hack until threat system is built
        if (crisisMode) {
            return false;
        }
        if (this.mapAwareness.getValidExpansionLocations().isEmpty()) {
            return false;
        }
        return currentSupply >= nextExpansionAt;
    }

    private int countMiningBases() {
        return observation().getUnits(Alliance.SELF,
                unitInPool -> Constants.TERRAN_CC_TYPES.contains(unitInPool.unit().getType()) &&
                        unitInPool.unit().getBuildProgress() > 0.99f &&
                        unitInPool.unit().getIdealHarvesters().isPresent() &&
                        unitInPool.unit().getIdealHarvesters().get() > 0
        ).size();
    }

    private boolean tryBuildCommandCentre() {
        if (!needsCommandCentre()) {
            return false;
        }
        if (this.mapAwareness.getValidExpansionLocations().isEmpty()) {
            actions().sendChat("Valid Expansions missing or empty", ActionChat.Channel.TEAM);
            return false;
        }
        if (observation().getMinerals() < 400) {
            return false;
        }
        long gameLoop = observation().getGameLoop();
        for (Expansion validExpansionLocation : this.mapAwareness.getValidExpansionLocations().get()) {
            if (tryBuildStructure(
                    Abilities.BUILD_COMMAND_CENTER,
                    Units.TERRAN_COMMAND_CENTER,
                    Units.TERRAN_SCV,
                    1,
                    Optional.of(validExpansionLocation.position().toPoint2d()))) {
                this.mapAwareness.onExpansionAttempted(validExpansionLocation, gameLoop);
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
        return observation().getFoodCap() < 200;
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
        }
        long numCc = countUnitType(Constants.TERRAN_CC_TYPES_ARRAY);
        return tryBuildStructure(Abilities.BUILD_SUPPLY_DEPOT, Units.TERRAN_SUPPLY_DEPOT, Units.TERRAN_SCV,
                (int) Math.min(3, numCc), position);
    }

    private boolean tryBuildScvs() {
        int numBases = countUnitType(Constants.TERRAN_CC_TYPES_ARRAY);
        int numScvs = countUnitType(Units.TERRAN_SCV);
        observation().getUnits(Alliance.SELF,
                unitInPool -> Constants.TERRAN_CC_TYPES.contains(unitInPool.unit().getType())).forEach(commandCentre -> {
            if (commandCentre.unit().getOrders().isEmpty()) {
                if (numScvs < Math.min(80, numBases * 22)) {
                    actions().unitCommand(commandCentre.unit(), Abilities.TRAIN_SCV, false);
                }
            }
        });
        return true;
    }

    private boolean tryBuildUnit(Ability abilityToCast, UnitType unitType, UnitType buildFrom,
                                 Optional<Integer> maximum) {
        if (maximum.isPresent()) {
            int count = countUnitType(unitType);
            if (count >= maximum.get()) {
                return false;
            }
        }
        observation().getUnits(Alliance.SELF, UnitInPool.isUnit(buildFrom)).forEach(structure -> {
            boolean reactor = false;
            if (structure.unit().getAddOnTag().isPresent()) {
                Tag addOn = structure.unit().getAddOnTag().get();
                UnitInPool addOnUnit = observation().getUnit(addOn);
                if (addOnUnit != null &&
                        (addOnUnit.unit().getType() == Units.TERRAN_BARRACKS_REACTOR ||
                                addOnUnit.unit().getType() == Units.TERRAN_STARPORT_REACTOR ||
                                addOnUnit.unit().getType() == Units.TERRAN_FACTORY_REACTOR)) {
                    reactor = true;
                }
            }
            List<UnitOrder> orders = structure.unit().getOrders();
            if (orders.isEmpty() || (reactor && orders.size() < 2)) {
                if (!needsCommandCentre()) {
                    actions().unitCommand(structure.unit(), abilityToCast, false);
                }
            }
        });
        return true;
    }

    private boolean needsRefinery() {
        return observation().getFoodWorkers() > 24 &&
                countUnitType(Units.TERRAN_REFINERY) < countUnitType(Constants.TERRAN_CC_TYPES_ARRAY) * (observation().getFoodCap() > 100 ? 2 : 1);
    }

    private boolean tryBuildRefinery() {
        if (!needsRefinery()) {
            return false;
        }
        List<Unit> commandCentres = observation().getUnits(unitInPool ->
                        unitInPool.unit().getAlliance() == Alliance.SELF &&
                                (Constants.TERRAN_CC_TYPES.contains(unitInPool.unit().getType()))).stream()
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
                        tryBuildStructureAtTarget(Abilities.BUILD_REFINERY, Units.TERRAN_REFINERY, Units.TERRAN_SCV,
                                1, Optional.of(geyser));
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
                                (Constants.TERRAN_CC_TYPES.contains(unitInPool.unit().getType()))).stream()
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
     *
     * @param unit   The unit to check
     * @param units  The list of other units to check are near the {@code unit}.
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

    private boolean tryBuildMax(Ability abilityTypeForStructure, UnitType unitTypeForStructure, UnitType unitType,
                                int maxParallel, int max) {
        if (countUnitType(unitTypeForStructure) < max) {
            return tryBuildStructure(abilityTypeForStructure, unitTypeForStructure, unitType, maxParallel,
                    Optional.empty());
        }
        return false;
    }

    private boolean tryBuildStructure(Ability abilityTypeForStructure, UnitType unitTypeForStructure,
                                      UnitType unitType, int maxParallel, Optional<Point2d> specificPosition) {
        return _tryBuildStructure(abilityTypeForStructure, unitTypeForStructure, unitType, maxParallel,
                specificPosition, Optional.empty());
    }

    private boolean tryBuildStructureAtTarget(Ability abilityTypeForStructure, UnitType unitTypeForStructure,
                                              UnitType unitType, int maxParallel, Optional<Unit> specificTarget) {
        return _tryBuildStructure(abilityTypeForStructure, unitTypeForStructure, unitType, maxParallel,
                Optional.empty(), specificTarget);
    }

    private boolean _tryBuildStructure(Ability abilityTypeForStructure, UnitType unitTypeForStructure,
                                       UnitType unitType, int maxParallel, Optional<Point2d> specificPosition,
                                       Optional<Unit> specificTarget) {
        BuildStructureTask maybeTask = new BuildStructureTask(
                abilityTypeForStructure,
                unitTypeForStructure,
                specificPosition,
                specificTarget,
                gameData.getUnitMineralCost(unitTypeForStructure),
                gameData.getUnitVespeneCost(unitTypeForStructure),
                Optional.empty());
        return taskManager.addTask(maybeTask, maxParallel);
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
        if (!(unitInPool.unit().getType() instanceof Units)) {
            return;
        }
        switch ((Units) unitInPool.unit().getType()) {
            case TERRAN_MARINE:
            case TERRAN_MARAUDER:
            case TERRAN_MEDIVAC:
                fightManager.addUnit(unitInPool.unit());
                break;
        }
    }

    @Override
    public void onUnitIdle(UnitInPool unitInPool) {
        Unit unit = unitInPool.unit();
        switch ((Units) unit.getType()) {
            case TERRAN_SCV:
                findNearestCommandCentre(unit.getPosition().toPoint2d()).ifPresent(commandCentre -> {
                    findNearestMineralPatch(commandCentre.getPosition().toPoint2d()).ifPresent(mineralPath ->
                            actions().unitCommand(unit, Abilities.SMART, mineralPath, false));
                });
                break;
            case TERRAN_MARINE:
            case TERRAN_MARAUDER:
            case TERRAN_MEDIVAC:
                fightManager.onUnitIdle(unitInPool);
                break;
            default:
                break;
        }
    }

    private Optional<Unit> findNearestCommandCentre(Point2d start) {
        List<UnitInPool> units = observation().getUnits(
                UnitFilter.builder()
                        .alliance(Alliance.SELF)
                        .unitTypes(Constants.TERRAN_CC_TYPES).build());
        return units.stream()
                .min(UnitComparator.builder()
                        .distanceToPoint(start)
                        .ascending(true).build())
                .map(unitInPool -> unitInPool.unit());
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
        int numCc = countUnitType(Constants.TERRAN_CC_TYPES_ARRAY);
        if (numBarracks > numCc * 3) {
            return false;
        }
        Optional<Point2d> position = Optional.empty();

        if (numBarracks == 0 && structurePlacementCalculator.isPresent()) {
            position = structurePlacementCalculator.get()
                    .getFirstBarracksLocation(observation().getStartLocation().toPoint2d());
        }

        int maxParallel = Math.max(1, observation().getMinerals() / 250);

        return tryBuildStructure(Abilities.BUILD_BARRACKS, Units.TERRAN_BARRACKS, Units.TERRAN_SCV, maxParallel,
                position);
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


    @Override
    public Optional<StructurePlacementCalculator> structurePlacementCalculator() {
        return structurePlacementCalculator;
    }

    @Override
    public GameData gameData() {
        return gameData;
    }

    @Override
    public MapAwareness mapAwareness() {
        return mapAwareness;
    }
}
