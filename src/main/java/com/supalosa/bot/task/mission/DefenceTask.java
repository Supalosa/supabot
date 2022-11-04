package com.supalosa.bot.task.mission;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.game.PlayerInfo;
import com.github.ocraft.s2client.protocol.game.Race;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.analysis.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.task.*;
import com.supalosa.bot.task.army.ArmyTask;
import com.supalosa.bot.task.army.FightPerformance;
import com.supalosa.bot.task.message.TaskMessage;
import com.supalosa.bot.task.message.TaskPromise;
import com.supalosa.bot.utils.CalculateCurrentCompositionVisitor;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This is a localised defence task.
 */
public class DefenceTask extends DefaultTaskWithUnits implements MissionTask {

    // Minimum distance from another DefenceTask. It is the responsibility of the caller to cluster it better.
    private static final double DISTANCE_THRESHOLD = 5f;
    // Unique number of attacks until we start building static defence.
    private static final int L1_DEFENCE_THRESHOLD = 2;
    private static final int L2_DEFENCE_THRESHOLD = 5;
    private static final int L3_DEFENCE_THRESHOLD = 10;

    private static final List<UnitTypeRequest> L0_TERRAN_DEFENCE = List.of(
            ImmutableUnitTypeRequest.builder().unitType(Units.TERRAN_MARINE)
                    .producingUnitType(Units.TERRAN_BARRACKS)
                    .productionAbility(Abilities.TRAIN_MARINE)
                    .amount(1)
                    .build(),
            ImmutableUnitTypeRequest.builder().unitType(Units.TERRAN_SCV)
                    .producingUnitType(Units.TERRAN_SCV)
                    .productionAbility(Abilities.TRAIN_SCV)
                    .amount(2)
                    .build());
    private static final List<UnitTypeRequest> L1_TERRAN_DEFENCE = List.of(
            ImmutableUnitTypeRequest.builder().unitType(Units.TERRAN_MARINE)
                    .producingUnitType(Units.TERRAN_BARRACKS)
                    .productionAbility(Abilities.TRAIN_MARINE)
                    .amount(4)
                    .build());
    private static final List<UnitTypeRequest> L2_TERRAN_DEFENCE = List.of(
            ImmutableUnitTypeRequest.builder().unitType(Units.TERRAN_MARINE)
                    .producingUnitType(Units.TERRAN_BARRACKS)
                    .productionAbility(Abilities.TRAIN_MARINE)
                    .amount(4)
                    .build(),
            ImmutableUnitTypeRequest.builder().unitType(Units.TERRAN_BUNKER)
                    .producingUnitType(Units.TERRAN_SCV)
                    .productionAbility(Abilities.BUILD_BUNKER)
                    .amount(1)
                    .build());
    private static final List<UnitTypeRequest> L3_TERRAN_DEFENCE = List.of(
            ImmutableUnitTypeRequest.builder().unitType(Units.TERRAN_MARINE)
                    .producingUnitType(Units.TERRAN_BARRACKS)
                    .productionAbility(Abilities.TRAIN_MARINE)
                    .amount(4)
                    .build(),
            ImmutableUnitTypeRequest.builder().unitType(Units.TERRAN_BUNKER)
                    .producingUnitType(Units.TERRAN_SCV)
                    .productionAbility(Abilities.BUILD_BUNKER)
                    .amount(1)
                    .build(),
            ImmutableUnitTypeRequest.builder().unitType(Units.TERRAN_SIEGE_TANK)
                    .alternateForm(Units.TERRAN_SIEGE_TANK_SIEGED)
                    .producingUnitType(Units.TERRAN_FACTORY)
                    .needsTechLab(true)
                    .productionAbility(Abilities.TRAIN_SIEGE_TANK)
                    .amount(2)
                    .build());

    private static final Map<Race, List<List<UnitTypeRequest>>> DEFENCE_MATRIX = Map.of(
            Race.TERRAN, List.of(L0_TERRAN_DEFENCE, L1_TERRAN_DEFENCE, L2_TERRAN_DEFENCE, L3_TERRAN_DEFENCE));

    // Time between attacks to be considered unique. A sustained attack longer than this is counted as multiple attacks.
    private static final long ATTACK_INTERVAL = 22L * 20;

    private Optional<Race> playerRace = Optional.empty();
    private List<UnitTypeRequest> targetComposition = Collections.emptyList();

    private Region region;
    private Optional<RegionData> latestRegionData = Optional.empty();
    private double priority;
    private boolean isComplete;

    private int numUniqueAttacks = 0;
    private long lastUniqueAttackAt = 0L;

    private int defenceLevel = 0;

    private Army virtualEnemyArmy = Army.empty();

    // The main army performing the defence.
    private Optional<ArmyTask> primaryArmy = Optional.empty();
    // Additional armies roped in to defend.
    private List<ArmyTask> additionalArmies = new ArrayList<>();

