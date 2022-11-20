package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.github.ocraft.s2client.protocol.unit.UnitOrder;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.Expansion;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.placement.PlacementRules;
import com.supalosa.bot.task.*;
import com.supalosa.bot.task.terran.BuildUtils;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.Validate;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The default Terran bio build order.
 * Although this build works to start a game from scratch, it's probably better to start from
 * a specialised build order task.
 */
public class BaseTerranTask implements BuildOrder {

    // expected amount before we start pulling workers from gas back to minerals.
    public static final int MINIMUM_MINERAL_WORKERS_PER_CC = 14;

    private Long lastExpansionTime = 0L;
    private int maxGasMiners = 0;

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
        tryBuildSupplyDepot(agentWithData);
        tryBuildBarracks(agentWithData);
        tryBuildCommandCentre(agentWithData);
        tryBuildRefinery(agentWithData);
        int supply = agentWithData.observation().getFoodUsed();
        int workerSupply = agentWithData.observation().getFoodWorkers();
        int armySupply = agentWithData.observation().getFoodArmy();
        boolean floatingLots = (agentWithData.observation().getMinerals() > 1250 && agentWithData.observation().getVespene() > 500);
        Set<Upgrade> upgrades = new HashSet<>(agentWithData.observation().getUpgrades());

        maxGasMiners = Integer.MAX_VALUE;

