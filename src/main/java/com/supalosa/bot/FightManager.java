package com.supalosa.bot;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.CloakState;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.analysis.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.task.army.*;
import com.supalosa.bot.task.RepairTask;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.utils.UnitFilter;

import java.util.*;
import java.util.stream.Collectors;

public class FightManager {

    private final S2Agent agent;
    private final List<ArmyTask> armyTasks;

    private TerranBioArmyTask attackingArmy = new TerranBioArmyTask("Attack", 1);
    private TerranBioArmyTask reserveArmy = new TerranBioArmyTask("Reserve", 10);

    private final Map<Tag, Float> rememberedUnitHealth = new HashMap<>();

    private long lastCloakOrBurrowedUpdate = 0L;
    private static final long CLOAK_OR_BURROW_UPDATE_INTERVAL = 44L;
    private HashSet<Tag> cloakedOrBurrowedUnits = new HashSet<>();
    private List<Point2d> cloakedOrBurrowedUnitClusters = new ArrayList<>();

    private boolean hasSeenCloakedOrBurrowedUnits = false;

    private long positionalLogicUpdatedAt = 0L;
    private boolean canAttack = false;
    private boolean pendingReinforcement = false;

    public FightManager(S2Agent agent) {
        this.agent = agent;
        armyTasks = new ArrayList<>();
        armyTasks.add(reserveArmy);
        armyTasks.add(attackingArmy);
    }

    private void setHarassPosition(Optional<Point2d> harassPosition) {
        armyTasks.forEach(armyTask -> {
            if (armyTask instanceof TerranBioHarassArmyTask) {
                armyTask.setTargetPosition(harassPosition);
            };
        });
    }

    private void setDefencePosition(Optional<Point2d> defencePosition) {
        this.reserveArmy.setTargetPosition(defencePosition);
        armyTasks.forEach(armyTask -> {
                armyTask.setRetreatPosition(defencePosition);
        });
    }

    private boolean addedTasks = false;

    // these will be moved to a playstyle-specific class
    private void onStepTerranBio(TaskManager taskManager, AgentData data) {
        if (agent.observation().getFoodUsed() < 40) {
            // Worker rush defence. Also used for crisis modes.
            Optional<RegionData> startRegionData = data.mapAwareness()
                    .getRegionDataForPoint(agent.observation().getStartLocation().toPoint2d());
            if (data.mapAwareness().shouldDefendLocation(agent.observation().getStartLocation().toPoint2d())) {
                List<UnitInPool> numNearbyWorkers = agent.observation().getUnits(
                        UnitFilter.builder()
                                .alliance(Alliance.ENEMY)
                                .unitTypes(Constants.WORKER_TYPES)
                                .inRangeOf(agent.observation().getStartLocation().toPoint2d())
                                .range(15f)
                                .build());
                if (numNearbyWorkers.size() > 8) {
                    DefaultArmyTask defenceTask = new TerranWorkerRushDefenceTask("WorkerDefence", 100);
                    if (taskManager.addTask(defenceTask, 1)) {
                        agent.actions().sendChat("Worker rush defence started.", ActionChat.Channel.BROADCAST);
                        defenceTask.setPathRules(MapAwareness.PathRules.NORMAL);
                        defenceTask.setAggressionLevel(DefaultArmyTask.AggressionLevel.FULL_AGGRESSION);
                        armyTasks.add(defenceTask);
                    }
                }
            }
        }
        if (agent.observation().getArmyCount() > 40) {
            // Start a harass force.
            DefaultArmyTask harassTask = new TerranBioHarassArmyTask("Harass1", 100, 50);
            if (taskManager.addTask(harassTask, 1)) {
                harassTask.setPathRules(MapAwareness.PathRules.AVOID_ENEMY_ARMY);
                harassTask.setAggressionLevel(DefaultArmyTask.AggressionLevel.FULL_RETREAT);
                armyTasks.add(harassTask);
            }
        }
        if (agent.observation().getArmyCount() > 70) {
            // Start a harass force.
            DefaultArmyTask harassTask = new TerranBioHarassArmyTask("Harass2", 100, 100);
            if (taskManager.addTask(harassTask, 1)) {
                harassTask.setPathRules(MapAwareness.PathRules.AVOID_ENEMY_ARMY);
                harassTask.setAggressionLevel(DefaultArmyTask.AggressionLevel.FULL_RETREAT);
                armyTasks.add(harassTask);
            }
        }
        /*if (data.mapAwareness().getValidExpansionLocations().isPresent() && agent.observation().getArmyCount() > 80) {
            // Start parking units around the map
            DefaultArmyTask mapTask = new TerranMapControlArmyTask("Parked", 100);
            if (taskManager.addTask(mapTask, data.mapAwareness().getValidExpansionLocations().get().size())) {
                mapTask.setPathRules(MapAwareness.PathRules.AVOID_ENEMY_ARMY);
                mapTask.setAggressionLevel(DefaultArmyTask.AggressionLevel.FULL_AGGRESSION);
                armyTasks.add(mapTask);
            }
        }*/
    }


