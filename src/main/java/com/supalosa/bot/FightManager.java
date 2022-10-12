package com.supalosa.bot;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.*;
import com.supalosa.bot.task.BuildStructureTask;
import com.supalosa.bot.task.RepairTask;
import com.supalosa.bot.task.TaskManager;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class FightManager {

    private final S2Agent agent;

    private Set<Tag> attackingArmy = new HashSet<>();
    private final Set<Tag> reserveArmy = new HashSet<>();

    private Optional<Point2d> attackPosition = Optional.empty();
    private Optional<Point2d> defencePosition = Optional.empty();

    private final Map<Tag, Long> unitsRetreatingUntil = new HashMap<>();
    private final Map<Tag, Long> unitCannotRetreatUntil = new HashMap<>();
    private final Map<Tag, Float> rememberedUnitHealth = new HashMap<>();

    private long lastCloakOrBurrowedUpdate = 0L;
    private static final long CLOAK_OR_BURROW_UPDATE_INTERVAL = 44L;
    private HashSet<Tag> cloakedOrBurrowedUnits = new HashSet<>();
    private List<Point2d> cloakedOrBurrowedUnitClusters = new ArrayList<>();

    private boolean hasSeenCloakedOrBurrowedUnits = false;
    private long lastDefenceCommand = 0L;

    public FightManager(S2Agent agent) {
        this.agent = agent;
    }

    public void setAttackPosition(Optional<Point2d> attackPosition) {
        this.attackPosition = attackPosition;
    }

    public void setDefencePosition(Optional<Point2d> defencePosition) {
        this.defencePosition = defencePosition;
    }

    public void onStep(TaskManager taskManager, AgentData data) {
        attackingArmy = attackingArmy.stream().filter(tag -> {
                    UnitInPool unit = agent.observation().getUnit(tag);
                    return (unit != null && unit.isAlive());
                })
                .collect(Collectors.toSet());

        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > lastDefenceCommand + 22L && defencePosition.isPresent()) {
            defenceCommand();
            lastDefenceCommand = gameLoop;
        }

        Set<Tag> unitsRetreatingThisTick = new HashSet<>();
        agent.observation().getUnits(Alliance.SELF).stream().forEach(unit -> {
            float health = unit.unit().getHealth().orElse(0.0f);
            Tag tag = unit.getTag();

            if (rememberedUnitHealth.containsKey(tag) &&
                    unit.unit().getHealth().isPresent() &&
                    unit.unit().getHealth().get() < rememberedUnitHealth.get(tag)) {
                //System.out.println("Unit " + tag + " started retreating");
                float prevHealth = rememberedUnitHealth.get(tag);
                float currentHealth = unit.unit().getHealth().get();
                if (attackingArmy.contains(tag) && unitCannotRetreatUntil.getOrDefault(tag, 0L) < gameLoop) {
                    unitsRetreatingUntil.put(tag, gameLoop + 22); // approx 1 sec.
                    unitCannotRetreatUntil.put(tag, gameLoop + 224); // approx 10 seconds
                    unitsRetreatingThisTick.add(tag);
                }
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

        Set<Tag> unitsToRemoveFromRetreat = new HashSet<>();
        AtomicBoolean doAttack = new AtomicBoolean(false);
        unitsRetreatingUntil.forEach((tag, time) -> {
            UnitInPool unit = agent.observation().getUnit(tag);
            if (unit == null || !unit.isAlive()) {
                unitsToRemoveFromRetreat.add(tag);
                unitCannotRetreatUntil.remove(tag);
                return;
            }
            if (gameLoop > time) {
                unitsToRemoveFromRetreat.add(tag);
                //System.out.println("Unit " + tag + " stopped retreating");
                doAttack.set(true);
            }
        });
        unitsToRemoveFromRetreat.forEach(toRemove -> {
            unitsRetreatingUntil.remove(toRemove);
        });
        if (doAttack.get()) {
            attackCommand();
        }
        if (unitsRetreatingThisTick.size() > 0 && defencePosition.isPresent()) {
            agent.actions().unitCommand(unitsRetreatingThisTick, Abilities.MOVE, defencePosition.get(), false);
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

    private boolean createRepairTask(TaskManager taskManager, Unit unitToRepair) {
        RepairTask maybeTask = new RepairTask(unitToRepair.getTag());
        return taskManager.addTask(maybeTask, 1);
    }

    public List<Point2d> getCloakedOrBurrowedUnitClusters() {
        return this.cloakedOrBurrowedUnitClusters;
    }

    private void attackCommand() {
        Set<Tag> unitsToAttackWith = new HashSet<>(attackingArmy);
        unitsToAttackWith.removeAll(unitsRetreatingUntil.keySet());
        if (unitsToAttackWith.size() > 0) {
            attackPosition.ifPresentOrElse(point2d ->
                            agent.actions().unitCommand(unitsToAttackWith, Abilities.ATTACK_ATTACK, point2d, false),
                    () -> defencePosition.ifPresent(point2d -> agent.actions().unitCommand(unitsToAttackWith, Abilities.MOVE, point2d, false)));
        }
    }

    private void defenceCommand() {
        if (reserveArmy.size() > 0) {
            defencePosition.ifPresentOrElse(point2d ->
                            agent.actions().unitCommand(reserveArmy, Abilities.ATTACK_ATTACK, point2d, false),
                    () -> defencePosition.ifPresent(point2d -> agent.actions().unitCommand(reserveArmy, Abilities.MOVE, point2d, false)));
        }
    }

    public void addUnit(Unit unit) {
        reserveArmy.add(unit.getTag());
        if ((reserveArmy.size()) >= getTargetMarines()) {
            attackingArmy.addAll(reserveArmy);
            reserveArmy.clear();
            attackCommand();
        } else {
            if (defencePosition.isPresent()) {
                if (unit.getPosition().toPoint2d().distance(defencePosition.get()) > 2.5) {
                    agent.actions().unitCommand(unit,
                            Abilities.ATTACK_ATTACK, defencePosition.get(), false);
                }
            }
        }
    }

    // Get size (in unit count) of the reserve army before it should add it to the attacking army.
    public int getTargetMarines() {
        int attackingArmySize = attackingArmy.size();
        int myFoodCap = agent.observation().getFoodCap() - attackingArmySize;
        int result = Math.max(10, Math.min(attackingArmySize / 2, (int)(myFoodCap * 0.75)));
        return result;
    }

    public boolean hasSeenCloakedOrBurrowedUnits() {
        return hasSeenCloakedOrBurrowedUnits;
    }

    public void onUnitIdle(UnitInPool unit) {
        if (attackingArmy.contains(unit.getTag())) {
            attackPosition.ifPresent(point2d ->
                    agent.actions().unitCommand(unit.unit(), Abilities.ATTACK_ATTACK, point2d, false));
        } else if (agent.observation().getGameLoop() % 100 == 0) {
            if (defencePosition.isPresent()) {
                if (unit.unit().getPosition().toPoint2d().distance(defencePosition.get()) > 2.5) {
                    agent.actions().unitCommand(unit.unit(),
                            Abilities.ATTACK_ATTACK, defencePosition.get(), false);
                }
            }
        }
    }
}
