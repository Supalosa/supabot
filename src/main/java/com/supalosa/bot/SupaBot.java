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
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.supalosa.bot.analysis.AnalyseMap;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.awareness.*;
import com.supalosa.bot.debug.DebugTarget;
import com.supalosa.bot.engagement.TerranBioThreatCalculator;
import com.supalosa.bot.engagement.ThreatCalculator;
import com.supalosa.bot.placement.StructurePlacementCalculator;
import com.supalosa.bot.task.*;
import com.supalosa.bot.task.terran.OrbitalCommandManagerTask;
import com.supalosa.bot.utils.UnitComparator;
import com.supalosa.bot.utils.UnitFilter;
import com.supalosa.bot.utils.Utils;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SupaBot extends S2Agent implements AgentData {

    private static final long GAS_CHECK_INTERVAL = 22L;
    // expected amount before we start pulling workers from gas back to minerals.
    public static final int MINIMUM_MINERAL_WORKERS_PER_CC = 14;
    private final TaskManager taskManager;
    private final FightManager fightManager;
    private final GameData gameData;
    private final MapAwareness mapAwareness;
    private final EnemyAwareness enemyAwareness;
    private long lastGasCheck = 0L;
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
    private boolean isDebug = false;
    private boolean isSlow = false;
    private Optional<AnalysisResults> mapAnalysis = Optional.empty();
    private Optional<StructurePlacementCalculator> structurePlacementCalculator = Optional.empty();
    private Map<UnitType, UnitTypeData> unitTypeData = null;
    private long lastRebalanceAt = 0L;
    private Long lastExpansionTime = 0L;

    private Multimap<Integer, Task> singletonTasksToDispatch = ArrayListMultimap.create();

    private final DebugTarget debugTarget;

    public SupaBot(boolean isDebug, DebugTarget debugTarget) {
        this.isDebug = isDebug;
        ThreatCalculator threatCalculator = new TerranBioThreatCalculator();
        this.taskManager = new TaskManagerImpl();
        this.fightManager = new FightManager(this);
        this.mapAwareness = new MapAwarenessImpl(threatCalculator);
        this.enemyAwareness = new EnemyAwarenessImpl(threatCalculator);
        this.gameData = new GameData(observation());
        this.debugTarget = debugTarget;
    }

    @Override
    public void onGameEnd() {
        this.debugTarget.stop();
    }

    @Override
    public void onGameStart() {
        this.debugTarget.initialise(this);
        this.unitTypeData = observation().getUnitTypeData(true);
        mapAnalysis = observation().getGameInfo(true).getStartRaw().map(startRaw -> AnalyseMap.analyse(
                observation(),
                gameData,
                startRaw));
        structurePlacementCalculator = mapAnalysis
                .map(analysisResults -> new StructurePlacementCalculator(analysisResults, gameData,
                        observation().getStartLocation().toPoint2d()));
        this.mapAwareness.setStartPosition(observation().getStartLocation().toPoint2d());
        mapAnalysis.ifPresent(analysis -> this.mapAwareness.setMapAnalysisResults(analysis));

        dispatchTaskOnce(18, new ScoutTask(mapAwareness.getMaybeEnemyPositionNearEnemy(), 1));
        dispatchTaskOnce(15, new OrbitalCommandManagerTask(100));
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
        enemyAwareness.onStep(this.observation(), this);
        if (structurePlacementCalculator.isPresent()) {
            structurePlacementCalculator.get().onStep(this, this);
        }
        tryBuildSupplyDepot();
        tryBuildBarracks();
        tryBuildCommandCentre();
        tryBuildRefinery();
        int supply = observation().getFoodUsed();
        Set<Upgrade> upgrades = new HashSet<>(observation().getUpgrades());

        if (supply > 50) {
            int targetFactories = supply > 160 ? 2 : 1;
            if (supply == 200) {
                targetFactories = 4;
            }
            tryBuildMax(Abilities.BUILD_FACTORY, Units.TERRAN_FACTORY, Units.TERRAN_SCV, 1, targetFactories);
            observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    unitInPool.unit().getAddOnTag().isEmpty() &&
                    UnitInPool.isUnit(Units.TERRAN_FACTORY).test(unitInPool)).forEach(unit -> {
                actions().unitCommand(unit.unit(),
                        countUnitType(Units.TERRAN_FACTORY_TECHLAB) == 0 ?
                                Abilities.BUILD_TECHLAB_FACTORY :
                                Abilities.BUILD_REACTOR_FACTORY, unit.unit().getPosition().toPoint2d(),
                        false);
            });
            tryGetUpgrades(upgrades, Units.TERRAN_FACTORY_TECHLAB, Map.of(
                    Upgrades.DRILL_CLAWS, Abilities.RESEARCH_DRILLING_CLAWS
            ));
        }
        if (supply > 70) {
            tryBuildMax(Abilities.BUILD_ENGINEERING_BAY, Units.TERRAN_ENGINEERING_BAY, Units.TERRAN_SCV, 1, 2);
        }
        if (supply > 100) {
            tryBuildMax(Abilities.BUILD_ARMORY, Units.TERRAN_ARMORY, Units.TERRAN_SCV, 1, 1);
        }
        if (supply > 50) {
            int targetStarports = supply > 160 ? 2 : 1;
            if (supply == 200) {
                targetStarports = 4;
            }
            tryBuildMax(Abilities.BUILD_STARPORT, Units.TERRAN_STARPORT, Units.TERRAN_SCV, 1, targetStarports);
            observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    unitInPool.unit().getAddOnTag().isEmpty() &&
                    UnitInPool.isUnit(Units.TERRAN_STARPORT).test(unitInPool)).forEach(unit -> {
                actions().unitCommand(unit.unit(),
                        countUnitType(Units.TERRAN_STARPORT_REACTOR) == 0 ?
                                Abilities.BUILD_REACTOR_STARPORT :
                                Abilities.BUILD_TECHLAB_STARPORT, unit.unit().getPosition().toPoint2d(),
                        false);
            });
        }
        if (supply > 40) {
            observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    unitInPool.unit().getAddOnTag().isEmpty() &&
                    UnitInPool.isUnit(Units.TERRAN_BARRACKS).test(unitInPool)).forEach(unit -> {
                Ability ability = Abilities.BUILD_REACTOR;
                if (countUnitType(Units.TERRAN_BARRACKS_TECHLAB) == 0 || ThreadLocalRandom.current().nextBoolean()) {
                    ability = Abilities.BUILD_TECHLAB;
                }
                actions().unitCommand(unit.unit(), ability, unit.unit().getPosition().toPoint2d(), false);
            });
            tryGetUpgrades(upgrades, Units.TERRAN_BARRACKS_TECHLAB, Map.of(
                Upgrades.COMBAT_SHIELD, Abilities.RESEARCH_COMBAT_SHIELD,
                Upgrades.STIMPACK, Abilities.RESEARCH_STIMPACK,
                Upgrades.JACKHAMMER_CONCUSSION_GRENADES, Abilities.RESEARCH_CONCUSSIVE_SHELLS
            ));
            tryGetUpgrades(upgrades, Units.TERRAN_ENGINEERING_BAY, Map.of(
                    Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL1, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL1,
                    Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL2, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL2,
                    Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL3, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL3,
                    Upgrades.TERRAN_INFANTRY_ARMORS_LEVEL1, Abilities.RESEARCH_TERRAN_INFANTRY_ARMOR_LEVEL1,
                    Upgrades.TERRAN_INFANTRY_ARMORS_LEVEL2, Abilities.RESEARCH_TERRAN_INFANTRY_ARMOR_LEVEL2,
                    Upgrades.TERRAN_INFANTRY_ARMORS_LEVEL3, Abilities.RESEARCH_TERRAN_INFANTRY_ARMOR_LEVEL3
            ));
        }
        if (supply > 120) {
            tryGetUpgrades(upgrades, Units.TERRAN_ENGINEERING_BAY, Map.of(
                    Upgrades.TERRAN_BUILDING_ARMOR, Abilities.RESEARCH_TERRAN_STRUCTURE_ARMOR_UPGRADE
            ));
        }
        // upgrade orbital/planetaries
        int numCcs = countUnitType(Constants.TERRAN_CC_TYPES_ARRAY);
        observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                UnitInPool.isUnit(Units.TERRAN_COMMAND_CENTER).test(unitInPool)).forEach(unit -> {
            Ability ability = Abilities.MORPH_ORBITAL_COMMAND;
            if (numCcs > 4) {
                ability = Abilities.MORPH_PLANETARY_FORTRESS;
            }
            actions().unitCommand(unit.unit(), ability, false);
        });
        tryBuildScvs();
        if (fightManager.hasSeenCloakedOrBurrowedUnits() ||
                mapAwareness.getObservedCreepCoverage().map(coverage -> coverage > 0.1f).orElse(false)) {
            tryBuildUnit(Abilities.TRAIN_RAVEN, Units.TERRAN_RAVEN, Units.TERRAN_STARPORT, true, Optional.of(1));
        }
        List<UnitTypeRequest> requestedUnitTypes = fightManager.getRequestedUnitTypes();
        if (requestedUnitTypes.size() > 0) {
            // TODO better logic to prevent starvation.
            // Here we just wait until we have the highest mineral cost.
            // However that will lead to lower costs being starved.
            int maxMineralCost = requestedUnitTypes.stream()
                    .filter(request -> request.amount() > 0)
                    .mapToInt(request ->
                        gameData.getUnitMineralCost(request.unitType()).orElse(50))
                    .max()
                    .orElse(50);
            //System.out.println("Waiting for " + maxMineralCost + " minerals");
            if (observation().getMinerals() > maxMineralCost) {
                Collections.shuffle(requestedUnitTypes);
                requestedUnitTypes.forEach(requestedUnitType -> {
                    //System.out.println("Making " + requestedUnitType.unitType() + " (max " + requestedUnitType.amount() + ")");
                    UnitType[] type;
                    if (requestedUnitType.alternateForm().isPresent()) {
                        type = new UnitType[]{requestedUnitType.unitType(), requestedUnitType.alternateForm().get()};
                    } else {
                        type = new UnitType[]{requestedUnitType.unitType()};
                    }
                    tryBuildUnit(
                            requestedUnitType.productionAbility(),
                            type,
                            requestedUnitType.producingUnitType(),
                            requestedUnitType.needsTechLab(),
                            Optional.of(requestedUnitType.amount()));
                });
            }
        }
        rebalanceWorkers();

        mineGas();
        taskManager.onStep(this, this);
        fightManager.onStep(taskManager, this);

        // Open or close the ramp.
        structurePlacementCalculator.ifPresent(spc -> {
            AtomicBoolean rampClosed = new AtomicBoolean(false);
            spc.getFirstSupplyDepot(observation()).ifPresent(supplyDepot -> {
                if (observation().getUnits(Alliance.ENEMY).stream()
                        .anyMatch(enemyUnit -> enemyUnit
                                .getUnit()
                                .filter(uip -> uip.getPosition().distance(supplyDepot.unit().getPosition()) < 8)
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
        });

        // Dispatch one-off tasks.
        if (singletonTasksToDispatch.size() > 0) {
            Set<Integer> suppliesReached = new HashSet<>();
            Map<Task, Integer> notDispatched = new HashMap<>();
            singletonTasksToDispatch.forEach((supplyTrigger, task) -> {
                if (observation().getFoodUsed() >= supplyTrigger) {
                    suppliesReached.add(supplyTrigger);
                    if (!taskManager.addTask(task, 1)) {
                        notDispatched.put(task, supplyTrigger);
                    }
                }
            });
            suppliesReached.forEach(supplyTrigger -> singletonTasksToDispatch.removeAll(supplyTrigger));
            notDispatched.forEach((task, supplyTrigger) -> singletonTasksToDispatch.put(supplyTrigger, task));
        }

        List<ChatReceived> chat = observation().getChatMessages();
        for (ChatReceived chatReceived : chat) {
            if (chatReceived.getPlayerId() != observation().getPlayerId()) {
                continue;
            }
            if (chatReceived.getMessage().contains("debug")) {
                this.isDebug = !isDebug;
                // send one more debug command to flush the buffer.
                debug().sendDebug();
                actions().sendChat("Debug: " + isDebug, ActionChat.Channel.TEAM);
            }

            if (chatReceived.getMessage().contains("slow")) {
                this.isDebug = true;
                this.isSlow = !this.isSlow;
                actions().sendChat("Slow: " + isSlow, ActionChat.Channel.TEAM);
            }
        }

        debugTarget.onStep(this, this);
        if (isDebug) {
            if (isSlow) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (this.taskManager != null) {
                this.taskManager.debug(this);
            }
            this.mapAwareness().debug(this);
            this.enemyAwareness.debug(this);
            this.structurePlacementCalculator.ifPresent(spc -> spc.debug(this));
            this.fightManager.debug(this);
            int miningBases = countMiningBases();
            debug().debugTextOut("Bases: " + miningBases, Point2d.of(0.95f, 0.2f), Color.WHITE, 8);
            mapAwareness.getObservedCreepCoverage().ifPresent(creep ->
                    debug().debugTextOut(String.format("Creep: %.1f%%", (creep * 100f)), Point2d.of(0.92f, 0.25f), Color.WHITE, 8));

            debug().sendDebug();
        }
    }

    private void dispatchTaskOnce(int atSupply, Task task) {
        singletonTasksToDispatch.put(atSupply, task);
    }

    private void tryGetUpgrades(Set<Upgrade> upgrades, Units structure, Map<Upgrades, Abilities> upgradesToGet) {
        Set<Upgrades> upgradesMissing = new HashSet<>(upgradesToGet.keySet());
        upgradesMissing.removeAll(upgrades);
        if (upgradesMissing.size() == 0) {
            return;
        }

        observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                UnitInPool.isUnit(structure).test(unitInPool)).forEach(unit -> {
            if (unit.unit().getOrders().isEmpty()) {
                for (Map.Entry<Upgrades, Abilities> upgrade: upgradesToGet.entrySet()) {
                    if (!upgrades.contains(upgrade.getKey())) {
                        actions().unitCommand(unit.unit(), upgrade.getValue(), false);
                    }
                }
            }
        });
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
                    Optional<Unit> nearestMineralPatch = Utils.findNearestMineralPatch(observation(), takerCc.getPosition().toPoint2d());
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
        // Expand every 16 workers.
        final int[] expansionNumWorkers = new int[]{0, 18, 32, 48, 64, 72};
        int currentWorkers = observation().getFoodWorkers();
        int numCcs = countMiningBases();
        int index = Math.min(expansionNumWorkers.length - 1, Math.max(0, numCcs));
        int nextExpansionAt = expansionNumWorkers[index];
        if (observation().getGameLoop() < lastExpansionTime + 22L) {
            return false;
        }
        if (getNumBuildingStructure(Abilities.BUILD_COMMAND_CENTER) > 0) {
            return false;
        }
        if (this.mapAwareness.getValidExpansionLocations().isEmpty()) {
            return false;
        }
        return currentWorkers >= nextExpansionAt;
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
        int productionCapableBuildings = countUnitType(Constants.TERRAN_CC_TYPES_ARRAY);
        productionCapableBuildings += countUnitType(Units.TERRAN_BARRACKS, Units.TERRAN_FACTORY, Units.TERRAN_STARPORT);
        if (observation().getFoodUsed() <= observation().getFoodCap() - productionCapableBuildings) {
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
        if (observation().getFoodUsed() == observation().getFoodCap()) {
            return false;
        }
        observation().getUnits(Alliance.SELF,
                unitInPool -> Constants.TERRAN_CC_TYPES.contains(unitInPool.unit().getType())).forEach(commandCentre -> {
            if (commandCentre.unit().getBuildProgress() < 1.0f) {
                return;
            }
            if (commandCentre.unit().getOrders().isEmpty()) {
                if (numScvs < Math.min(80, numBases * 22)) {
                    actions().unitCommand(commandCentre.unit(), Abilities.TRAIN_SCV, false);
                }
            }
        });
        return true;
    }
    private boolean tryBuildUnit(Ability abilityToCast, UnitType unitType, UnitType buildFrom,
                                 boolean needTechLab, Optional<Integer> maximum) {
        return tryBuildUnit(abilityToCast, new UnitType[]{unitType}, buildFrom, needTechLab, maximum);
    }

    private boolean tryBuildUnit(Ability abilityToCast, UnitType[] unitTypes, UnitType buildFrom,
                                 boolean needTechLab, Optional<Integer> maximum) {
        int count = countUnitType(unitTypes);
        if (maximum.isPresent()) {
            if (count >= maximum.get()) {
                return false;
            }
        }
        if (observation().getFoodUsed() == observation().getFoodCap()) {
            return false;
        }
        AtomicInteger amountNeeded = new AtomicInteger(maximum.orElse(1000) - count);
        observation()
                .getUnits(UnitFilter.builder().alliance(Alliance.SELF).unitType(buildFrom).build())
                .forEach(structure -> {
            boolean reactor = false;
            boolean techLab = false;
            if (structure.unit().getBuildProgress() < 1.0f) {
                return;
            }
            if (amountNeeded.get() <= 0) {
                return;
            }
            if (structure.unit().getAddOnTag().isPresent()) {
                Tag addOn = structure.unit().getAddOnTag().get();
                UnitInPool addOnUnit = observation().getUnit(addOn);
                if (addOnUnit != null) {
                    if (addOnUnit.unit().getType() == Units.TERRAN_BARRACKS_REACTOR ||
                            addOnUnit.unit().getType() == Units.TERRAN_STARPORT_REACTOR ||
                            addOnUnit.unit().getType() == Units.TERRAN_FACTORY_REACTOR) {
                        reactor = true;
                    }
                    if (addOnUnit.unit().getType() == Units.TERRAN_BARRACKS_TECHLAB ||
                            addOnUnit.unit().getType() == Units.TERRAN_STARPORT_TECHLAB ||
                            addOnUnit.unit().getType() == Units.TERRAN_FACTORY_TECHLAB) {
                        techLab = true;
                    }
                }
            }
            if (needTechLab && !techLab) {
                return;
            }
            List<UnitOrder> orders = structure.unit().getOrders();
            boolean structureInHighThreat = mapAwareness.getRegionDataForPoint
                    (structure.unit().getPosition().toPoint2d())
                    .map(regionData -> {
                        //System.out.println("Nearby threat " + regionData.nearbyEnemyThreat() + " vs " + regionData.playerThreat());
                        return regionData.nearbyEnemyThreat() > regionData.playerThreat();
                    })
                    .orElse(false);
            if (orders.isEmpty() || (reactor && orders.size() < 2)) {
                // Hack here - need prioritsation.
                if (structureInHighThreat ||
                        (observation().getMinerals() > taskManager.totalReservedMinerals() &&
                                observation().getVespene() > taskManager.totalReservedVespene())) {
                    actions().unitCommand(structure.unit(), abilityToCast, false);
                    amountNeeded.decrementAndGet();
                }
            }
        });
        return true;
    }

    private boolean needsRefinery() {
        return observation().getFoodWorkers() > 18 &&
                countUnitType(Units.TERRAN_REFINERY) < countUnitType(Constants.TERRAN_CC_TYPES_ARRAY) * (observation().getFoodUsed() > 100 ? 2 : 1);
    }

    private boolean tryBuildRefinery() {
        if (!needsRefinery()) {
            return false;
        }
        List<Unit> commandCentres = observation().getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.SELF)
                            .unitTypes(Constants.TERRAN_CC_TYPES).build())
                .stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        List<Unit> refineries = observation().getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.SELF)
                            .unitType(Units.TERRAN_REFINERY)
                            .filter(unit -> hasUnitNearby(unit, commandCentres, 10f))
                            .build())
                .stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        List<Unit> neutralGeysers = observation().getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.NEUTRAL)
                            .unitTypes(Constants.VESPENE_GEYSER_TYPES)
                            .filter(unit -> hasUnitNearby(unit, commandCentres, 10f))
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
        lastGasCheck = gameLoop;
        List<Unit> commandCentres = observation().getUnits(unitInPool ->
                        unitInPool.unit().getAlliance() == Alliance.SELF &&
                                (Constants.ALL_TOWN_HALL_TYPES.contains(unitInPool.unit().getType()))).stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        List<Unit> refineries = observation().getUnits(unitInPool ->
                        unitInPool.unit().getAlliance().equals(Alliance.SELF) &&
                                unitInPool.unit().getType().equals(Units.TERRAN_REFINERY) &&
                                hasUnitNearby(unitInPool.unit(), commandCentres, 10f)
                ).stream()
                .map(UnitInPool::unit)
                .collect(Collectors.toList());
        Set<Unit> ccsDoneThisRun = new HashSet<>();
        refineries.forEach(refinery -> {
            if (refinery.getAssignedHarvesters().isPresent() &&
                    refinery.getIdealHarvesters().isPresent()) {
                Optional<Unit> nearCc = commandCentres.stream().filter(cc -> cc.getPosition().distance(refinery.getPosition()) < 10f).findAny();
                int delta = refinery.getIdealHarvesters().get() - refinery.getAssignedHarvesters().get();
                if (nearCc.isPresent()) {
                    int desiredHarvesters = Math.min(MINIMUM_MINERAL_WORKERS_PER_CC, nearCc.get().getIdealHarvesters().orElse(0));
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
                    List<Unit> nearbyScvs = observation().getUnits(unitInPool ->
                                    unitInPool.unit().getAlliance().equals(Alliance.SELF) &&
                                            unitInPool.unit().getType() == Units.TERRAN_SCV &&
                                            UnitInPool.isCarryingMinerals().test(unitInPool) &&
                                            unitInPool.unit().getPosition().distance(refinery.getPosition()) < 8.0f)
                            .stream().map(UnitInPool::unit).collect(Collectors.toList());
                    for (int i = 0; i < Math.min(nearbyScvs.size(), delta); ++i) {
                        actions().unitCommand(nearbyScvs.get(i), Abilities.SMART, refinery, false);
                    }
                    ccsDoneThisRun.add(nearCc.get());
                } else if (nearCc.isPresent() && delta < 0) {
                    List<Unit> nearbyScvs = observation().getUnits(unitInPool ->
                            unitInPool.unit().getOrders().stream().anyMatch(order -> order.getTargetedUnitTag().equals(Optional.of(refinery.getTag()))) &&
                                    unitInPool.unit().getAlliance().equals(Alliance.SELF) &&
                                            unitInPool.unit().getType() == Units.TERRAN_SCV &&
                                            unitInPool.unit().getPosition().distance(refinery.getPosition()) < 8.0f)
                            .stream().map(UnitInPool::unit).collect(Collectors.toList());

                    Optional<Unit> nearMinerals = Utils.findNearestMineralPatch(observation(), nearCc.get().getPosition().toPoint2d());
                    for (int i = 0; i < Math.min(nearbyScvs.size(), Math.abs(delta)); ++i) {
                        actions().unitCommand(nearbyScvs.get(i), Abilities.SMART, nearMinerals.get(), false);
                    }
                    ccsDoneThisRun.add(nearCc.get());
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
        // hack
        if (needsCommandCentre() && (unitTypeForStructure != Units.TERRAN_COMMAND_CENTER)) {
            return false;
        }
        BuildStructureTask maybeTask = new BuildStructureTask(
                abilityTypeForStructure,
                unitTypeForStructure,
                specificPosition,
                specificTarget,
                gameData.getUnitMineralCost(unitTypeForStructure),
                gameData.getUnitVespeneCost(unitTypeForStructure),
                Optional.empty());
        if (taskManager.addTask(maybeTask, maxParallel)) {
            long gameLoop = observation().getGameLoop();
            long currentMinerals = observation().getMinerals();
            long currentSupply = observation().getFoodUsed();
            System.out.println("Task for " + unitTypeForStructure + " started at " + gameLoop + " (mins " + currentMinerals + ", supply " + currentSupply + ")");
            return true;
        } else {
            return false;
        }
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
        // First barracks gets rallied behind it. This defends against 12 pools etc.
        if (unitInPool.unit().getType() == Units.TERRAN_BARRACKS) {
            if (countUnitType(Units.TERRAN_BARRACKS) <= 1) {
                structurePlacementCalculator.ifPresent(spc -> {
                    // Defend from behind the barracks, or else the position of the barracks.
                    spc.getMainRamp()
                        .flatMap(ramp -> ramp.projection(5.0f)).ifPresent(projection -> {
                            actions().unitCommand(unitInPool.getTag(), Abilities.RALLY_BUILDING, projection, false);
                        });
                });
            }
        }
        taskManager.dispatchUnit(unitInPool.unit());
    }

    @Override
    public void onUnitIdle(UnitInPool unitInPool) {
        Unit unit = unitInPool.unit();
        if (!(unit.getType() instanceof Units)) {
            return;
        }
        switch ((Units) unit.getType()) {
            case TERRAN_SCV:
                findNearestCommandCentreWithMinerals(unit.getPosition().toPoint2d()).ifPresent(commandCentre -> {
                    Utils.findNearestMineralPatch(observation(), commandCentre.getPosition().toPoint2d()).ifPresent(mineralPatch ->
                            actions().unitCommand(unit, Abilities.SMART, mineralPatch, false));
                });
                break;
            default:
                fightManager.onUnitIdle(unitInPool);
                break;
        }
    }

    private Optional<Unit> findNearestCommandCentreWithMinerals(Point2d start) {
        List<UnitInPool> units = observation().getUnits(
                UnitFilter.builder()
                        .alliance(Alliance.SELF)
                        .unitTypes(Constants.TERRAN_CC_TYPES)
                        .filter(unit -> unit.getIdealHarvesters().isPresent())
                        .build());
        return units.stream()
                .min(UnitComparator.builder()
                        .distanceToPoint(start)
                        .ascending(true).build())
                .map(unitInPool -> unitInPool.unit());
    }

    private boolean tryBuildBarracks() {
        if (countUnitType(Units.TERRAN_SUPPLY_DEPOT, Units.TERRAN_SUPPLY_DEPOT_LOWERED) < 1) {
            return false;
        }
        if (needsSupplyDepot() && observation().getMinerals() < 150) {
            return false;
        }
        if (needsCommandCentre()) {
            return false;
        }

        int numBarracks = countUnitType(Units.TERRAN_BARRACKS);
        int numCc = countMiningBases();
        int barracksPerCc = observation().getMinerals() > 1000 ? 4 : 3;
        if (observation().getMinerals() > 2000) {
            barracksPerCc = 6;
        }
        if (numBarracks > numCc * barracksPerCc) {
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
    public Optional<AnalysisResults> mapAnalysis() {
        return this.mapAnalysis;
    }

    @Override
    public MapAwareness mapAwareness() {
        return mapAwareness;
    }

    @Override
    public FightManager fightManager() {
        return fightManager;
    }

    @Override
    public TaskManager taskManager() {
        return taskManager;
    }

    @Override
    public EnemyAwareness enemyAwareness() {
        return enemyAwareness;
    }

    @Override
    public void onUnitDestroyed(UnitInPool unit) {
        enemyAwareness.onUnitDestroyed(unit);
    }

}
