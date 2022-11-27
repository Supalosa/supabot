package com.supalosa.bot.builds.terran;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.Expansion;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.builds.BuildOrder;
import com.supalosa.bot.builds.BuildOrderOutput;
import com.supalosa.bot.builds.ImmutableBuildOrderOutput;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.placement.PlacementRules;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.strategy.ProtossRoboticBayObservation;
import com.supalosa.bot.strategy.StrategicObservation;
import com.supalosa.bot.task.*;
import com.supalosa.bot.task.terran.BuildUtils;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.Validate;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The default Terran bio build order.
 * Although this build works to start a game from scratch, it's probably better to start from
 * a specialised build order task.
 */
public class TerranBioBuild implements BuildOrder {

    private static final long OUTPUT_UPDATE_INTERVAL = 15L;
    private long lastOutputUpdateAt = Long.MIN_VALUE;

    private Long lastExpansionTime = 0L;

    private Set<BuildOrderOutput> outputs = new LinkedHashSet<>();
    private HashMap<Ability, Integer> queuedAbilities = new HashMap<>();

    private LoadingCache<UnitType, Integer> countOfUnits = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.SECONDS)
            .build(new CacheLoader<>() {
                @Override
                public Integer load(UnitType key) {
                    System.err.println("Query for unit type " + key + " before any step started.");
                    return 0;
                }
            });

    public TerranBioBuild() {
    }

    @Override
    public void onStep(AgentWithData agentWithData) {
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
        long gameLoop = agentWithData.observation().getGameLoop();
        if (gameLoop > lastOutputUpdateAt + OUTPUT_UPDATE_INTERVAL) {
            lastOutputUpdateAt = gameLoop;
            outputs.clear();

            Set<Upgrade> upgrades = new HashSet<>(agentWithData.observation().getUpgrades());

            maybeSetGasMiners(agentWithData, Integer.MAX_VALUE);
            tryBuildSupplyDepot(agentWithData);
            morphCommandCentres(agentWithData);
            tryBuildScvs(agentWithData);
            tryBuildBarracks(agentWithData);
            tryBuildCommandCentre(agentWithData);
            tryBuildRefinery(agentWithData);
            buildAddons(agentWithData);
            tryBuildEngineeringBayAndUpgrades(agentWithData, upgrades);
            tryBuildFactoryAndUpgrades(agentWithData, upgrades);
            tryBuildStarportAndUpgrades(agentWithData, upgrades);
            tryBuildGhostAcademyAndUpgrades(agentWithData, upgrades);

            // Translate UnitTypeRequests to BuildOrderOutput.
            List<UnitTypeRequest> orderedUnitTypeRequests = sortUnitTypeRequests(agentWithData.fightManager().getRequestedUnitTypes());
            orderedUnitTypeRequests.forEach(unitTypeRequest -> {
                int count = unitTypeRequest.alternateForm()
                        .map(alternateUnitType -> countUnitType(unitTypeRequest.unitType(), alternateUnitType))
                        .orElseGet(() -> countUnitType(unitTypeRequest.unitType()));
                if (count < unitTypeRequest.amount()) {
                    int maxParallel = Math.max(0, unitTypeRequest.amount() - count);
                    maxParallel = Math.min(maxParallel, countUnitType(unitTypeRequest.producingUnitType()));
                    if (!unitTypeRequest.needsTechLab()) {
                        maxParallel *= 2; // can use reactor.
                    }
                    for (int i = 0; i < maxParallel; ++i) {
                        outputs.add(ImmutableBuildOrderOutput.builder()
                                .abilityToUse(unitTypeRequest.productionAbility())
                                .eligibleUnitTypes(UnitFilter.mine(unitTypeRequest.producingUnitType()))
                                .placementRules(unitTypeRequest.placementRules())
                                .addonRequired(unitTypeRequest.needsTechLab() ? Optional.of(Units.TERRAN_TECHLAB) : Optional.empty())
                                .outputId((int)gameLoop * 1000 + i)
                                .build());
                    }
                }
            });
        }

        // Allow attacks.
        agentWithData.fightManager().setCanAttack(true);

        BuildUtils.defaultTerranRamp(agentWithData);
    }

    private List<UnitTypeRequest> sortUnitTypeRequests(List<UnitTypeRequest> requestedUnitTypes) {
        // Sort unit type requests by lowest present amount.
        return requestedUnitTypes.stream().sorted(Comparator.comparingInt(request -> {
            int count = request.alternateForm()
                    .map(alternateUnitType -> countUnitType(request.unitType(), alternateUnitType))
                    .orElseGet(() -> countUnitType(request.unitType()));
            return count;
        })).collect(Collectors.toList());
    }

    private void tryBuildGhostAcademyAndUpgrades(AgentWithData agentWithData, Set<Upgrade> upgrades) {
        int supply = agentWithData.observation().getFoodUsed();
        boolean floatingLots = (agentWithData.observation().getMinerals() > 1250 && agentWithData.observation().getVespene() > 500);
        if (supply > 180 || floatingLots) {
            tryBuildMaxStructure(agentWithData,
                    Abilities.BUILD_GHOST_ACADEMY,
                    Units.TERRAN_GHOST_ACADEMY,
                    1,
                    1,
                    PlacementRules.borderOfBase());
            tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_GHOST_ACADEMY, Map.of(
                    Upgrades.ENHANCED_SHOCKWAVES, Abilities.RESEARCH_TERRAN_GHOST_ENHANCED_SHOCKWAVES
            ));
        }
    }

    private void tryBuildStarportAndUpgrades(AgentWithData agentWithData, Set<Upgrade> upgrades) {
        int armySupply = agentWithData.observation().getFoodArmy();
        boolean floatingLots = (agentWithData.observation().getMinerals() > 1250 && agentWithData.observation().getVespene() > 500);
        boolean needsLiberators = false;

        if (agentWithData.strategyTask().hasSeenObservation(ProtossRoboticBayObservation.class)) {
            needsLiberators = true;
        }

        if (armySupply > 20) {
            int numStarports = (floatingLots) ? 2 : 1;
            tryBuildMaxStructure(agentWithData,
                    Abilities.BUILD_STARPORT,
                    Units.TERRAN_STARPORT,
                    numStarports,
                    numStarports,
                    PlacementRules.centreOfBase());
        }

        if (armySupply > 40) {
            int numArmories = (floatingLots) ? 2 : 1;
            tryBuildMaxStructure(agentWithData,
                    Abilities.BUILD_ARMORY,
                    Units.TERRAN_ARMORY,
                    numArmories,
                    numArmories,
                    PlacementRules.borderOfBase());

            if (agentWithData.observation().getVespene() > 300) {
                if (needsLiberators || floatingLots) {
                    tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_ARMORY, Map.of(
                            Upgrades.TERRAN_VEHICLE_AND_SHIP_ARMORS_LEVEL1, Abilities.RESEARCH_TERRAN_VEHICLE_AND_SHIP_PLATING,
                            Upgrades.TERRAN_SHIP_WEAPONS_LEVEL1, Abilities.RESEARCH_TERRAN_SHIP_WEAPONS,
                            Upgrades.TERRAN_VEHICLE_AND_SHIP_ARMORS_LEVEL2, Abilities.RESEARCH_TERRAN_VEHICLE_AND_SHIP_PLATING,
                            Upgrades.TERRAN_SHIP_WEAPONS_LEVEL2, Abilities.RESEARCH_TERRAN_SHIP_WEAPONS,
                            Upgrades.TERRAN_VEHICLE_AND_SHIP_ARMORS_LEVEL3, Abilities.RESEARCH_TERRAN_VEHICLE_AND_SHIP_PLATING,
                            Upgrades.TERRAN_SHIP_WEAPONS_LEVEL3, Abilities.RESEARCH_TERRAN_SHIP_WEAPONS
                    ));
                    tryBuildMaxStructure(agentWithData,
                            Abilities.BUILD_FUSION_CORE,
                            Units.TERRAN_FUSION_CORE,
                            1,
                            1,
                            PlacementRules.borderOfBase());
                    tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_FUSION_CORE, Map.of(
                            Upgrades.LIBERATOR_AG_RANGE_UPGRADE, Abilities.RESEARCH_LIBERATOR_BALLISTIC_RANGE
                    ));
                } else {
                    tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_ARMORY, Map.of(
                            Upgrades.TERRAN_VEHICLE_AND_SHIP_ARMORS_LEVEL1, Abilities.RESEARCH_TERRAN_VEHICLE_AND_SHIP_PLATING,
                            Upgrades.TERRAN_VEHICLE_AND_SHIP_ARMORS_LEVEL2, Abilities.RESEARCH_TERRAN_VEHICLE_AND_SHIP_PLATING,
                            Upgrades.TERRAN_VEHICLE_AND_SHIP_ARMORS_LEVEL3, Abilities.RESEARCH_TERRAN_VEHICLE_AND_SHIP_PLATING
                    ));
                }
            }
        }
    }

    private void tryBuildFactoryAndUpgrades(AgentWithData agentWithData, Set<Upgrade> upgrades) {
        int supply = agentWithData.observation().getFoodUsed();
        int workerSupply = agentWithData.observation().getFoodWorkers();
        int armySupply = agentWithData.observation().getFoodArmy();

        if (workerSupply > 28 && armySupply > 1) {
            int targetFactories = supply > 160 ? 2 : 1;
            if (supply == 200) {
                targetFactories = 4;
            }
            tryBuildMaxStructure(agentWithData, Abilities.BUILD_FACTORY,
                    Units.TERRAN_FACTORY,
                    targetFactories,
                    1,
                    PlacementRules.centreOfBase());
            tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_FACTORY_TECHLAB, Map.of(
                    Upgrades.DRILL_CLAWS, Abilities.RESEARCH_DRILLING_CLAWS
            ));
        }
    }

    private void tryBuildEngineeringBayAndUpgrades(AgentWithData agentWithData, Set<Upgrade> upgrades) {
        int workerSupply = agentWithData.observation().getFoodWorkers();
        if (workerSupply > 40) {
            tryBuildMaxStructure(agentWithData,
                    Abilities.BUILD_ENGINEERING_BAY,
                    Units.TERRAN_ENGINEERING_BAY,2, 2, PlacementRules.borderOfBase());
        }
        tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_BARRACKS_TECHLAB, Map.of(
                Upgrades.SHIELD_WALL, Abilities.RESEARCH_COMBAT_SHIELD,
                Upgrades.STIMPACK, Abilities.RESEARCH_STIMPACK,
                Upgrades.PUNISHER_GRENADES, Abilities.RESEARCH_CONCUSSIVE_SHELLS
        ));
        tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_ENGINEERING_BAY, Map.of(
                Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL1, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS,
                Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL2, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS,
                Upgrades.TERRAN_INFANTRY_WEAPONS_LEVEL3, Abilities.RESEARCH_TERRAN_INFANTRY_WEAPONS,
                Upgrades.TERRAN_INFANTRY_ARMORS_LEVEL1, Abilities.RESEARCH_TERRAN_INFANTRY_ARMOR,
                Upgrades.TERRAN_INFANTRY_ARMORS_LEVEL2, Abilities.RESEARCH_TERRAN_INFANTRY_ARMOR,
                Upgrades.TERRAN_INFANTRY_ARMORS_LEVEL3, Abilities.RESEARCH_TERRAN_INFANTRY_ARMOR
        ));
        if (workerSupply > 60) {
            tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_ENGINEERING_BAY, Map.of(
                    Upgrades.TERRAN_BUILDING_ARMOR, Abilities.RESEARCH_TERRAN_STRUCTURE_ARMOR_UPGRADE
            ));
        }
    }

    private void buildAddons(AgentWithData agentWithData) {
        buildAddonsForType(agentWithData,
                Units.TERRAN_BARRACKS,
                Units.TERRAN_BARRACKS_TECHLAB,
                1,
                0.5);
        buildAddonsForType(agentWithData,
                Units.TERRAN_FACTORY,
                Units.TERRAN_FACTORY_TECHLAB,
                1,
                0.0);
        buildAddonsForType(agentWithData,
                Units.TERRAN_STARPORT,
                Units.TERRAN_STARPORT_TECHLAB,
                0,
                0.0);
    }

    private void buildAddonsForType(AgentWithData agentWithData, UnitType type,
                                    UnitType techlabType, int minTechLabs, double techLabRatio) {
        int numOfType = countUnitType(type);
        int numTechLabs = countUnitType(techlabType);
        int targetTechLabs = Math.max(minTechLabs, (int)Math.ceil((double)numOfType * techLabRatio));
        if (numTechLabs < targetTechLabs) {
            outputs.add(ImmutableBuildOrderOutput
                    .builder()
                    .abilityToUse(Abilities.BUILD_TECHLAB)
                    .eligibleUnitTypes(UnitFilter.mine(type))
                    .outputId((int)agentWithData.observation().getGameLoop())
                    .build());
        } else {
            outputs.add(ImmutableBuildOrderOutput
                    .builder()
                    .abilityToUse(Abilities.BUILD_REACTOR)
                    .eligibleUnitTypes(UnitFilter.mine(type))
                    .outputId((int)agentWithData.observation().getGameLoop())
                    .build());
        }
    }

    private void morphCommandCentres(AgentWithData agentWithData) {
        int numCcs = countUnitType(Units.TERRAN_COMMAND_CENTER);
        if (countUnitType(Units.TERRAN_BARRACKS) > 0 && numCcs > 0) {
            List<Unit> currentCcs = agentWithData.observation().getUnits(UnitFilter.mine(Units.TERRAN_COMMAND_CENTER))
                    .stream()
                    .map(UnitInPool::unit)
                    .collect(Collectors.toList());
            int numOrbitals = countUnitType(Units.TERRAN_ORBITAL_COMMAND);
            currentCcs.forEach(cc -> {
                if (numOrbitals < 3) {
                    outputs.add(ImmutableBuildOrderOutput
                            .builder()
                            .abilityToUse(Abilities.MORPH_ORBITAL_COMMAND)
                            .specificUnit(cc.getTag())
                            .build());
                } else {
                    outputs.add(ImmutableBuildOrderOutput
                            .builder()
                            .abilityToUse(Abilities.MORPH_PLANETARY_FORTRESS)
                            .specificUnit(cc.getTag())
                            .build());
                }
            });
        }
    }

    private void tryBuildScvs(AgentWithData agentWithData) {
        List<Unit> idleCcs = agentWithData.observation().getUnits(UnitFilter.mine(Constants.TERRAN_CC_TYPES))
                .stream()
                .map(UnitInPool::unit)
                .filter(unit -> unit.getOrders().isEmpty())
                .collect(Collectors.toList());
        int maxWorkers = 28 * Math.min(3, countUnitType(Constants.TERRAN_CC_TYPES_ARRAY));
        int numWorkers = countUnitType(Units.TERRAN_SCV);
        AtomicInteger workerQuota = new AtomicInteger(Math.max(0, maxWorkers - numWorkers));
        idleCcs.forEach(cc -> {
            if (workerQuota.getAndDecrement() > 0) {
                outputs.add(ImmutableBuildOrderOutput
                        .builder()
                        .abilityToUse(Abilities.TRAIN_SCV)
                        .specificUnit(cc.getTag())
                        .build());
            }
        });
    }

    @Override
    public List<BuildOrderOutput> getOutput(AgentWithData data, Map<Ability, Integer> currentParallelAbilities) {
        return new ArrayList<>(outputs);
    }

    @Override
    public void onStageStarted(S2Agent agent, AgentData data, BuildOrderOutput stage) {
        long gameLoop = agent.observation().getGameLoop();
        if (stage.abilityToUse().equals(Optional.of(Abilities.BUILD_COMMAND_CENTER))) {
            Validate.isTrue(stage.placementRules().isPresent(),
                    "Expected placement rule to be present for expansion.");
            // This statement can be false if the expansion was triggered by another build order.
            if (stage.placementRules().get().at().isPresent()) {
                Point2d expansionPosition = stage.placementRules().get().at().get();
                Optional<Expansion> maybeExpansion = data.mapAwareness().getExpansionLocations().flatMap(expansions ->
                        expansions.stream().filter(exp -> exp.position().equals(expansionPosition)).findFirst());
                maybeExpansion.ifPresent(expansion -> {
                    data.mapAwareness().onExpansionAttempted(expansion, gameLoop);
                    lastExpansionTime = gameLoop;
                    System.out.println("Attempting to build command centre at " + expansion);
                });
            }
        }
        if (stage.abilityToUse().isPresent()) {
            queuedAbilities.merge(stage.abilityToUse().get(), 0, (a, b) -> a - 1);
        }
    }

    @Override
    public void onStageFailed(BuildOrderOutput stage, AgentWithData agentWithData) {
        onStageFinished(stage, agentWithData, false);
    }

    @Override
    public void onStageCompleted(BuildOrderOutput stage, AgentWithData agentWithData) {
        onStageFinished(stage, agentWithData, true);
    }

    public void onStageFinished(BuildOrderOutput stage, AgentWithData agentWithData, boolean success) {
        outputs.remove(stage);
    }

    @Override
    public boolean isComplete() {
        return false;
    }

    @Override
    public boolean isTimedOut() {
        return false;
    }

    @Override
    public String getDebugText() {
        return "Terran Bio";
    }

    @Override
    public List<String> getVerboseDebugText() {
        return Collections.emptyList();
    }

    private boolean tryBuildMaxStructure(AgentWithData agentWithData,
                                         Ability abilityTypeForStructure,
                                         UnitType unitTypeForStructure,
                                         int max, int maxParallel, PlacementRules rules) {
        int completeCount = agentWithData.observation().getUnits(UnitFilter.mine(unitTypeForStructure)).size();
        if (completeCount < max) {
            // Check in-progress tasks. We nest it this way to reduce unnecessary iteration over the task list.
            long taskCount = agentWithData.taskManager().countTasks(task ->
                    task instanceof BuildStructureTask && ((BuildStructureTask)task).getTargetUnitType().equals(unitTypeForStructure));
            if (completeCount + taskCount < max) {
                tryBuildStructure(agentWithData.observation().getGameLoop(), abilityTypeForStructure, maxParallel, rules);
            }
        }
        return false;
    }

    private void tryBuildStructureOnTarget(AgentWithData agentWithData,
                                           Ability abilityTypeForStructure,
                                           Unit specificTarget) {
        tryBuildStructure(agentWithData.observation().getGameLoop(), abilityTypeForStructure, 1, PlacementRules.on(specificTarget));
    }

    private void tryBuildStructure(long gameLoop, Ability abilityTypeForStructure,
                                   int maxParallel, PlacementRules rules) {
        for (int i = 0; i < maxParallel; ++i) {
            outputs.add(ImmutableBuildOrderOutput.builder()
                    .abilityToUse(abilityTypeForStructure)
                    .eligibleUnitTypes(UnitFilter.mine(Units.TERRAN_SCV))
                    .placementRules(rules)
                    .outputId((int) gameLoop * 1000 + i)
                    .build());
        }
    }

    private void tryBuildBarracks(AgentWithData agentWithData) {
        if (countUnitType(Units.TERRAN_SUPPLY_DEPOT, Units.TERRAN_SUPPLY_DEPOT_LOWERED) < 1) {
            return;
        }
        if (needsSupplyDepot(agentWithData) && agentWithData.observation().getMinerals() < 150) {
            return;
        }
        if (needsCommandCentre(agentWithData)) {
            return;
        }

        int numBarracks = countUnitType(Units.TERRAN_BARRACKS);
        int numCc = countMiningBases(agentWithData);
        int minerals = agentWithData.observation().getMinerals();
        int barracksPerCc;
        if (minerals < 200) {
            barracksPerCc = 1;
        } else if (minerals < 500) {
            barracksPerCc = 2;
        } else {
            barracksPerCc = 3;
        }
        if (numBarracks > numCc * barracksPerCc) {
            return;
        }
        int targetNumBarracks = barracksPerCc * numCc;
        if (numBarracks == 0 && agentWithData.structurePlacementCalculator().isPresent()) {
            tryBuildMaxStructure(agentWithData, Abilities.BUILD_BARRACKS, Units.TERRAN_BARRACKS, targetNumBarracks, numCc, PlacementRules.mainRampBarracksWithAddon());
        } else {
            tryBuildMaxStructure(agentWithData, Abilities.BUILD_BARRACKS, Units.TERRAN_BARRACKS, targetNumBarracks, numCc, PlacementRules.centreOfBase());
        }
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
        final int[] expansionNumWorkers = new int[]{0, 18, 36, 54, 72, 999};
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

    private void tryBuildCommandCentre(AgentWithData agentWithData) {
        if (!needsCommandCentre(agentWithData)) {
            return;
        }
        if (agentWithData.mapAwareness().getValidExpansionLocations().isEmpty()) {
            agentWithData.actions().sendChat("Valid Expansions missing or empty", ActionChat.Channel.TEAM);
            return;
        }
        Optional<Expansion> firstExpansion = agentWithData.mapAwareness()
                .getValidExpansionLocations()
                .flatMap(expansions -> expansions.stream().findFirst());
        firstExpansion.ifPresent(expansion -> {
            tryBuildStructure(
                    agentWithData.observation().getGameLoop(), Abilities.BUILD_COMMAND_CENTER,
                    1, PlacementRules.at(expansion.position(), 0));
        });
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

    private void tryBuildSupplyDepot(AgentWithData agentWithData) {
        if (!needsSupplyDepot(agentWithData)) {
            return;
        }
        long numCc = countUnitType(Constants.TERRAN_CC_TYPES_ARRAY);
        long numSupplyDepot = countUnitType(Units.TERRAN_SUPPLY_DEPOT, Units.TERRAN_SUPPLY_DEPOT_LOWERED);
        boolean controlsBase = agentWithData.mapAwareness().getMainBaseRegion().map(RegionData::isPlayerControlled).orElse(false);
        if (numSupplyDepot == 0 && controlsBase) {
            tryBuildStructure(agentWithData.observation().getGameLoop(),
                    Abilities.BUILD_SUPPLY_DEPOT,
                    (int)numCc,
                    PlacementRules.mainRampSupplyDepot1());
        } else if (numSupplyDepot == 1 && controlsBase) {
            tryBuildStructure(agentWithData.observation().getGameLoop(),
                    Abilities.BUILD_SUPPLY_DEPOT,
                    (int)numCc,
                    PlacementRules.mainRampSupplyDepot2());
        } else {
            tryBuildStructure(agentWithData.observation().getGameLoop(),
                    Abilities.BUILD_SUPPLY_DEPOT,
                    (int)numCc,
                    PlacementRules.borderOfBase());
        }
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
        freeGeyserNearCc.ifPresent(geyser -> tryBuildStructureOnTarget(agentWithData,
                Abilities.BUILD_REFINERY, geyser));
        return true;
    }

    private void tryGetUpgrades(AgentWithData agentWithData,
                                Set<Upgrade> upgrades, UnitType structure, Map<Upgrades, Abilities> upgradesToGet) {
        Set<Upgrades> upgradesMissing = new HashSet<>(upgradesToGet.keySet());
        upgradesMissing.removeAll(upgrades);
        if (upgradesMissing.size() == 0) {
            return;
        }

        for (Map.Entry<Upgrades, Abilities> upgrade: upgradesToGet.entrySet()) {
            boolean isAvailable = agentWithData.observation().getUnits(UnitFilter.mine(structure)).stream()
                    .filter(unitInPool -> agentWithData.gameData().unitHasAbility(unitInPool.getTag(), upgrade.getValue()))
                    .count() > 0;
            if (isAvailable && !upgrades.contains(upgrade.getKey())) {
                outputs.add(ImmutableBuildOrderOutput.builder()
                        .eligibleUnitTypes(UnitFilter.mine(structure))
                        .abilityToUse(upgrade.getValue())
                        .outputId((int)agentWithData.observation().getGameLoop())
                        .build());
            }
        }
    }

    private void maybeSetGasMiners(AgentWithData agentWithData, int amount) {
        List<UnitInPool> refineries = agentWithData.observation().getUnits(UnitFilter.mine(Units.TERRAN_REFINERY));
        int currentGasMiners = refineries.stream().map(UnitInPool::unit)
                .map(Unit::getAssignedHarvesters)
                .map(optionalInt -> optionalInt.orElse(0))
                .reduce(0, Integer::sum);
        if (currentGasMiners != amount) {
            outputs.add(ImmutableBuildOrderOutput.builder().setGasMiners(amount).build());
        }
    }
}