        if (workerSupply > 28 && armySupply > 1) {
            int targetFactories = supply > 160 ? 2 : 1;
            if (supply == 200) {
                targetFactories = 4;
            }
            tryBuildMaxStructure(agentWithData, Abilities.BUILD_FACTORY,
                    Units.TERRAN_FACTORY,
                    targetFactories,
                    PlacementRules.centreOfBase());
            agentWithData.observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    unitInPool.unit().getAddOnTag().isEmpty() &&
                    UnitInPool.isUnit(Units.TERRAN_FACTORY).test(unitInPool)).forEach(unit -> {
                if (unit.unit().getOrders().isEmpty() && canFitAddon(agentWithData, unit.unit())) {
                    agentWithData.actions().unitCommand(unit.unit(),
                            countUnitType(Units.TERRAN_FACTORY_TECHLAB) == 0 ?
                                    Abilities.BUILD_TECHLAB_FACTORY :
                                    Abilities.BUILD_REACTOR_FACTORY, unit.unit().getPosition().toPoint2d(),
                            false);
                } else {
                    agentWithData.debug().debugTextOut("No Addon", unit.unit().getPosition(), Color.RED, 10);
                }
            });
            tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_FACTORY_TECHLAB, Map.of(
                    Upgrades.DRILL_CLAWS, Abilities.RESEARCH_DRILLING_CLAWS
            ));
        }
        if (workerSupply > 40) {
            tryBuildMaxStructure(agentWithData,
                    Abilities.BUILD_ENGINEERING_BAY,
                    Units.TERRAN_ENGINEERING_BAY,2, PlacementRules.borderOfBase());
        }
        if (armySupply > 40) {
            boolean hasAir = countUnitType(Units.TERRAN_VIKING_FIGHTER, Units.TERRAN_LIBERATOR, Units.TERRAN_LIBERATOR_AG) > 0;
            int numArmories = (floatingLots || hasAir) ? 2 : 1;
            tryBuildMaxStructure(agentWithData,
                    Abilities.BUILD_ARMORY,
                    Units.TERRAN_ARMORY,
                    numArmories,
                    PlacementRules.borderOfBase());

            if (agentWithData.observation().getVespene() > 300) {
                if (hasAir || floatingLots) {
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
        if (supply > 180 || floatingLots) {
            tryBuildMaxStructure(agentWithData,
                    Abilities.BUILD_GHOST_ACADEMY,
                    Units.TERRAN_GHOST_ACADEMY,
                    1,
                    PlacementRules.borderOfBase());
            tryGetUpgrades(agentWithData, upgrades, Units.TERRAN_GHOST_ACADEMY, Map.of(
                    Upgrades.ENHANCED_SHOCKWAVES, Abilities.RESEARCH_TERRAN_GHOST_ENHANCED_SHOCKWAVES
            ));
        }
        if (workerSupply > 28 && armySupply > 1) {
            int targetStarports = supply > 160 ? 2 : 1;
            if (supply == 200) {
                targetStarports = 3;
            }
            tryBuildMaxStructure(agentWithData, Abilities.BUILD_STARPORT, Units.TERRAN_STARPORT,
                    targetStarports,
                    PlacementRules.borderOfBase());
            int numReactors = countUnitType(Units.TERRAN_STARPORT_REACTOR);
            int numTechLabs = countUnitType(Units.TERRAN_STARPORT_TECHLAB);
            agentWithData.observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    unitInPool.unit().getAddOnTag().isEmpty() &&
                    UnitInPool.isUnit(Units.TERRAN_STARPORT).test(unitInPool)).forEach(unit -> {
                if (unit.unit().getOrders().isEmpty() && canFitAddon(agentWithData, unit.unit())) {
                    // Reactor, Techlab, Reactor
                    agentWithData.actions().unitCommand(unit.unit(), numReactors == 0 ?
                                    Abilities.BUILD_REACTOR_STARPORT :
                                    numTechLabs == 0 ? Abilities.BUILD_TECHLAB_STARPORT : Abilities.BUILD_REACTOR_STARPORT,
                            unit.unit().getPosition().toPoint2d(),
                            false);
                } else {
                    agentWithData.debug().debugTextOut("Addon Blocked", unit.unit().getPosition(), Color.RED, 10);
                }
            });
        }
        if (workerSupply > 24 && armySupply > 10) {
            agentWithData.observation().getUnits(unitInPool -> unitInPool.unit().getAlliance() == Alliance.SELF &&
                    unitInPool.unit().getAddOnTag().isEmpty() &&
                    UnitInPool.isUnit(Units.TERRAN_BARRACKS).test(unitInPool)).forEach(unit -> {
                if (unit.unit().getOrders().isEmpty() && canFitAddon(agentWithData, unit.unit())) {
                    Ability ability = Abilities.BUILD_REACTOR;
                    if (countUnitType(Units.TERRAN_BARRACKS_TECHLAB) == 0 || ThreadLocalRandom.current().nextBoolean()) {
                        ability = Abilities.BUILD_TECHLAB;
                    }
                    agentWithData.actions().unitCommand(unit.unit(), ability, unit.unit().getPosition().toPoint2d(), false);
                } else {
                    agentWithData.debug().debugTextOut("No Addon", unit.unit().getPosition(), Color.RED, 10);
                }
            });
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
        }
        if (workerSupply > 60) {
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
            if (agentWithData.observation().getMinerals() >= maxMineralCost) {
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

        BuildUtils.defaultTerranRamp(agentWithData);

        agentWithData.fightManager().setCanAttack(true);
    }

    private boolean canFitAddon(AgentWithData agentWithData, Unit unit) {
        return agentWithData.structurePlacementCalculator().map(spc -> spc.canFitAddon(unit)).orElse(false);
    }

    @Override
    public List<BuildOrderOutput> getOutput(AgentWithData data) {
        return new ArrayList<>(outputs);
    }

    @Override
    public void onStageStarted(S2Agent agent, AgentData data, BuildOrderOutput stage) {
        long gameLoop = agent.observation().getGameLoop();
        if (stage.abilityToUse().equals(Optional.of(Abilities.BUILD_COMMAND_CENTER))) {
            Validate.isTrue(stage.placementRules().isPresent(),
                    "Expected placement rule to be present for expansion.");
            Validate.isTrue(stage.placementRules().get().at().isPresent(),
                    "Expected placement rule to be a specific location for an expansion.");
            Point2d expansionPosition = stage.placementRules().get().at().get();
            Optional<Expansion> maybeExpansion = data.mapAwareness().getExpansionLocations().flatMap(expansions ->
                    expansions.stream().filter(exp -> exp.position().equals(expansionPosition)).findFirst());
            maybeExpansion.ifPresent(expansion -> {
                data.mapAwareness().onExpansionAttempted(expansion, gameLoop);
                lastExpansionTime = gameLoop;
                System.out.println("Attempting to build command centre at " + expansion);
            });
        }
        outputs.remove(stage);
        if (stage.abilityToUse().isPresent()) {
            queuedAbilities.compute(stage.abilityToUse().get(), (k, v) -> v == null ? 1 : v - 1);
        }
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
    public int getMaximumGasMiners() {
        return maxGasMiners;
    }

    @Override
    public String getDebugText() {
        return "Terran Bio";
    }

    private int getNumAbilitiesInUse(ObservationInterface observationInterface, Ability abilityTypeForStructure) {
        // If a unit already is building a supply structure of this type, do nothing.
        return observationInterface.getUnits(Alliance.SELF, doesBuildWith(abilityTypeForStructure)).size();
    }

    private boolean tryBuildMaxStructure(AgentWithData agentWithData,
                                         Ability abilityTypeForStructure,
                                         UnitType unitTypeForStructure,
                                         int max, PlacementRules rules) {
        int completeCount = agentWithData.observation().getUnits(UnitFilter.mine(unitTypeForStructure)).size();
        if (completeCount < max) {
            // Check in-progress tasks. We nest it this way to reduce unnecessary iteration over the task list.
            long taskCount = agentWithData.taskManager().countTasks(task ->
                    task instanceof BuildStructureTask && ((BuildStructureTask)task).getTargetUnitType().equals(unitTypeForStructure));
            if (completeCount + taskCount < max) {
                tryBuildStructure(abilityTypeForStructure, rules);
            }
        }
        return false;
    }

    private void tryBuildStructureOnTarget(Ability abilityTypeForStructure,
                                           Unit specificTarget) {
        tryBuildStructure(abilityTypeForStructure, PlacementRules.on(specificTarget));
    }

    private void tryBuildStructure(Ability abilityTypeForStructure,
                                    PlacementRules rules) {
        // Temporary blocker
        if (getQueuedCount(abilityTypeForStructure) == 0) {
            outputs.add(ImmutableBuildOrderOutput.builder()
                    .abilityToUse(abilityTypeForStructure)
                    .eligibleUnitTypes(UnitFilter.mine(Units.TERRAN_SCV))
                    .placementRules(rules)
                    .originatingHashCode(ThreadLocalRandom.current().nextInt())
                    .build());
            queuedAbilities.compute(abilityTypeForStructure, (k, v) -> v == null ? 1 : v + 1);
        }
    }

    private Predicate<UnitInPool> doesBuildWith(Ability abilityTypeForStructure) {
        return unitInPool -> unitInPool.unit()
                .getOrders()
                .stream()
                .anyMatch(unitOrder -> abilityTypeForStructure.equals(unitOrder.getAbility()));
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
            return;
        }
        if (numBarracks == 0 && agentWithData.structurePlacementCalculator().isPresent()) {
            tryBuildStructure(Abilities.BUILD_BARRACKS, PlacementRules.mainRampBarracksWithAddon());
        } else {
            tryBuildStructure(Abilities.BUILD_BARRACKS, PlacementRules.centreOfBase());
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
                    Abilities.BUILD_COMMAND_CENTER,
                    PlacementRules.at(expansion.position(), 0));
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
            tryBuildStructure(
                    Abilities.BUILD_SUPPLY_DEPOT,
                    PlacementRules.mainRampSupplyDepot1());
        } else if (numSupplyDepot == 1 && controlsBase) {
            tryBuildStructure(
                Abilities.BUILD_SUPPLY_DEPOT,
                PlacementRules.mainRampSupplyDepot2());
        } else {
            tryBuildStructure(
                    Abilities.BUILD_SUPPLY_DEPOT,
                    PlacementRules.borderOfBase());
        }
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
        freeGeyserNearCc.ifPresent(geyser -> tryBuildStructureOnTarget(
                Abilities.BUILD_REFINERY, geyser));
        return true;
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
                    if (agentWithData.gameData().unitHasAbility(unit.getTag(), upgrade.getValue()) && !upgrades.contains(upgrade.getKey())) {
                        agentWithData.actions().unitCommand(unit.unit(), upgrade.getValue(), false);
                        break;
                    }
                }
            }
        });
    }

    private int getQueuedCount(Ability ability) {
        return queuedAbilities.getOrDefault(ability, 0);
    }
}