    public void onStep(TaskManager taskManager, AgentData data) {
        // hack, should be an ongamestart function
        if (!addedTasks) {
            addedTasks = true;
            taskManager.addTask(attackingArmy, 1);
            taskManager.addTask(reserveArmy, 1);
        }

        onStepTerranBio(taskManager, data);

        // Remove complete tasks.
        List<ArmyTask> validArmyTasks = armyTasks.stream().filter(armyTask -> !armyTask.isComplete()).collect(Collectors.toList());
        armyTasks.clear();
        armyTasks.addAll(validArmyTasks);

        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > positionalLogicUpdatedAt + 22L) {
            positionalLogicUpdatedAt = gameLoop;
            updateTargetingLogic(data);
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
                if (data.gameData().isStructure(unit.unit().getType())) {
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
            DefaultArmyTask reinforcingArmy = attackingArmy.createChildArmy();
            if (taskManager.addTask(reinforcingArmy, 1)) {
                reinforcingArmy.takeAllFrom(taskManager, agent.observation(), reserveArmy);
                armyTasks.add(reinforcingArmy);
            }
        }
    }

    /**
     * Make decisions on where to attack and defend.
     */
    private void updateTargetingLogic(AgentData data) {
        Optional<Point2d> nearestEnemy = data.mapAwareness().getMaybeEnemyPositionNearOwnBase();
        Optional<Army> enemyArmyNearBase = nearestEnemy.flatMap(enemyPosition -> data.enemyAwareness().getMaybeEnemyArmy(enemyPosition));


        Optional<Point2d> defencePosition = Optional.empty();
        Optional<Point2d> attackPosition = Optional.empty();
        Optional<Point2d> harassPosition = Optional.empty();

        boolean defenceNeedsAssistance = false;

        if (nearestEnemy.isPresent() && data.mapAwareness().shouldDefendLocation(nearestEnemy.get())) {
            // Enemy detected near our base, attack them.
            defencePosition = nearestEnemy;
        } else {
            defencePosition = data.structurePlacementCalculator().flatMap(spc -> {
                // Defend from behind the barracks, or else the position of the barracks.
                return spc.getMainRamp()
                        .map(ramp -> ramp.projection(5.0f))
                        .orElse(spc.getFirstBarracksWithAddonLocation());
            });
        }

        Optional<Army> attackingEnemyArmy = data.enemyAwareness().getMaybeEnemyArmy(nearestEnemy.get());
        if (attackingEnemyArmy.isPresent()) {
            FightPerformance predictedOutcome = reserveArmy.predictFightAgainst(attackingEnemyArmy.get());
            if (predictedOutcome == FightPerformance.BADLY_LOSING) {
                // Defending army needs help.
                attackPosition = defencePosition;
                defenceNeedsAssistance = true;
            }
        }

        if (attackPosition.isEmpty()) {
            if (data.enemyAwareness().getPotentialEnemyArmy().isEmpty()) {
                // Don't know where the enemy army is - attack them.
                attackPosition = data.mapAwareness().getMaybeEnemyPositionNearEnemyBase();
            } else {
                // We think we can attack the enemy base - attack it.
                if (data.enemyAwareness().getPotentialEnemyArmy().isPresent() &&
                        attackingArmy.predictFightAgainst(data.enemyAwareness().getPotentialEnemyArmy().get()) == FightPerformance.WINNING) {
                    attackPosition = data.mapAwareness().getMaybeEnemyPositionNearEnemyBase();
                }
            }
        }
        // Harass bases which have half the enemy army threat diffused to them.
        double minDiffuseThreat = data.enemyAwareness().getPotentialEnemyArmy()
                .map(army -> army.threat() / 2.0)
                .orElse(20.0);
        RegionData minRegion = null;
        for (RegionData regionData : data.mapAwareness().getAllRegionData()) {
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
        } else {
            harassPosition = data.mapAwareness().getNextScoutTarget();
        }
        if (attackPosition.isEmpty()) {
            attackPosition = harassPosition;
        }

        if (defencePosition.isPresent()) {
            this.setDefencePosition(defencePosition);
        }
        if (attackPosition.isPresent()) {
            this.attackingArmy.setTargetPosition(attackPosition);
        }
        if (harassPosition.isPresent()) {
            this.setHarassPosition(harassPosition);
        }

        // periodically push units from the reserve army to the main army.
        if (!defenceNeedsAssistance && (reserveArmy.getSize()) >= getTargetMarines() && canAttack) {
            reinforceAttackingArmy();
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

    public List<Point2d> getCloakedOrBurrowedUnitClusters() {
        return this.cloakedOrBurrowedUnitClusters;
    }

    public boolean hasSeenCloakedOrBurrowedUnits() {
        return hasSeenCloakedOrBurrowedUnits;
    }

    public void onUnitIdle(UnitInPool unit) {
        armyTasks.forEach(armyTask -> {
            if (armyTask.hasUnit(unit.getTag())) {
                armyTask.onUnitIdle(unit);
            }
        });
    }

    public void debug(S2Agent agent) {
    }

    public List<UnitTypeRequest> getRequestedUnitTypes() {
        Map<UnitType, Integer> requestedAmount = new HashMap<>();
        Map<UnitType, Optional<UnitType>> alternateForm = new HashMap<>();
        Map<UnitType, UnitType> producingUnitType = new HashMap<>();
        Map<UnitType, Ability> productionAbility = new HashMap<>();
        armyTasks.forEach(armyTask -> {
            attackingArmy.requestingUnitTypes().forEach(unitTypeRequest -> {
                requestedAmount.put(unitTypeRequest.unitType(),
                        requestedAmount.getOrDefault(unitTypeRequest.unitType(), 0) + unitTypeRequest.amount());
                producingUnitType.put(unitTypeRequest.unitType(), unitTypeRequest.producingUnitType());
                productionAbility.put(unitTypeRequest.unitType(), unitTypeRequest.productionAbility());
                alternateForm.put(unitTypeRequest.unitType(), unitTypeRequest.alternateForm());
            });
        });
        return requestedAmount.entrySet().stream()
                .map(entry -> ImmutableUnitTypeRequest.builder()
                        .unitType(entry.getKey())
                        .amount(entry.getValue())
                        .producingUnitType(producingUnitType.get(entry.getKey()))
                        .productionAbility(productionAbility.get(entry.getKey()))
                        .alternateForm(alternateForm.get(entry.getKey()))
                        .build())
                .collect(Collectors.toList());
    }

    public Collection<ArmyTask> getAllArmies() {
        return Collections.unmodifiableCollection(armyTasks);
    }

    public void setCanAttack(boolean canAttack) {
        this.canAttack = canAttack;
    }

    public void reinforceAttackingArmy() {
        this.pendingReinforcement = true;
    }
}
