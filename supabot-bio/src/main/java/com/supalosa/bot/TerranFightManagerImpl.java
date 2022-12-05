package com.supalosa.bot;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.*;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.production.UnitRequester;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskWithUnits;
import com.supalosa.bot.task.army.*;
import com.supalosa.bot.task.RepairTask;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.mission.DefenceTask;
import com.supalosa.bot.task.mission.DummyAttackTask;
import org.immutables.value.Value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class TerranFightManagerImpl implements FightManager, ArmyTaskListener {

    private final S2Agent agent;
    private final List<ArmyTask> armyTasks;

    private ArmyTask attackingArmy;
    private ArmyTask reserveArmy;

    private final Map<Tag, Float> rememberedUnitHealth = new HashMap<>();

    private long lastCloakOrBurrowedUpdate = 0L;
    private static final long CLOAK_OR_BURROW_UPDATE_INTERVAL = 44L;
    private HashSet<Tag> cloakedOrBurrowedUnits = new HashSet<>();
    private List<Point2d> cloakedOrBurrowedUnitClusters = new ArrayList<>();

    private boolean hasSeenCloakedOrBurrowedUnits = false;

    private long positionalLogicUpdatedAt = 0L;
    private boolean canAttack = false;
    private boolean pendingReinforcement = false;

    private DummyAttackTask dummyAttackTask;
    private Map<Region, DefenceTask> defenceTasks = new HashMap<>();
    private Function<DefenceTask, ArmyTask> defenceArmySupplier = defenceTask ->
            new TerranBioDefenceArmyTask("Defence." + UUID.randomUUID(), defenceTask);
    private Map<Region, PendingDefenceTask> pendingDefenceTasks = new HashMap<>();

    private CompositionChooser compositionChooser;

    @Value.Immutable
    interface PendingDefenceTask {
        Region region();
        long extension();
        int defenceLevel();
    }

    private boolean addedTasks = false;

    /**
     * Constructs the TerranFightManagerImpl.
     *
     * @param agent Agent to make queries to (TO BE DEPRECATED).
     * @param armySupplier Function that, given a name, produces an army identified by that name.
     */
    public TerranFightManagerImpl(S2Agent agent,
                                  Function<String, ArmyTask> armySupplier,
                                  CompositionChooser compositionChooser) {
        this.agent = agent;
        armyTasks = new ArrayList<>();
        attackingArmy = armySupplier.apply("Attack");
        reserveArmy = armySupplier.apply("Reserve");
        armyTasks.add(attackingArmy);
        armyTasks.add(reserveArmy);
        this.compositionChooser = compositionChooser;
    }

    private void setHarassPosition(Optional<Point2d> harassPosition) {
        armyTasks.forEach(armyTask -> {
            if (armyTask instanceof TerranBioHarassArmyTask) {
                armyTask.setTargetPosition(harassPosition);
            };
        });
    }

    private void setDefencePosition(Optional<Point2d> defencePosition) {
        //this.reserveArmy.setTargetPosition(defencePosition);
        armyTasks.forEach(armyTask -> {
                armyTask.setRetreatPosition(defencePosition);
        });
    }

    // these will be moved to a playstyle-specific class
    private void onStepTerranBio(TaskManager taskManager, AgentData data) {
        if (agent.observation().getArmyCount() > 40) {
            // Start a harass force.
            DefaultArmyTask harassTask = new TerranBioHarassArmyTask("Harass1", 100, 50);
            if (taskManager.addTask(harassTask, 1)) {
                harassTask.setPathRules(MapAwareness.PathRules.AVOID_ENEMY_ARMY);
                harassTask.setAggressionLevel(DefaultArmyTask.AggressionLevel.FULL_RETREAT);
                armyTasks.add(harassTask);
            }
        }
    }

    @Override
    public void onStep(TaskManager taskManager, AgentWithData agentWithData) {
        // hack, should be an ongamestart function
        if (!addedTasks) {
            addedTasks = true;
            taskManager.addTask(attackingArmy, 1);
            taskManager.addTask(reserveArmy, 1);

            // Stops the attacking army from taking units.
            attackingArmy.addArmyListener(this);
        }
        // Make the reserve army delegate production decisions to the attacking army.
        reserveArmy.setUnitRequester(compositionChooser);
        if (dummyAttackTask == null) {
            Optional<RegionData> startPointRegion = agentWithData.mapAwareness()
                    .getRegionDataForPoint(agentWithData.observation().getStartLocation().toPoint2d());
            if (startPointRegion.isPresent()) {
                dummyAttackTask = new DummyAttackTask(
                        startPointRegion
                                .map(RegionData::region)
                                .orElseThrow(() -> new IllegalStateException("No region found for start location.")));
                taskManager.addTask(dummyAttackTask, 1);
            }
        }
        onStepTerranBio(taskManager, agentWithData);
        compositionChooser.onStep(agentWithData);

        // Remove complete tasks.
        List<ArmyTask> validArmyTasks = armyTasks.stream().filter(armyTask -> !armyTask.isComplete()).collect(Collectors.toList());
        armyTasks.clear();
        armyTasks.addAll(validArmyTasks);

        final long gameLoop = agent.observation().getGameLoop();

        // Remove defence tasks that are complete.
        Set<Region> regionDefenceTasksComplete = defenceTasks.entrySet().stream()
                .filter(entry -> entry.getValue().isComplete()).map(Map.Entry::getKey).collect(Collectors.toSet());
        regionDefenceTasksComplete.forEach(defenceTasks::remove);

        // Add queued defence tasks.
        if (pendingDefenceTasks.size() > 0) {
            pendingDefenceTasks.forEach((region, pendingDefenceTask) -> {
                createOrGetDefenceTask(taskManager, pendingDefenceTask.region()).ifPresent(task -> {
                    task.setMinimumLevel(pendingDefenceTask.defenceLevel());
                    task.setMinimumDuration(pendingDefenceTask.extension());
                });
            });
            pendingDefenceTasks.clear();
        }

        if (gameLoop > positionalLogicUpdatedAt + 22L) {
            positionalLogicUpdatedAt = gameLoop;
            updateTargetingLogic(agentWithData);
        }

        agent.observation().getUnits(Alliance.SELF).stream().forEach(unit -> {
            float health = unit.unit().getHealth().orElse(0.0f);
            Tag tag = unit.getTag();

            if (rememberedUnitHealth.containsKey(tag) &&
                    unit.unit().getHealth().isPresent() &&
                    unit.unit().getHealth().get() < rememberedUnitHealth.get(tag)) {
                float prevHealth = rememberedUnitHealth.get(tag);
                float currentHealth = unit.unit().getHealth().get();
                // TODO dispatch damage taken event to armies
                if (agentWithData.gameData().isStructure(unit.unit().getType())) {
                    createRepairTask(taskManager, unit.unit());
                }
            }
            rememberedUnitHealth.put(tag, health);
        });
        if (gameLoop > lastCloakOrBurrowedUpdate + CLOAK_OR_BURROW_UPDATE_INTERVAL) {
            updateCloakOrBurrowed();
            lastCloakOrBurrowedUpdate = gameLoop;
        }

        if (pendingReinforcement) {
            pendingReinforcement = false;
            canAttack = false;
            ArmyTask reinforcingArmy = attackingArmy.createChildArmy();
            if (taskManager.addTask(reinforcingArmy, 1)) {
                taskManager.reassignUnits(reserveArmy, reinforcingArmy, agentWithData.observation(), _unit -> true);
                armyTasks.add(reinforcingArmy);
            }
        }
    }

    /**
     * Make decisions on where to attack and defend.
     */
    private void updateTargetingLogic(AgentWithData agentWithData) {
        Optional<Point2d> nearestEnemy = agentWithData.mapAwareness().getMaybeEnemyPositionNearOwnBase();
        Army virtualAttackingArmy = Army.toVirtualArmy(nearestEnemy.map(enemyPosition ->
                agentWithData.enemyAwareness().getMaybeEnemyArmies(enemyPosition, 20f)).orElse(Collections.emptyList()));

        Optional<Point2d> defenceRetreatPosition = Optional.empty();
        Optional<Point2d> defenceAttackPosition = Optional.empty();
        Optional<Point2d> attackPosition = Optional.empty();
        Optional<Point2d> harassPosition;

        boolean defenceNeedsAssistance = false;

        if (nearestEnemy.isPresent() && agentWithData.mapAwareness().shouldDefendLocation(nearestEnemy.get())) {
            // Enemy detected near our base, attack them.
            defenceRetreatPosition = nearestEnemy;
            onLocationAttacked(nearestEnemy.get(), agentWithData);
        }

        if (virtualAttackingArmy.size() > 0) {
            FightPerformance predictedOutcome = reserveArmy.predictFightAgainst(virtualAttackingArmy);
            if (defenceRetreatPosition.isPresent() && predictedOutcome == FightPerformance.BADLY_LOSING) {
                // Defending army needs help.
                attackPosition = defenceRetreatPosition;
                defenceNeedsAssistance = true;
                // This stops the defending army from mindlessly running to fight.
                defenceRetreatPosition = Optional.empty();
            }
        }
        if (defenceRetreatPosition.isEmpty()) {
            // If there's nothing to defend, or we think we're gonna lose, fall back to the top of the ramp.
            defenceRetreatPosition = agentWithData.structurePlacementCalculator().flatMap(spc -> {
                // Defend from behind the barracks, or else the position of the barracks.
                Optional<Point2d> location = spc.getMainRamp()
                        .map(ramp -> ramp.projection(5.0f))
                        .orElse(spc.getFirstBarracksWithAddonLocation());
                return location;
            });
        }

        if (attackPosition.isEmpty()) {
            // Don't know where the enemy army is - attack what is near us, or the enemy base
            Optional<Point2d> searchOrigin = attackingArmy
                    .getCentreOfMass()
                    .or(attackingArmy::getTargetPosition)
                    .or(() -> agentWithData.mapAwareness().getMaybeEnemyPositionNearEnemyBase())
                    .or(() -> agentWithData.mapAwareness().getMaybeEnemyPositionNearOwnBase())
                    .or(() -> agentWithData.mapAwareness().getNextScoutTarget());
            if (searchOrigin.isPresent()) {
                attackPosition = agentWithData.mapAwareness().findEnemyPositionNearPoint(agent.observation(), searchOrigin.get());
            } else {
                System.err.println("No target or target area found to attack - how did we get here?");
            }

            // Moderate the attack with checking if we could win a fight or not. If not, stay home and expand our territory.
            // Also expand our territory if there's nothing to attack.
            FightPerformance predictedFightPerformance = attackingArmy.predictFightAgainst(agentWithData.enemyAwareness().getOverallEnemyArmy());
            boolean shouldCancelAttack = false;
            boolean shouldExploreMap = false;
            if (agentWithData.enemyAwareness().isMyEconomyStronger()) {
                // If we have a stronger economy, we're more willing to take stable fights.
                shouldCancelAttack = predictedFightPerformance != FightPerformance.WINNING && predictedFightPerformance != FightPerformance.STABLE;
                shouldExploreMap = predictedFightPerformance != FightPerformance.BADLY_LOSING;
            } else {
                shouldCancelAttack = predictedFightPerformance != FightPerformance.WINNING;
                shouldExploreMap = false;
            }
            if (shouldCancelAttack ||
                    attackPosition.isEmpty()) {
                if (shouldExploreMap) {
                    // In this case, try to find a non-controlled region next to ours.
                    Optional<RegionData> uncontrolledBorderRegion = findNeutralBorderRegion(agentWithData.mapAwareness());
                    attackPosition = uncontrolledBorderRegion
                            .map(RegionData::region)
                            .map(Region::centrePoint);
                } else {
                    // We shouldn't even explore, just camp at home.
                    attackPosition = Optional.empty();
                }
            }
        }
        // Harass the base with the least diffuse threat, as long as the diffuse threat is less half our attacking
        // army's power.
        double minDiffuseThreat = attackingArmy.getPower() / 4.0;
        RegionData minRegion = null;
        for (RegionData regionData : agentWithData.mapAwareness().getAllRegionData()) {
            if (attackPosition.isPresent()) {
                if (regionData.hasEnemyBase()) {
                    if (regionData.diffuseEnemyThreat() < minDiffuseThreat) {
                        minRegion = regionData;
                        minDiffuseThreat = regionData.diffuseEnemyThreat();
                    }
                }
            }
        }
        if (minRegion != null) {
            harassPosition = Optional.of(minRegion.region().centrePoint());
            // If no attack position, send the attacking army there.
            if (attackPosition.isEmpty()) {
                if (harassPosition.isPresent()) {
                    attackPosition = harassPosition;
                } else {
                    attackPosition = defenceRetreatPosition;
                }
            }
        } else {
            harassPosition = agentWithData.mapAwareness().getNextScoutTarget();
            if (attackPosition.isEmpty()) {
                attackPosition = defenceRetreatPosition;
            }
        }

        if (defenceRetreatPosition.isPresent()) {
            this.setDefencePosition(defenceRetreatPosition);
        }
        if (defenceAttackPosition.isPresent()) {
            this.reserveArmy.setTargetPosition(defenceAttackPosition);
        }
        if (attackPosition.isPresent()) {
            this.attackingArmy.setTargetPosition(attackPosition);
        }
        if (harassPosition.isPresent()) {
            this.setHarassPosition(harassPosition);
        }

        // periodically push units from the reserve army to the main army, as long as the defence army doesn't need
        // additional units.
        if (!defenceNeedsAssistance && (reserveArmy.getSize()) >= getTargetMarines() && canAttack) {
            reinforceAttackingArmy();
        }
    }

    /**
     * Returns a region that borders ours that we don't consider controlled (i.e. where RegionData::isPlayerControlled
     * is false but also not controlled by enemies.
     * The closest region by distance to the main base is chosen.
     */
    private Optional<RegionData> findNeutralBorderRegion(MapAwareness mapAwareness) {
        Set<Region> neutralRegions = mapAwareness.getAllRegionData().stream()
                .filter(data -> !data.isPlayerControlled() && !data.isEnemyControlled())
                .map(RegionData::region)
                .collect(Collectors.toSet());
        Optional<Region> mainBaseRegion = mapAwareness.getMainBaseRegion().map(RegionData::region);
        if (mainBaseRegion.isEmpty()) {
            return Optional.empty();
        }
        Optional<Region> target = mapAwareness.getPathingGraph(MapAwareness.PathRules.NORMAL).flatMap(graph ->
                graph.closestFirstSearch(mainBaseRegion.get(), region -> neutralRegions.contains(region)));
        return target.flatMap(region -> mapAwareness.getRegionDataForId(region.regionId()));
    }

    // Called when a position (which we are trying to defend) is attacked.
    private void onLocationAttacked(Point2d position, AgentWithData agentWithData) {
        Optional<RegionData> maybeRegionData = agentWithData.mapAwareness().getRegionDataForPoint(position);
        maybeRegionData.ifPresent(regionData -> {
            createOrGetDefenceTask(agentWithData.taskManager(), regionData.region()).ifPresent(task -> {
                task.onRegionAttacked();
            });
        });
    }

    private Optional<DefenceTask> createOrGetDefenceTask(TaskManager taskManager, Region region) {
        if (!defenceTasks.containsKey(region)) {
            DefenceTask newTask = new DefenceTask(region, 1, defenceArmySupplier);
            if (taskManager.addTask(newTask, 1)) {
                defenceTasks.put(region, newTask);
                return Optional.of(newTask);
            } else {
                return Optional.empty();
            }
        } else {
            return Optional.ofNullable(defenceTasks.get(region));
        }
    }

    private void updateCloakOrBurrowed() {
        List<UnitInPool> enemyUnits = agent.observation().getUnits(Alliance.ENEMY);
        this.cloakedOrBurrowedUnits = new HashSet<>();
        List<UnitInPool> cloakedOrBurrowedUips = new ArrayList<>();
        List<UnitInPool> changelings = new ArrayList<>();
        for (UnitInPool enemyUnit : enemyUnits) {
            if (enemyUnit.unit().getCloakState().isPresent() &&
                    enemyUnit.unit().getCloakState().get() == CloakState.CLOAKED) {
                this.hasSeenCloakedOrBurrowedUnits = true;
                cloakedOrBurrowedUnits.add(enemyUnit.getTag());
                cloakedOrBurrowedUips.add(enemyUnit);
            }
            if (enemyUnit.unit().getBurrowed().orElse(false)) {
                this.hasSeenCloakedOrBurrowedUnits = true;
                cloakedOrBurrowedUnits.add(enemyUnit.getTag());
                cloakedOrBurrowedUips.add(enemyUnit);
            }
            if (enemyUnit.unit().getType() == Units.ZERG_CHANGELING ||
                    enemyUnit.unit().getType() == Units.ZERG_CHANGELING_MARINE ||
                    enemyUnit.unit().getType() == Units.ZERG_CHANGELING_ZEALOT ||
                    enemyUnit.unit().getType() == Units.ZERG_CHANGELING_MARINE_SHIELD ||
                    enemyUnit.unit().getType() == Units.ZERG_CHANGELING_ZERGLING ||
                    enemyUnit.unit().getType() == Units.ZERG_CHANGELING_ZERGLING_WINGS) {
                changelings.add(enemyUnit);
            }
        }
        if (changelings.size() > 0) {
            // hack for now
            for (UnitInPool unit : agent.observation().getUnits(Alliance.SELF)) {
                if (unit.unit().getType() == Units.TERRAN_MARINE) {
                    for (UnitInPool changeling : changelings) {
                        double distance = changeling.unit().getPosition().distance(unit.unit().getPosition());
                        if (distance < 5) {
                            agent.actions().unitCommand(unit.unit(), Abilities.ATTACK, changeling.unit(), false);
                        }
                    }
                }
            }
        }
        this.cloakedOrBurrowedUnitClusters = new ArrayList<>();
        Map<Point, List<UnitInPool>> clusters = Expansions.cluster(cloakedOrBurrowedUips, 8f);
        this.cloakedOrBurrowedUnitClusters = clusters.keySet().stream().map(Point::toPoint2d).collect(Collectors.toList());
    }

    // Get size (in unit count) of the reserve army before it should add it to the attacking army.
    public int getTargetMarines() {
        int attackingArmySize = attackingArmy.getSize();
        int myFoodCap = agent.observation().getFoodCap() - attackingArmySize;
        int result = Math.max(10, Math.min(attackingArmySize / 4, (int)(myFoodCap * 0.25)));
        return result;
    }

    private boolean createRepairTask(TaskManager taskManager, Unit unitToRepair) {
        RepairTask maybeTask = new RepairTask(unitToRepair.getTag());
        return taskManager.addTask(maybeTask, 1);
    }

    @Override
    public List<Point2d> getCloakedOrBurrowedUnitClusters() {
        return this.cloakedOrBurrowedUnitClusters;
    }

    @Override
    public boolean hasSeenCloakedOrBurrowedUnits() {
        return hasSeenCloakedOrBurrowedUnits;
    }

    @Override
    public void onUnitIdle(UnitInPool unit) {
        armyTasks.forEach(armyTask -> {
            if (armyTask.hasUnit(unit.getTag())) {
                armyTask.onUnitIdle(unit);
            }
        });
    }

    @Override
    public void debug(S2Agent agent) {
    }

    @Override
    public List<UnitTypeRequest> getRequestedUnits() {
        // Compute what units the attacking army wants.
        Map<UnitType, Integer> requestedAmount = new HashMap<>();
        Map<UnitType, Optional<UnitType>> alternateForm = new HashMap<>();
        Map<UnitType, UnitType> producingUnitType = new HashMap<>();
        Map<UnitType, Ability> productionAbility = new HashMap<>();
        Map<UnitType, Boolean> needsTechLab = new HashMap<>();
        List<TaskWithUnits> tasksWithUnits = new ArrayList<>(armyTasks);
        tasksWithUnits.addAll(defenceTasks.values());
        List<UnitTypeRequest> allRequests = tasksWithUnits.stream()
                .map(UnitRequester::getRequestedUnits)
                .flatMap(List::stream).collect(Collectors.toList());
        allRequests.addAll(compositionChooser.getRequestedUnits());
        allRequests.forEach(unitTypeRequest -> {
            requestedAmount.put(unitTypeRequest.unitType(),
                    requestedAmount.getOrDefault(unitTypeRequest.unitType(), 0) + unitTypeRequest.amount());
            producingUnitType.put(unitTypeRequest.unitType(), unitTypeRequest.producingUnitType());
            productionAbility.put(unitTypeRequest.unitType(), unitTypeRequest.productionAbility());
            alternateForm.put(unitTypeRequest.unitType(), unitTypeRequest.alternateForm());
            needsTechLab.merge(unitTypeRequest.unitType(), unitTypeRequest.needsTechLab(), (a, b) -> a || b);
        });
        return requestedAmount.entrySet().stream()
                .map(entry -> ImmutableUnitTypeRequest.builder()
                        .unitType(entry.getKey())
                        .amount(entry.getValue())
                        .producingUnitType(producingUnitType.get(entry.getKey()))
                        .productionAbility(productionAbility.get(entry.getKey()))
                        .alternateForm(alternateForm.get(entry.getKey()))
                        .needsTechLab(needsTechLab.get(entry.getKey()))
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public Collection<ArmyTask> getAllArmies() {
        return Collections.unmodifiableCollection(armyTasks);
    }

    @Override
    public void setCanAttack(boolean canAttack) {
        this.canAttack = canAttack;
    }

    @Override
    public void reinforceAttackingArmy() {
        this.pendingReinforcement = true;
    }

    @Override
    public void defendRegionFor(Region region, int defenceLevel, long gameCycles) {
        pendingDefenceTasks.put(region, ImmutablePendingDefenceTask.builder()
                .region(region)
                .defenceLevel(defenceLevel)
                .extension(gameCycles)
                .build());
    }

    @Override
    public ArmyTask getMainArmy() {
        return attackingArmy;
    }

    @Override
    public void onEngagementStarted(ArmyTask task, Army enemyArmy, FightPerformance predictedResult) {
    }

    @Override
    public void onEngagementEnded(ArmyTask task, double powerLost, List<UnitType> unitsLost) {
    }
}
