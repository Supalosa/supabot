package com.supalosa.bot.task.terran;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.github.ocraft.s2client.protocol.unit.UnitOrder;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.Expansion;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.placement.PlacementRules;
import com.supalosa.bot.task.*;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.utils.UnitFilter;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The default Terran build behaviour behaviour.
 * Although this task works to start a game from scratch, it's probably better to start from
 * a build order task.
 */
public class BaseTerranTask implements BehaviourTask {

    // expected amount before we start pulling workers from gas back to minerals.
    public static final int MINIMUM_MINERAL_WORKERS_PER_CC = 14;

    private Long lastExpansionTime = 0L;
    private long lastGasCheck = 0L;
    private long lastRebalanceAt = 0L;
    private static final long GAS_CHECK_INTERVAL = 22L;
    private LoadingCache<UnitType, Integer> countOfUnits = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.SECONDS)
            .build(new CacheLoader<>() {
                @Override
                public Integer load(UnitType key) {
                    System.err.println("Query for unit type " + key + " before any step started.");
                    return 0;
                }
            });;

    @Override
    public void onStep(TaskManager taskManager, AgentWithData agentWithData) {
        countOfUnits = CacheBuilder.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(1, TimeUnit.SECONDS)
                .build(new CacheLoader<>() {
                    @Override
                    public Integer load(UnitType key) {
                        int count = agentWithData.observation().getUnits(Alliance.SELF, UnitInPool.isUnit(key)).size();
                        return count;
                    }
                });
        countOfUnits.invalidateAll();
        tryBuildSupplyDepot(agentWithData);
        tryBuildBarracks(agentWithData);
        tryBuildCommandCentre(agentWithData);
        tryBuildRefinery(agentWithData);
        int supply = agentWithData.observation().getFoodUsed();
        Set<Upgrade> upgrades = new HashSet<>(agentWithData.observation().getUpgrades());

        if (supply > 50) {
            int targetFactories = supply > 160 ? 2 : 1;
            if (supply == 200) {
                targetFactories = 4;
            }
            tryBuildMax(agentWithData, Abilities.BUILD_FACTORY,
                    Units.TERRAN_FACTORY,
                    Units.TERRAN_SCV,
                    1,
                    targetFactories,
                    Optional.of(PlacementRules.centreOfBase()));
            agentWithData.observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    unitInPool.unit().getAddOnTag().isEmpty() &&
                    UnitInPool.isUnit(Units.TERRAN_FACTORY).test(unitInPool)).forEach(unit -> {
                agentWithData.actions().unitCommand(unit.unit(),
                        countUnitType(Units.TERRAN_FACTORY_TECHLAB) == 0 ?
                                Abilities.BUILD_TECHLAB_FACTORY :
                                Abilities.BUILD_REACTOR_FACTORY, unit.unit().getPosition().toPoint2d(),
                        false);
            });
            tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_FACTORY_TECHLAB, Map.of(
                    Upgrades.DRILL_CLAWS, Abilities.RESEARCH_DRILLING_CLAWS
            ));
        }
        if (supply > 70) {
            tryBuildMax(agentWithData,
                    Abilities.BUILD_ENGINEERING_BAY,
                    Units.TERRAN_ENGINEERING_BAY,
                    Units.TERRAN_SCV, 1, 2,
                    Optional.of(PlacementRules.borderOfBase()));
        }
        if (supply > 100) {
            tryBuildMax(agentWithData, Abilities.BUILD_ARMORY, Units.TERRAN_ARMORY, Units.TERRAN_SCV, 1, 1, Optional.of(PlacementRules.borderOfBase()));
        }
        if (supply > 150) {
            tryBuildMax(agentWithData,
                    Abilities.BUILD_GHOST_ACADEMY,
                    Units.TERRAN_GHOST_ACADEMY,
                    Units.TERRAN_SCV,
                    1, 1,
                    Optional.of(PlacementRules.borderOfBase()));
            tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_GHOST_ACADEMY, Map.of(
                    Upgrades.PERSONAL_CLOAKING, Abilities.RESEARCH_PERSONAL_CLOAKING,
                    Upgrades.ENHANCED_SHOCKWAVES, Abilities.RESEARCH_TERRAN_GHOST_ENHANCED_SHOCKWAVES
            ));
        }
        if (supply > 50) {
            int targetStarports = supply > 160 ? 2 : 1;
            if (supply == 200) {
                targetStarports = 4;
            }
            tryBuildMax(agentWithData, Abilities.BUILD_STARPORT, Units.TERRAN_STARPORT, Units.TERRAN_SCV, 2, targetStarports, Optional.of(PlacementRules.borderOfBase()));
            agentWithData.observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    unitInPool.unit().getAddOnTag().isEmpty() &&
                    UnitInPool.isUnit(Units.TERRAN_STARPORT).test(unitInPool)).forEach(unit -> {
                agentWithData.actions().unitCommand(unit.unit(),
                        countUnitType(Units.TERRAN_STARPORT_REACTOR) == 0 ?
                                Abilities.BUILD_REACTOR_STARPORT :
                                Abilities.BUILD_TECHLAB_STARPORT, unit.unit().getPosition().toPoint2d(),
                        false);
            });
        }
        if (supply > 40) {
            agentWithData.observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    unitInPool.unit().getAddOnTag().isEmpty() &&
                    UnitInPool.isUnit(Units.TERRAN_BARRACKS).test(unitInPool)).forEach(unit -> {
                Ability ability = Abilities.BUILD_REACTOR;
                if (countUnitType(Units.TERRAN_BARRACKS_TECHLAB) == 0 || ThreadLocalRandom.current().nextBoolean()) {
                    ability = Abilities.BUILD_TECHLAB;
                }
                agentWithData.actions().unitCommand(unit.unit(), ability, unit.unit().getPosition().toPoint2d(), false);
            });
            tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_BARRACKS_TECHLAB, Map.of(
                    Upgrades.COMBAT_SHIELD, Abilities.RESEARCH_COMBAT_SHIELD,
                    Upgrades.STIMPACK, Abilities.RESEARCH_STIMPACK,
                    Upgrades.JACKHAMMER_CONCUSSION_GRENADES, Abilities.RESEARCH_CONCUSSIVE_SHELLS
            ));
            tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_ENGINEERING_BAY, Map.of(
                    Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL1, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL1,
                    Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL2, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL2,
                    Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL3, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS_LEVEL3,
                    Upgrades.TERRAN_INFANTRY_ARMORS_LEVEL1, Abilities.RESEARCH_TERRAN_INFANTRY_ARMOR_LEVEL1,
                    Upgrades.TERRAN_INFANTRY_ARMORS_LEVEL2, Abilities.RESEARCH_TERRAN_INFANTRY_ARMOR_LEVEL2,
                    Upgrades.TERRAN_INFANTRY_ARMORS_LEVEL3, Abilities.RESEARCH_TERRAN_INFANTRY_ARMOR_LEVEL3
            ));
        }
        if (supply > 120) {
            tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_ENGINEERING_BAY, Map.of(
                    Upgrades.TERRAN_BUILDING_ARMOR, Abilities.RESEARCH_TERRAN_STRUCTURE_ARMOR_UPGRADE
            ));
        }
        // upgrade orbital/planetaries
        int numCcs = countUnitType(Constants.TERRAN_CC_TYPES_ARRAY);
        agentWithData.observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                UnitInPool.isUnit(Units.TERRAN_COMMAND_CENTER).test(unitInPool)).forEach(unit -> {
            Ability ability = Abilities.MORPH_ORBITAL_COMMAND;
            if (numCcs > 4) {
                ability = Abilities.MORPH_PLANETARY_FORTRESS;
            }
            agentWithData.actions().unitCommand(unit.unit(), ability, false);
        });
        tryBuildScvs(agentWithData);
        if (agentWithData.fightManager().hasSeenCloakedOrBurrowedUnits() ||
                agentWithData.mapAwareness().getObservedCreepCoverage().map(coverage -> coverage > 0.1f).orElse(false)) {
            tryBuildUnit(agentWithData, Abilities.TRAIN_RAVEN, Units.TERRAN_RAVEN, Units.TERRAN_STARPORT, true, Optional.of(1));
        }
        List<UnitTypeRequest> requestedUnitTypes = agentWithData.fightManager().getRequestedUnitTypes();
        if (requestedUnitTypes.size() > 0) {
            // TODO better logic to prevent starvation.
            // Here we just wait until we have the highest mineral cost.
            // However that will lead to lower costs being starved.
            int maxMineralCost = requestedUnitTypes.stream()
                    .filter(request -> request.amount() > 0)
                    .mapToInt(request ->
                            agentWithData.gameData().getUnitMineralCost(request.unitType()).orElse(50))
                    .max()
                    .orElse(50);
            //System.out.println("Waiting for " + maxMineralCost + " minerals");
            if (agentWithData.observation().getMinerals() > maxMineralCost) {
                Collections.shuffle(requestedUnitTypes);
                requestedUnitTypes.forEach(requestedUnitType -> {
                    //System.out.println("Making " + requestedUnitType.unitType() + " (max " + requestedUnitType.amount() + ")");
                    UnitType[] type;
                    if (requestedUnitType.alternateForm().isPresent()) {
                        type = new UnitType[]{requestedUnitType.unitType(), requestedUnitType.alternateForm().get()};
                    } else {
                        type = new UnitType[]{requestedUnitType.unitType()};
                    }
                    tryBuildUnit(agentWithData,
                            requestedUnitType.productionAbility(),
                            type,
                            requestedUnitType.producingUnitType(),
                            requestedUnitType.needsTechLab(),
                            Optional.of(requestedUnitType.amount()));
                });
            }
        }
        rebalanceWorkers(agentWithData);

        mineGas(agentWithData);

        BuildUtils.defaultTerranRamp(agentWithData);

        agentWithData.fightManager().setCanAttack(true);
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
        return "base";
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        return otherTask instanceof BaseTerranTask;
    }

    @Override
    public void debug(S2Agent agent) {

    }

    @Override
    public String getDebugText() {
        return "TerranManager";
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
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

    private int getNumAbilitiesInUse(ObservationInterface observationInterface, Ability abilityTypeForStructure) {
        // If a unit already is building a supply structure of this type, do nothing.
        return observationInterface.getUnits(Alliance.SELF, doesBuildWith(abilityTypeForStructure)).size();
    }

    private boolean tryBuildMax(AgentWithData agentWithData, Ability abilityTypeForStructure, UnitType unitTypeForStructure, UnitType unitType,
                                int maxParallel, int max, Optional<PlacementRules> rules) {
        if (countUnitType(unitTypeForStructure) < max) {
            return tryBuildStructure(agentWithData, abilityTypeForStructure, unitTypeForStructure, unitType, maxParallel,
                    Optional.empty(), rules);
        }
        return false;
    }

    private boolean tryBuildStructure(AgentWithData agentWithData,
                                      Ability abilityTypeForStructure, UnitType unitTypeForStructure,
                                      UnitType unitType, int maxParallel, Optional<Point2d> specificPosition,
                                      Optional<PlacementRules> rules) {
        return _tryBuildStructure(agentWithData, abilityTypeForStructure, unitTypeForStructure, unitType, maxParallel,
                specificPosition, Optional.empty(), rules);
    }

    private boolean tryBuildStructureAtTarget(AgentWithData agentWithData,
                                              Ability abilityTypeForStructure, UnitType unitTypeForStructure,
                                              UnitType unitType, int maxParallel, Optional<Unit> specificTarget) {
        return _tryBuildStructure(agentWithData, abilityTypeForStructure, unitTypeForStructure, unitType, maxParallel,
                Optional.empty(), specificTarget, Optional.empty());
    }

    private boolean _tryBuildStructure(AgentWithData agentWithData,
                                       Ability abilityTypeForStructure, UnitType unitTypeForStructure,
                                       UnitType unitType, int maxParallel, Optional<Point2d> specificPosition,
                                       Optional<Unit> specificTarget, Optional<PlacementRules> rules) {
        // hack
        if (needsCommandCentre(agentWithData) && (unitTypeForStructure != Units.TERRAN_COMMAND_CENTER)) {
            return false;
        }
        BuildStructureTask maybeTask = new BuildStructureTask(
                abilityTypeForStructure,
                unitTypeForStructure,
                specificPosition,
                specificTarget,
                agentWithData.gameData().getUnitMineralCost(unitTypeForStructure),
                agentWithData.gameData().getUnitVespeneCost(unitTypeForStructure),
                rules);
        if (agentWithData.taskManager().addTask(maybeTask, maxParallel)) {
            long gameLoop = agentWithData.observation().getGameLoop();
            long currentMinerals = agentWithData.observation().getMinerals();
            long currentSupply = agentWithData.observation().getFoodUsed();
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

    private boolean tryBuildBarracks(AgentWithData agentWithData) {
        if (countUnitType(Units.TERRAN_SUPPLY_DEPOT, Units.TERRAN_SUPPLY_DEPOT_LOWERED) < 1) {
            return false;
        }
        if (needsSupplyDepot(agentWithData) && agentWithData.observation().getMinerals() < 150) {
            return false;
        }
        if (needsCommandCentre(agentWithData)) {
            return false;
        }

        int numBarracks = countUnitType(Units.TERRAN_BARRACKS);
        int numCc = countMiningBases(agentWithData);
        int minerals = agentWithData.observation().getMinerals();
        int barracksPerCc;
        if (minerals < 200) {
            barracksPerCc = 1;
        } else if (minerals < 500) {
            barracksPerCc = 2;
        } else if (minerals < 1000) {
            barracksPerCc = 3;
        } else if (minerals < 2000) {
            barracksPerCc = 4;
        } else if (minerals < 5000) {
            barracksPerCc = 5;
        } else {
            barracksPerCc = 6;
        }
        if (numBarracks > numCc * barracksPerCc) {
            return false;
        }
        Optional<Point2d> position = Optional.empty();

        if (numBarracks == 0 && agentWithData.structurePlacementCalculator().isPresent()) {
            position = agentWithData.structurePlacementCalculator().get()
                    .getFirstBarracksWithAddonLocation();
        }

        int maxParallel = Math.max(1, agentWithData.observation().getMinerals() / 150);

        return tryBuildStructure(agentWithData, Abilities.BUILD_BARRACKS, Units.TERRAN_BARRACKS, Units.TERRAN_SCV, maxParallel,
                position, Optional.of(PlacementRules.centreOfBase()));
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
    }


    private boolean needsCommandCentre(AgentWithData agentWithData) {
        final ObservationInterface observationInterface = agentWithData.observation();
        final MapAwareness mapAwareness = agentWithData.mapAwareness();
        // Expand every 16 workers.
        final int[] expansionNumWorkers = new int[]{0, 18, 32, 48, 64, 72, 80};
        int currentWorkers = observationInterface.getFoodWorkers();
        int numCcs = countMiningBases(agentWithData);
        if (observationInterface.getMinerals() > 1000) {
            numCcs ++;
        }
        if (observationInterface.getMinerals() > 2000) {
            numCcs ++;
        }
        int index = Math.min(expansionNumWorkers.length - 1, Math.max(0, numCcs));
        int nextExpansionAt = expansionNumWorkers[index];
        if (observationInterface.getGameLoop() < lastExpansionTime + 22L) {
            return false;
        }
        if (getNumAbilitiesInUse(observationInterface, Abilities.BUILD_COMMAND_CENTER) > 0) {
            return false;
        }
        if (mapAwareness.getValidExpansionLocations().isEmpty()) {
            return false;
        }
        return currentWorkers >= nextExpansionAt;
    }

    private int countMiningBases(AgentWithData agentWithData) {
        return agentWithData.observation().getUnits(Alliance.SELF,
                unitInPool -> Constants.TERRAN_CC_TYPES.contains(unitInPool.unit().getType()) &&
                        unitInPool.unit().getBuildProgress() > 0.99f &&
                        unitInPool.unit().getIdealHarvesters().isPresent() &&
                        unitInPool.unit().getIdealHarvesters().get() >= 6
        ).size();
    }

    private boolean tryBuildCommandCentre(AgentWithData agentWithData) {
        if (!needsCommandCentre(agentWithData)) {
            return false;
        }
        if (agentWithData.mapAwareness().getValidExpansionLocations().isEmpty()) {
            agentWithData.actions().sendChat("Valid Expansions missing or empty", ActionChat.Channel.TEAM);
            return false;
        }
        long gameLoop = agentWithData.observation().getGameLoop();
        for (Expansion validExpansionLocation : agentWithData.mapAwareness().getValidExpansionLocations().get()) {
            if (tryBuildStructure(agentWithData,
                    Abilities.BUILD_COMMAND_CENTER,
                    Units.TERRAN_COMMAND_CENTER,
                    Units.TERRAN_SCV,
                    1,
                    Optional.of(validExpansionLocation.position().toPoint2d()), Optional.of(PlacementRules.expansion()))) {
                agentWithData.mapAwareness().onExpansionAttempted(validExpansionLocation, gameLoop);
                lastExpansionTime = gameLoop;
                System.out.println("Attempting to build command centre at " + validExpansionLocation);
                return true;
            }
        }
        return false;
    }

    private boolean needsSupplyDepot(AgentWithData agentWithData) {
        // If we are not supply capped, don't build a supply depot.
        int productionCapableBuildings = countUnitType(Constants.TERRAN_CC_TYPES_ARRAY);
        productionCapableBuildings += countUnitType(Units.TERRAN_BARRACKS, Units.TERRAN_FACTORY, Units.TERRAN_STARPORT);
        if (agentWithData.observation().getFoodUsed() <= agentWithData.observation().getFoodCap() - productionCapableBuildings) {
            return false;
        }
        return agentWithData.observation().getFoodCap() < 200;
    }

    private boolean tryBuildSupplyDepot(AgentWithData agentWithData) {
        if (!needsSupplyDepot(agentWithData)) {
            return false;
        }
        // Try and build a depot. Find a random TERRAN_SCV and give it the order.
        Optional<Point2d> position = Optional.empty();
        if (agentWithData.structurePlacementCalculator().isPresent()) {
            position = agentWithData.structurePlacementCalculator().get()
                    .getFirstSupplyDepotLocation();
            if (agentWithData.structurePlacementCalculator().get().getFirstSupplyDepot(agentWithData.observation()).isPresent()) {
                position = agentWithData.structurePlacementCalculator().get()
                        .getSecondSupplyDepotLocation();
            }
            if (agentWithData.structurePlacementCalculator().get().getSecondSupplyDepot(agentWithData.observation()).isPresent()) {
                position = Optional.empty();
            }
            if (position.isPresent() &&
                    !agentWithData.query().placement(Abilities.BUILD_SUPPLY_DEPOT, position.get())) {
                position = Optional.empty();
            }
        }
        long numCc = countUnitType(Constants.TERRAN_CC_TYPES_ARRAY);
        return tryBuildStructure(agentWithData, Abilities.BUILD_SUPPLY_DEPOT, Units.TERRAN_SUPPLY_DEPOT, Units.TERRAN_SCV,
                (int) Math.min(3, numCc), position, Optional.of(PlacementRules.borderOfBase()));
    }

    private boolean tryBuildScvs(AgentWithData agentWithData) {
        int numBases = countMiningBases(agentWithData);
        int numScvs = countUnitType(Units.TERRAN_SCV);
        if (agentWithData.observation().getFoodUsed() == agentWithData.observation().getFoodCap()) {
            return false;
        }
        int neededScvs = Math.min(75, numBases * 28);
        AtomicInteger deltaScvs = new AtomicInteger(Math.max(0, neededScvs - numScvs));
        agentWithData.observation().getUnits(Alliance.SELF,
                unitInPool -> Constants.TERRAN_CC_TYPES.contains(unitInPool.unit().getType())).forEach(commandCentre -> {
            if (commandCentre.unit().getBuildProgress() < 1.0f) {
                return;
            }
            if (commandCentre.unit().getOrders().isEmpty()) {
                if (deltaScvs.get() > 0) {
                    agentWithData.actions().unitCommand(commandCentre.unit(), Abilities.TRAIN_SCV, false);
                    deltaScvs.decrementAndGet();
                }
            }
        });
        return true;
    }
    private boolean tryBuildUnit(AgentWithData agentWithData,
                                 Ability abilityToCast, UnitType unitType, UnitType buildFrom,
                                 boolean needTechLab, Optional<Integer> maximum) {
        return tryBuildUnit(agentWithData, abilityToCast, new UnitType[]{unitType}, buildFrom, needTechLab, maximum);
    }

    private boolean tryBuildUnit(AgentWithData agentWithData,
                                 Ability abilityToCast, UnitType[] unitTypes, UnitType buildFrom,
                                 boolean needTechLab, Optional<Integer> maximum) {
        int count = countUnitType(unitTypes);
        if (maximum.isPresent()) {
            if (count >= maximum.get()) {
                return false;
            }
        }
        if (agentWithData.observation().getFoodUsed() == agentWithData.observation().getFoodCap()) {
            return false;
        }
        AtomicInteger amountNeeded = new AtomicInteger(maximum.orElse(1000) - count);
        agentWithData.observation()
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
                        UnitInPool addOnUnit = agentWithData.observation().getUnit(addOn);
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
                    boolean structureInHighThreat = agentWithData.mapAwareness().getRegionDataForPoint
                                    (structure.unit().getPosition().toPoint2d())
                            .map(regionData -> {
                                //System.out.println("Nearby threat " + regionData.nearbyEnemyThreat() + " vs " + regionData.playerThreat());
                                return regionData.nearbyEnemyThreat() > regionData.playerThreat();
                            })
                            .orElse(false);
                    if (orders.isEmpty() || (reactor && orders.size() < 2)) {
                        // Hack here - need prioritsation.
                        if (structureInHighThreat ||
                                (agentWithData.observation().getMinerals() > agentWithData.taskManager().totalReservedMinerals() &&
                                        agentWithData.observation().getVespene() > agentWithData.taskManager().totalReservedVespene())) {
                            agentWithData.actions().unitCommand(structure.unit(), abilityToCast, false);
                            amountNeeded.decrementAndGet();
                        }
                    }
                });
        return true;
    }

    private boolean needsRefinery(AgentWithData agentWithData) {
        return agentWithData.observation().getFoodWorkers() > 18 &&
                countUnitType(Units.TERRAN_REFINERY) < countUnitType(Constants.TERRAN_CC_TYPES_ARRAY) * (agentWithData.observation().getFoodUsed() > 100 ? 2 : 1);
    }

    private boolean tryBuildRefinery(AgentWithData agentWithData) {
        if (!needsRefinery(agentWithData)) {
            return false;
        }
        Optional<Unit> freeGeyserNearCc = BuildUtils.getBuildableGeyser(agentWithData.observation());
        freeGeyserNearCc.ifPresent(geyser -> tryBuildStructureAtTarget(
                agentWithData, Abilities.BUILD_REFINERY, Units.TERRAN_REFINERY, Units.TERRAN_SCV,
                    1, Optional.of(geyser)));
        return true;
    }

    void mineGas(AgentWithData agentWithData) {
        long gameLoop = agentWithData.observation().getGameLoop();

        if (gameLoop < lastGasCheck + GAS_CHECK_INTERVAL) {
            return;
        }
        lastGasCheck = gameLoop;
        int minMineralWorkersPerCc = MINIMUM_MINERAL_WORKERS_PER_CC;
        BuildUtils.reassignGasWorkers(agentWithData, minMineralWorkersPerCc, Integer.MAX_VALUE);
    }

    private void rebalanceWorkers(AgentWithData agentWithData) {
        long gameLoop = agentWithData.observation().getGameLoop();
        if (gameLoop < lastRebalanceAt + 1000L) {
            return;
        }
        lastRebalanceAt = gameLoop;
        BuildUtils.rebalanceWorkers(agentWithData);
    }

    private void tryGetUpgrades(AgentWithData agentWithData,
                                Set<Upgrade> upgrades, Units structure, Map<Upgrades, Abilities> upgradesToGet) {
        Set<Upgrades> upgradesMissing = new HashSet<>(upgradesToGet.keySet());
        upgradesMissing.removeAll(upgrades);
        if (upgradesMissing.size() == 0) {
            return;
        }

        agentWithData.observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                UnitInPool.isUnit(structure).test(unitInPool)).forEach(unit -> {
            if (unit.unit().getOrders().isEmpty()) {
                for (Map.Entry<Upgrades, Abilities> upgrade: upgradesToGet.entrySet()) {
                    if (!upgrades.contains(upgrade.getKey())) {
                        agentWithData.actions().unitCommand(unit.unit(), upgrade.getValue(), false);
                    }
                }
            }
        });
    }

    @Override
    public Supplier<BehaviourTask> getNextBehaviourTask() {
        return () -> { throw new IllegalStateException("The BaseTerranTask should never end."); };
    }
}
