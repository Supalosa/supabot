package com.supalosa.bot;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.*;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.task.RepairTask;
import com.supalosa.bot.task.TaskManager;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    // distance between the two furthest units
    private float armyDispersion = 0f;

    private boolean hasSeenCloakedOrBurrowedUnits = false;
    private long lastDefenceCommand = 0L;
    private Optional<Point2d> centreOfMass = Optional.empty();

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
        MapAwareness mapAwareness = data.mapAwareness();
        AtomicBoolean doAttack = new AtomicBoolean(false);
        if ((reserveArmy.size()) >= getTargetMarines()) {
            attackingArmy.addAll(reserveArmy);
            reserveArmy.clear();
            doAttack.set(true);
        }
        List<Point2d> armyPositions = new ArrayList<>();
        attackingArmy = attackingArmy.stream().filter(tag -> {
                    UnitInPool unit = agent.observation().getUnit(tag);
                    if (unit != null) {
                        armyPositions.add(unit.unit().getPosition().toPoint2d());
                    }
                    return (unit != null && unit.isAlive());
                })
                .collect(Collectors.toSet());

        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > lastDefenceCommand + 22L && defencePosition.isPresent()) {
            doAttack.set(true);
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
            OptionalDouble averageX = armyPositions.stream().mapToDouble(point -> point.getX()).average();
            OptionalDouble averageY = armyPositions.stream().mapToDouble(point -> point.getY()).average();
            centreOfMass = Optional.empty();
            if (averageX.isPresent() && averageY.isPresent()) {
                centreOfMass = Optional.of(Point2d.of((float)averageX.getAsDouble(), (float)averageY.getAsDouble()));
            }
            attackCommand(agent.observation(), centreOfMass, mapAwareness.getMaybeEnemyArmy());
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

    private boolean isRegrouping = false;

    private void attackCommand(ObservationInterface observationInterface,
                               Optional<Point2d> centreOfMass,
                               Optional<Army> maybeEnemyArmy) {
        Set<Tag> unitsToAttackWith = new HashSet<>(attackingArmy);
        unitsToAttackWith.removeAll(unitsRetreatingUntil.keySet());
        boolean attackWithAll = false;
        if (unitsToAttackWith.size() > 0) {
            // TODO better detection of 'move-only' units.
            Set<Tag> unitsThatMustMove = observationInterface.getUnits(unitInPool ->
                unitsToAttackWith.contains(unitInPool.getTag()) && unitInPool.unit().getType() == Units.TERRAN_MEDIVAC
            ).stream().map(unitInPool -> unitInPool.getTag()).collect(Collectors.toSet());
            if (centreOfMass.isPresent()) {
                List<Unit> farUnits = new ArrayList<>();
                List<Unit> nearUnits = observationInterface.getUnits(unitInPool ->
                        unitsToAttackWith.contains(unitInPool.getTag())).stream()
                        .map(uip -> uip.unit())
                        .filter(unit -> {
                            if (unit.getPosition().toPoint2d().distance(centreOfMass.get()) < (isRegrouping ? 5f : 10f)) {
                                return true;
                            } else {
                                farUnits.add(unit);
                                return false;
                            }
                        })
                        .collect(Collectors.toList());
                if (nearUnits.size() > unitsToAttackWith.size() * 0.75) {
                    isRegrouping = false;
                } else if (nearUnits.size() < unitsToAttackWith.size() * 0.35) {
                    isRegrouping = true;
                }
                if (!isRegrouping) {
                    attackWithAll = true;
                } else {
                    // Units far away from the centre of mass should run there.
                    if (farUnits.size() > 0) {
                        // Bit of a hack but sometimes attacking and moving helps
                        Ability ability = ThreadLocalRandom.current().nextBoolean() ? Abilities.ATTACK : Abilities.MOVE;
                        centreOfMass.ifPresent(point2d ->
                                agent.actions().unitCommand(farUnits, ability, point2d, false));
                    }
                    // Units near the centre of mass can attack move.
                    if (nearUnits.size() > 0) {
                        attackPosition.ifPresent(point2d ->
                                agent.actions().unitCommand(nearUnits, Abilities.ATTACK, point2d, false));
                    }
                }
            } else {
                attackWithAll = true;
            }
            if (attackWithAll) {
                unitsToAttackWith.removeAll(unitsThatMustMove);
                if (unitsToAttackWith.size() > 0) {
                    attackPosition.ifPresentOrElse(point2d ->
                                    agent.actions().unitCommand(unitsToAttackWith, Abilities.ATTACK, point2d, false),
                            () -> defencePosition.ifPresent(point2d -> agent.actions().unitCommand(unitsToAttackWith,
                                    Abilities.MOVE, point2d, false)));
                }
                if (unitsThatMustMove.size() > 0) {
                    centreOfMass.ifPresentOrElse(point2d ->
                                    agent.actions().unitCommand(unitsThatMustMove, Abilities.ATTACK, point2d, false),
                            () -> defencePosition.ifPresent(point2d -> agent.actions().unitCommand(unitsToAttackWith, Abilities.MOVE, point2d, false)));
                }
                if (maybeEnemyArmy.isPresent()) {
                    // TODO this belongs in a task.
                    AtomicInteger stimmedMarines = new AtomicInteger(0);
                    AtomicInteger stimmedMarauders = new AtomicInteger(0);
                    Set<Tag> marinesWithoutStim = observationInterface.getUnits(unitInPool ->
                            unitsToAttackWith.contains(unitInPool.getTag()) &&
                                    (unitInPool.unit().getType() == Units.TERRAN_MARINE) &&
                                    unitInPool.unit().getPosition().toPoint2d().distance(maybeEnemyArmy.get().position()) < 10f &&
                                    unitInPool.unit().getHealth().filter(health -> health > 25f).isPresent()
                    ).stream().filter(unitInPool -> {
                        if (unitInPool.unit().getBuffs().contains(Buffs.STIMPACK)) {
                            stimmedMarines.incrementAndGet();
                            return false;
                        } else {
                            return true;
                        }
                    }).map(unitInPool -> unitInPool.getTag()).collect(Collectors.toSet());

                    Set<Tag> maraudersWithoutStim = observationInterface.getUnits(unitInPool ->
                            unitsToAttackWith.contains(unitInPool.getTag()) &&
                                    (unitInPool.unit().getType() == Units.TERRAN_MARAUDER) &&
                                    unitInPool.unit().getPosition().toPoint2d().distance(maybeEnemyArmy.get().position()) < 10f &&
                                    unitInPool.unit().getHealth().filter(health -> health > 40f).isPresent()
                    ).stream().filter(unitInPool -> {
                        if (unitInPool.unit().getBuffs().contains(Buffs.STIMPACK_MARAUDER)) {
                            stimmedMarauders.incrementAndGet();
                            return false;
                        } else {
                            return true;
                        }
                    }).map(unitInPool -> unitInPool.getTag()).collect(Collectors.toSet());
                    // Stim 1:1 ratio
                    int stimsRequested = Math.max(0, (int)maybeEnemyArmy.get().size() - stimmedMarines.get() - stimmedMarauders.get());
                    marinesWithoutStim =
                            marinesWithoutStim.stream().limit(stimsRequested).collect(Collectors.toSet());
                    if (marinesWithoutStim.size() > 0) {
                        agent.actions().unitCommand(marinesWithoutStim, Abilities.EFFECT_STIM_MARINE, false);
                        stimsRequested -= marinesWithoutStim.size();
                    }
                    maraudersWithoutStim = maraudersWithoutStim.stream().limit(stimsRequested).collect(Collectors.toSet());
                    if (maraudersWithoutStim.size() > 0) {
                        agent.actions().unitCommand(maraudersWithoutStim, Abilities.EFFECT_STIM_MARAUDER, false);
                    }
                }
            }
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
        if (defencePosition.isPresent()) {
            if (unit.getPosition().toPoint2d().distance(defencePosition.get()) > 2.5) {
                agent.actions().unitCommand(unit,
                        Abilities.ATTACK_ATTACK, defencePosition.get(), false);
            }
        }
    }

    // Get size (in unit count) of the reserve army before it should add it to the attacking army.
    public int getTargetMarines() {
        int attackingArmySize = attackingArmy.size();
        int myFoodCap = agent.observation().getFoodCap() - attackingArmySize;
        int result = Math.max(10, Math.min(attackingArmySize / 4, (int)(myFoodCap * 0.25)));
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

    public void debug(S2Agent agent) {
        centreOfMass.ifPresent(point2d -> {
            float z = agent.observation().terrainHeight(point2d);
            agent.debug().debugSphereOut(Point.of(point2d.getX(), point2d.getY(), z), isRegrouping ? 5f : 10f, Color.YELLOW);
        });
    }

    public boolean predictWinAgainst(Army army) {
        // secret sauce.
        return (attackingArmy.size() > army.threat());
    }
    public boolean predictDefensiveWinAgainst(Army army) {
        // secret sauce.
        return (reserveArmy.size() > army.threat());
    }
}