    private List<UnitTypeRequest> unitTypeRequests = new ArrayList<>();
    private final Function<DefenceTask, ArmyTask> armyTaskSupplier;
    // The composition of all armies taking part in this defence.
    private Map<UnitType, Integer> overallComposition;

    /**
     *
     * @param region Region to defend.
     * @param priority Starting priority of the defence task.
     * @param armyTaskSupplier Supplier of army, if there are no armies to pull from. It should be an army that terminates when
     *                         the given DefenceTask is terminated.
     */
    public DefenceTask(Region region, int priority, Function<DefenceTask, ArmyTask> armyTaskSupplier) {
        super(priority);
        this.region = region;
        this.priority = priority;
        this.isComplete = false;
        this.armyTaskSupplier = armyTaskSupplier;
    }

    @Override
    public void onStepImpl(TaskManager taskManager, AgentWithData agentWithData) {
        if (this.priority <= 0.0) {
            this.isComplete = true;
        }
        // Calculate what the defence should look like.
        if (playerRace.isEmpty()) {
            playerRace = agentWithData.observation().getGameInfo().getPlayersInfo().stream()
                    .filter(playerInfo ->
                            playerInfo.getPlayerId() == agentWithData.observation().getPlayerId())
                    .findFirst()
                    .flatMap(PlayerInfo::getActualRace);
        }
        defenceLevel = Math.max(0, Math.min(defenceLevel,
                playerRace.map(race -> DEFENCE_MATRIX.get(race).size() - 1)
                        .orElseThrow(() -> new IllegalStateException("Race defence matrix not defined"))));
        targetComposition = playerRace.map(race -> DEFENCE_MATRIX.get(race)).orElseThrow().get(defenceLevel);
        overallComposition = calculateOverallComposition();
        unitTypeRequests = calculateUnitTypeRequests(overallComposition, targetComposition);

        // Calculate the threat.
        additionalArmies = additionalArmies.stream().filter(task -> !task.isComplete()).collect(Collectors.toList());
        List<Army> armyList = agentWithData.enemyAwareness().getMaybeEnemyArmies(region.centrePoint(), 20f);
        virtualEnemyArmy = Army.toVirtualArmy(armyList);


        long gameLoop = agentWithData.observation().getGameLoop();

        // Manage the priority of the threat.
        if (virtualEnemyArmy.threat() < 0.1) {
            // Decay of defence priority.
            priority = priority * 0.75;
            if (priority < 0.1) {
                priority = 0.0;
            }
            return;
        } else {
            priority = Math.max(virtualEnemyArmy.threat(), priority);
            if (gameLoop > lastUniqueAttackAt + ATTACK_INTERVAL) {
                lastUniqueAttackAt = gameLoop;
                ++numUniqueAttacks;
                if (defenceLevel == 0 && numUniqueAttacks >= L1_DEFENCE_THRESHOLD) {
                    defenceLevel = 1;
                } else if (defenceLevel == 1 && numUniqueAttacks >= L2_DEFENCE_THRESHOLD) {
                    defenceLevel = 2;
                } else if (defenceLevel == 2 && numUniqueAttacks >= L3_DEFENCE_THRESHOLD) {
                    defenceLevel = 3;
                }
            }
        }

        // Manage the armies responding to the threat.
        if (primaryArmy.isEmpty()) {
            ArmyTask maybeArmyTask = armyTaskSupplier.apply(this);
            if (taskManager.addTask(maybeArmyTask, 1)) {
                primaryArmy = Optional.of(maybeArmyTask);
            }
        } else {
            ArmyTask primaryArmyTask = primaryArmy.get();
            if (primaryArmyTask.isComplete()) {
                primaryArmy = Optional.empty();
            } else {
                // Move units to the primary army for the defence task.
                if (getAssignedUnits().size() > 0) {
                    taskManager.reassignUnits(this, primaryArmyTask, agentWithData.observation(), unit -> true);
                }
                // Remove units we don't want anymore.
                Set<UnitType> wantedUnitTypes = new HashSet<>();
                for (UnitTypeRequest unitTypeRequests : unitTypeRequests) {
                    wantedUnitTypes.add(unitTypeRequests.unitType());
                    unitTypeRequests.alternateForm().ifPresent(wantedUnitTypes::add);
                    if (getAmountOfUnit(unitTypeRequests.unitType()) > unitTypeRequests.amount()) {
                        primaryArmyTask.removeUnitOfType(unitTypeRequests.unitType());
                    }
                }
                for (UnitType unitType : overallComposition.keySet()) {
                    if (!wantedUnitTypes.contains(unitType)) {
                        primaryArmyTask.removeUnitOfType(unitType);
                    }
                }
            }
        }

        if (additionalArmies.isEmpty()) {
            // Find a nearby, small army that can win against the threat.
            Optional<ArmyTask> reinforcement = taskManager.findFreeArmyForTask(
                    this,
                    task -> task.getCentreOfMass().isPresent() && task.predictFightAgainst(virtualEnemyArmy) == FightPerformance.WINNING,
                    Comparator.comparingDouble(task-> task.getCentreOfMass().get().distance(region.centrePoint()) * task.getPower()));
            if (reinforcement.isPresent()) {
                additionalArmies.add(reinforcement.get());
            }
        }

        // Send the assigned armies to deal with the threat.
        Optional<RegionData> latestRegionData = agentWithData.mapAwareness().getRegionDataForId(region.regionId());
        if (latestRegionData.isEmpty()) {
            this.isComplete = true;
            return;
        }
        RegionData regionData = latestRegionData.get();
        Optional<Point2d> positionToAttack = regionData.bestTileTowardsEnemy();
        List<Point2d> enemyArmyPoints = armyList.stream()
                .filter(army -> army.position().isPresent())
                .map(army -> army.position().get())
                .collect(Collectors.toList());
        if (enemyArmyPoints.size() > 0) {
            Point2d averagePosition = Point2d.of(0f, 0f);
            for (Point2d enemyArmyPosition : enemyArmyPoints) {
                averagePosition = averagePosition.add(enemyArmyPosition);
            }
            averagePosition = averagePosition.div(enemyArmyPoints.size());
            positionToAttack = Optional.of(averagePosition);
        }
        final Optional<Point2d> finalPositionToAttack = positionToAttack;
        if (primaryArmy.isPresent()) {
            primaryArmy.get().setTargetPosition(finalPositionToAttack);
            primaryArmy.get().setRetreatPosition(finalPositionToAttack);
        }
        additionalArmies.forEach(army -> {
            army.setTargetPosition(finalPositionToAttack);
            //army.setRetreatPosition(finalPositionToAttack);
        });
    }

