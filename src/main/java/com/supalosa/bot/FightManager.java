package com.supalosa.bot;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
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
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.task.TaskWithUnits;
import com.supalosa.bot.task.army.ArmyTask;
import com.supalosa.bot.task.terran.OrbitalCommandManagerTask;
import com.supalosa.bot.task.army.TerranBioArmyTask;
import com.supalosa.bot.task.RepairTask;
import com.supalosa.bot.task.TaskManager;
import com.supalosa.bot.task.army.TerranBioHarrassArmyTask;
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
    private long lastDefenceCommand = 0L;

    private long positionalLogicUpdatedAt = 0L;

    public FightManager(S2Agent agent) {
        this.agent = agent;
        armyTasks = new ArrayList<>();
        armyTasks.add(reserveArmy);
        armyTasks.add(attackingArmy);
    }

    private void setAttackPosition(Optional<Point2d> attackPosition) {
        armyTasks.forEach(armyTask -> {
            if (armyTask != reserveArmy) {
                armyTask.setTargetPosition(attackPosition);
            };
        });
    }

    private void setDefencePosition(Optional<Point2d> defencePosition) {
        this.reserveArmy.setTargetPosition(defencePosition);
        // TODO try to remember why we did this for reserveArmy.
        armyTasks.forEach(armyTask -> {
                armyTask.setRetreatPosition(defencePosition);
        });
    }

    private boolean addedTasks = false;

    // these will be moved to a playstyle-specific class
    private void onStepTerranBio(TaskManager taskManager, AgentData data) {


        if (agent.observation().getArmyCount() > 60) {
            // Start a harrass force.
            ArmyTask harrassTask = new TerranBioHarrassArmyTask("Harrass", 100);
            if (taskManager.addTask(harrassTask, 1)) {
                harrassTask.setPathRules(MapAwareness.PathRules.AVOID_ENEMY_ARMY);
                armyTasks.add(harrassTask);
            }
        }
        List<UnitInPool> enemySiegeTanks = agent.observation().getUnits(
                UnitFilter.builder()
                        .alliance(Alliance.ENEMY)
                        .unitType(Units.TERRAN_SIEGE_TANK_SIEGED).build());
        if (!enemySiegeTanks.isEmpty()) {
            // Start a harrass force.
            TaskWithUnits siegeTankMuleBombTask = new OrbitalCommandManagerTask(100);
            if (taskManager.addTask(siegeTankMuleBombTask, 1)) {
            }
        }
    }


    public void onStep(TaskManager taskManager, AgentData data) {
        // hack, should be an ongamestart function
        if (!addedTasks) {
            addedTasks = true;
            taskManager.addTask(attackingArmy, 1);
            taskManager.addTask(reserveArmy, 1);
        }

        // hack, maybe?
        if ((reserveArmy.getSize()) >= getTargetMarines()) {
            attackingArmy.takeAllFrom(reserveArmy);
        }

        onStepTerranBio(taskManager, data);

        // Remove complete tasks.
        List<ArmyTask> validArmyTasks = armyTasks.stream().filter(armyTask -> !armyTask.isComplete()).collect(Collectors.toList());
        armyTasks.clear();
        armyTasks.addAll(validArmyTasks);

        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > positionalLogicUpdatedAt + 33L) {
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
    }

    /**
     * Make decisions on where to attack and defend.
     */
    private void updateTargetingLogic(AgentData data) {
        // Select attack position.
        if (data.mapAwareness().getLargestEnemyArmy().isEmpty()) {
            this.setAttackPosition(data.mapAwareness().getMaybeEnemyPositionNearEnemy());
        } else {
            // TODO: defend if my regions are under threat.
            this.setAttackPosition(data.mapAwareness().getMaybeEnemyPositionNearEnemy());
        }
        // Select defence position.
        Optional<Point2d> nearestEnemy = data.mapAwareness().getMaybeEnemyPositionNearBase();
        if (nearestEnemy.isPresent() && data.mapAwareness().shouldDefendLocation(nearestEnemy.get())) {
            // Enemy detected near our base, attack them.
            this.setDefencePosition(nearestEnemy);
        } else {
            data.structurePlacementCalculator().ifPresent(spc -> {
                // Defend from behind the barracks, or else the position of the barracks.
                Optional<Point2d> defencePosition = spc
                        .getMainRamp()
                        .map(ramp -> ramp.projection(5.0f))
                        .orElse(spc.getFirstBarracksLocation(agent.observation().getStartLocation().toPoint2d()));
                this.setDefencePosition(defencePosition);
            });
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
        Map<UnitType, UnitType> producingUnitType = new HashMap<>();
        Map<UnitType, Ability> productionAbility = new HashMap<>();
        armyTasks.forEach(armyTask -> {
            attackingArmy.requestingUnitTypes().forEach(unitTypeRequest -> {
                requestedAmount.put(unitTypeRequest.unitType(),
                        requestedAmount.getOrDefault(unitTypeRequest.unitType(), 0) + unitTypeRequest.amount());
                producingUnitType.put(unitTypeRequest.unitType(), unitTypeRequest.producingUnitType());
                productionAbility.put(unitTypeRequest.unitType(), unitTypeRequest.productionAbility());
            });
        });
        return requestedAmount.entrySet().stream()
                .map(entry -> ImmutableUnitTypeRequest.builder()
                        .unitType(entry.getKey())
                        .amount(entry.getValue())
                        .producingUnitType(producingUnitType.get(entry.getKey()))
                        .productionAbility(productionAbility.get(entry.getKey()))
                        .build())
                .collect(Collectors.toList());
    }

    public Collection<ArmyTask> getAllArmies() {
        return Collections.unmodifiableCollection(armyTasks);
    }
}
