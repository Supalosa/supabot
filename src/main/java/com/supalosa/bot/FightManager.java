package com.supalosa.bot;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.*;
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
import java.util.List;
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

    private Map<Point2d, Long> lastAttackedLocation = new HashMap<>();
    private static final long FORGET_LAST_ATTACKED_LOCATION_AFTER = 22L * 30;
    private static final double LAST_ATTACKED_LOCATION_CLUSTER_DISTANCE = 10.0;

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


    public void onStep(TaskManager taskManager, AgentWithData agentWithData) {
        // hack, should be an ongamestart function
        if (!addedTasks) {
            addedTasks = true;
            taskManager.addTask(attackingArmy, 1);
            taskManager.addTask(reserveArmy, 1);

            // Stops the attacking army from taking units.
            attackingArmy.setAcceptingUnits(false);
            // Make the reserve army delegate production decisions to the attacking army.
            reserveArmy.setProductionDelegateArmy(attackingArmy);
        }

        onStepTerranBio(taskManager, agentWithData);

        // Remove complete tasks.
        List<ArmyTask> validArmyTasks = armyTasks.stream().filter(armyTask -> !armyTask.isComplete()).collect(Collectors.toList());
        armyTasks.clear();
        armyTasks.addAll(validArmyTasks);


        final long gameLoop = agent.observation().getGameLoop();

        // Forget last-attacked-locations after FORGET_LAST_ATTACKED_LOCATION_AFTER loops.
        Set<Point2d> lastAttackedLocationsToRemove = lastAttackedLocation.entrySet().stream()
                .filter(entry -> gameLoop > entry.getValue() + FORGET_LAST_ATTACKED_LOCATION_AFTER).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        lastAttackedLocationsToRemove.forEach(lastAttackedLocation::remove);

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
    private void updateTargetingLogic(AgentWithData data) {
        Optional<Point2d> nearestEnemy = data.mapAwareness().getMaybeEnemyPositionNearOwnBase();
        Army virtualAttackingArmy = Army.toVirtualArmy(nearestEnemy.map(enemyPosition ->
                data.enemyAwareness().getMaybeEnemyArmies(enemyPosition, 20f)).orElse(Collections.emptyList()));

        Optional<Point2d> defenceRetreatPosition = Optional.empty();
        Optional<Point2d> defenceAttackPosition = Optional.empty();
        Optional<Point2d> attackPosition = Optional.empty();
        Optional<Point2d> harassPosition = Optional.empty();

        long gameLoop = data.observation().getGameLoop();

        boolean defenceNeedsAssistance = false;

        if (nearestEnemy.isPresent() && data.mapAwareness().shouldDefendLocation(nearestEnemy.get())) {
            // Enemy detected near our base, attack them.
            defenceRetreatPosition = nearestEnemy;
            onLocationAttacked(nearestEnemy.get(), gameLoop);
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
            // If there's nothing to defend or we think we're gonna lose, fall back to the top of the ramp.
            defenceRetreatPosition = data.structurePlacementCalculator().flatMap(spc -> {
                // Defend from behind the barracks, or else the position of the barracks.
                return spc.getMainRamp()
                        .map(ramp -> ramp.projection(5.0f))
                        .orElse(spc.getFirstBarracksWithAddonLocation());
            });
        }

        // Set the defensive army to attack the most recently attacked location (might wanna revisit this).
        Optional<Point2d> mostRecentAttackedLocation = lastAttackedLocation.entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue)).map(Map.Entry::getKey);
        if (mostRecentAttackedLocation.isPresent()) {
            // Defend where we could place a 1x1 structure. Stops us from defending on top of a structure. Hack?
            defenceAttackPosition = data.structurePlacementCalculator().flatMap(spc ->
                spc.suggestLocationForFreePlacement(data, mostRecentAttackedLocation.get(), 1, 1, Optional.empty())
            );
        }

        if (attackPosition.isEmpty()) {
            // Don't know where the enemy army is - attack what is near us, or the enemy base
            Optional<Point2d> searchOrigin = attackingArmy
                    .getTargetPosition()
                    .or(() -> data.mapAwareness().getMaybeEnemyPositionNearEnemyBase())
                    .or(() -> data.mapAwareness().getMaybeEnemyPositionNearOwnBase())
                    .or(() -> data.mapAwareness().getNextScoutTarget());
            if (searchOrigin.isPresent()) {
                attackPosition = data.mapAwareness().findEnemyPositionNearPoint(agent.observation(), searchOrigin.get());
            } else {
                System.err.println("No target or target area found to attack - how did we get here?");
            }

            // Moderate the attack with checking if we could win a fight or not.
            if (data.enemyAwareness().getPotentialEnemyArmy().isPresent() &&
                    attackingArmy.predictFightAgainst(data.enemyAwareness().getPotentialEnemyArmy().get()) == FightPerformance.BADLY_LOSING) {
                if (attackPosition.isPresent()) {
                    //System.err.println("Aborted aggressive attack because we think we will badly lose.");
                }
                attackPosition = Optional.empty();
            }
        }
        // Harass the base with the least diffuse threat, as long as the diffuse threat is less half our attacking
        // army's power.
        double minDiffuseThreat = attackingArmy.getPower() / 2.0;
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

    // Called when a position (which we are trying to defend) is attacked.
    private void onLocationAttacked(Point2d position, long gameLoop) {
        Set<Point2d> existingPositions = lastAttackedLocation.keySet();
        Optional<Point2d> existingCluster = existingPositions.stream().filter(existingPosition ->
                existingPosition.distance(position) < LAST_ATTACKED_LOCATION_CLUSTER_DISTANCE).findFirst();

        existingCluster.ifPresentOrElse(existingPosition -> {
            lastAttackedLocation.remove(existingPosition);
            Point2d newLocation = existingPosition.add(position).div(2f);
            lastAttackedLocation.put(newLocation, gameLoop);
        }, () -> {
            lastAttackedLocation.put(position, gameLoop);
        });
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
        long gameLoop = agent.observation().getGameLoop();
        lastAttackedLocation.forEach((point2d, attackedAt) -> {
            float height = agent.observation().terrainHeight(point2d);
            Point point = Point.of(point2d.getX(), point2d.getY(), height);
            float radius = 5f * ((attackedAt + FORGET_LAST_ATTACKED_LOCATION_AFTER) - gameLoop) / (float)FORGET_LAST_ATTACKED_LOCATION_AFTER;
            agent.debug().debugSphereOut(point, Math.max(0.1f, radius), Color.PURPLE);
        });
    }

    public List<UnitTypeRequest> getRequestedUnitTypes() {
        // Compute what units the attacking army wants.
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