    private Map<UnitType, Integer> calculateOverallComposition() {
        CalculateCurrentCompositionVisitor visitor = new CalculateCurrentCompositionVisitor();
        // Check units that are staged in the DefenceTask itself.
        visitor.visit(this);
        // Check units in the primary army.
        if (primaryArmy.isPresent()) {
            visitor.visit(primaryArmy.get());
        }
        // Check units in the reinforcement armies.
        additionalArmies.forEach(visitor::visit);
        return visitor.getResult();
    }

    private List<UnitTypeRequest> calculateUnitTypeRequests(Map<UnitType, Integer> overallComposition, List<UnitTypeRequest> targetComposition) {
        Map<UnitType, Integer> currentCounts = overallComposition;
        List<UnitTypeRequest> result = targetComposition.stream().map(request ->
                ImmutableUnitTypeRequest.builder()
                    .from(request)
                    .amount(Math.max(0, request.amount() - currentCounts.getOrDefault(request.unitType(), 0)))
                    .build())
                .filter(request -> request.amount() > 0)
                .collect(Collectors.toList());
        return result;
    }

    @Override
    public Optional<TaskResult> getResult() {
        return Optional.empty();
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }

    @Override
    public String getKey() {
        return "Defence." + region.regionId();
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        if (otherTask == this) {
            return true;
        }
        if (otherTask instanceof DefenceTask) {
            DefenceTask other = (DefenceTask)otherTask;
            if (other.region.equals(this.region)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void debug(S2Agent agent) {
        float height = agent.observation().terrainHeight(this.region.centrePoint());
        Point point = Point.of(this.region.centrePoint().getX(), this.region.centrePoint().getY(), height);
        agent.debug().debugTextOut(String.format("Defending P%.1f L%d A%d", this.priority, this.defenceLevel, this.numUniqueAttacks), point, Color.PURPLE, 16);
    }

    @Override
    public String getDebugText() {
        return "Defence (" + region.regionId() + ")";
    }

    @Override
    public Optional<TaskPromise> onTaskMessage(Task taskOrigin, TaskMessage message) {
        return Optional.empty();
    }

    @Override
    public List<ArmyTask> getAdditionalArmies() {
        return additionalArmies;
    }

    @Override
    public List<UnitTypeRequest> requestingUnitTypes() {
        return unitTypeRequests;
    }

    @Override
    public Region getLocation() {
        return this.region;
    }

    @Override
    public void setLocation(Region location) {
        this.region = location;
    }

    @Override
    public int getPriority() {
        return (int) this.priority;
    }

    @Override
    public void endMission() {
        this.isComplete = true;
    }

    @Override
    public void onArmyRemoved(ArmyTask armyTask) {
        additionalArmies.remove(armyTask);
    }

    public void onRegionAttacked() {
    }
}
