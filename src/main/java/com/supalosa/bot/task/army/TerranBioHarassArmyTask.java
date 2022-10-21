package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.analysis.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.analysis.production.UnitTypeRequest;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.awareness.RegionData;
import com.supalosa.bot.pathfinding.RegionGraphPath;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.TaskManager;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A subtype of TerranBioArmyTask that is smaller and disbands if the overall army is too small.
 * It can also load units into medivac and drop into enemy base.
 */
public class TerranBioHarassArmyTask extends TerranBioArmyTask {

    private enum HarassMode {
        GROUND,
        AIR
    }

    private enum LoadingState {
        LOADING,
        MOVING,
        UNLOADING
    }

    private boolean isComplete = false;

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = 0L;

    private HarassMode harassMode = HarassMode.GROUND;
    private long harassModeCalculatedAt = 0L;

    private LoadingState loadingState = LoadingState.MOVING;
    private long loadingStateChangedAt = 0L;

    private int deleteAtArmyCount = 200;

    public TerranBioHarassArmyTask(String armyName, int basePriority, int deleteAtArmyCount) {
        super(armyName, basePriority);
        this.deleteAtArmyCount = deleteAtArmyCount;
    }

    @Override
    public void onStep(TaskManager taskManager, AgentData data, S2Agent agent) {
        super.onStep(taskManager, data, agent);
        long gameLoop = agent.observation().getGameLoop();

        if (gameLoop > desiredCompositionUpdatedAt + 22L) {
            desiredCompositionUpdatedAt = gameLoop;
            updateBioArmyComposition();
        }
        // This army disappears if the overall army is small.
        if (harassMode == HarassMode.GROUND && agent.observation().getArmyCount() < deleteAtArmyCount) {
            this.isComplete = true;
        }

        if (gameLoop > harassModeCalculatedAt + 22L * 5) {
            harassModeCalculatedAt = gameLoop;
            Optional<Region> currentRegion = centreOfMass.flatMap(point2d -> data.mapAwareness().getRegionDataForPoint(point2d).map(RegionData::region));
            Optional<Region> targetRegion = targetPosition.flatMap(point2d -> data.mapAwareness().getRegionDataForPoint(point2d).map(RegionData::region));
            if (currentRegion.isPresent() && targetRegion.isPresent()) {
                Optional<RegionGraphPath> pathByAir = data.mapAwareness().generatePath(currentRegion.get(), targetRegion.get(), MapAwareness.PathRules.AIR_AVOID_ENEMY_ARMY);
                Optional<RegionGraphPath> pathByGround = data.mapAwareness().generatePath(currentRegion.get(), targetRegion.get(), MapAwareness.PathRules.AVOID_ENEMY_ARMY);
                if (pathByAir.isPresent() && !pathByGround.isPresent()) {
                    harassMode = HarassMode.AIR;
                } else if (!pathByAir.isPresent() && pathByGround.isPresent()) {
                    harassMode = HarassMode.GROUND;
                } else if (harassMode == HarassMode.GROUND &&
                        pathByAir.map(RegionGraphPath::getWeight).orElse(0.0) < pathByGround.map(RegionGraphPath::getWeight).orElse(0.0) * 0.85) {
                    harassMode = HarassMode.AIR;
                } else if (harassMode == HarassMode.AIR && getFightPerformance() == FightPerformance.WINNING) {
                    harassMode = HarassMode.GROUND;
                }
            }
            List<Unit> units = armyUnits.stream().map(tag ->
                            agent.observation().getUnit(tag)).filter(unit -> unit != null)
                    .map(UnitInPool::unit).collect(Collectors.toList());
            boolean allGroundUnits = units.stream().noneMatch(unit -> unit.getFlying().orElse(false));
            boolean allAirUnits = units.stream().allMatch(unit -> unit.getFlying().orElse(false));
            if (allGroundUnits || allAirUnits) {
                harassMode = HarassMode.GROUND;
            }
            if (harassMode == HarassMode.AIR && allAirUnits) {
                this.setPathRules(MapAwareness.PathRules.AIR_AVOID_ENEMY_ARMY);
            } else {
                this.setPathRules(MapAwareness.PathRules.AVOID_ENEMY_ARMY);
            }
        }

    }

    private void updateBioArmyComposition() {
        List<UnitTypeRequest> result = new ArrayList<>();
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARINE)
                .productionAbility(Abilities.TRAIN_MARINE)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .amount(8)
                .build()
        );
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARAUDER)
                .productionAbility(Abilities.TRAIN_MARAUDER)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .needsTechLab(true)
                .amount(4)
                .build()
        );
        result.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MEDIVAC)
                .productionAbility(Abilities.TRAIN_MEDIVAC)
                .producingUnitType(Units.TERRAN_STARPORT)
                .amount(2)
                .build()
        );
        desiredComposition = result;
    }


    @Override
    protected AggressionState attackCommand(S2Agent agent,
                                            AgentData data,
                                            Optional<Point2d> centreOfMass,
                                            Optional<Point2d> suggestedAttackMovePosition,
                                            Optional<Point2d> suggestedRetreatMovePosition,
                                            Optional<Army> maybeEnemyArmy) {
        AggressionState parentState = super.attackCommand(agent, data, centreOfMass,
                suggestedAttackMovePosition, suggestedRetreatMovePosition, maybeEnemyArmy);

        ObservationInterface observationInterface = agent.observation();
        ActionInterface actionInterface = agent.actions();

        handleMedivacLoading(agent, data, actionInterface, suggestedAttackMovePosition);

        return parentState;
    }

    @Override
    protected AggressionState retreatCommand(S2Agent agent,
                                             AgentData data,
                                             Optional<Point2d> centreOfMass,
                                             Optional<Point2d> suggestedAttackMovePosition,
                                             Optional<Point2d> suggestedRetreatMovePosition,
                                             Optional<Army> enemyArmy, Optional<Army> maybeEnemyArmy) {
        AggressionState parentState = super.retreatCommand(agent, data, centreOfMass,
                suggestedAttackMovePosition, suggestedRetreatMovePosition, enemyArmy, maybeEnemyArmy);

        ObservationInterface observationInterface = agent.observation();
        ActionInterface actionInterface = agent.actions();

        handleMedivacLoading(agent, data, actionInterface, suggestedAttackMovePosition);
        return parentState;
    }

    @Override
    protected AggressionState regroupCommand(S2Agent agent,
                                             AgentData data,
                                             Optional<Point2d> centreOfMass,
                                             Optional<Point2d> suggestedAttackMovePosition,
                                             Optional<Point2d> suggestedRetreatMovePosition,
                                             Optional<Army> maybeEnemyArmy) {
        AggressionState parentState = super.regroupCommand(agent, data, centreOfMass,
                suggestedAttackMovePosition, suggestedRetreatMovePosition, maybeEnemyArmy);

        ObservationInterface observationInterface = agent.observation();
        ActionInterface actionInterface = agent.actions();

        handleMedivacLoading(agent, data, actionInterface, suggestedAttackMovePosition);
        return parentState;
    }


    private void handleMedivacLoading(S2Agent agent, AgentData data, ActionInterface actionInterface, Optional<Point2d> suggestedAttackMovePosition) {
        Function<Set<Tag>, Stream<Unit>> unitsStream = units -> units.stream().map(tag ->
                agent.observation().getUnit(tag)).filter(unit -> unit != null).map(UnitInPool::unit);

        if (!suggestedAttackMovePosition.isPresent()) {
            return;
        }
        if (harassMode == HarassMode.AIR) {
            long gameLoop = agent.observation().getGameLoop();
            List<Unit> airUnitsNotFull = unitsStream.apply(armyUnits)
                    .filter(unit -> unit.getFlying().orElse(false))
                    .filter(unit -> unit.getCargoSpaceTaken().orElse(0) < unit.getCargoSpaceMax().orElse(0) - 2)
                    .collect(Collectors.toList());
            List<Unit> airUnitsWithCargo = unitsStream.apply(armyUnits)
                    .filter(unit -> unit.getFlying().orElse(false))
                    .filter(unit -> unit.getCargoSpaceTaken().orElse(0) > 0)
                    .collect(Collectors.toList());
            List<Unit> airUnits = unitsStream.apply(armyUnits)
                    .filter(unit -> unit.getFlying().orElse(false))
                    .filter(unit -> unit.getCargoSpaceTaken().isPresent())
                    .collect(Collectors.toList());
            List<Unit> groundUnits = unitsStream.apply(armyUnits)
                    .filter(unit -> unit.getFlying().map(isFlying -> !isFlying).orElse(false))
                    .collect(Collectors.toList());
            if (gameLoop > loadingStateChangedAt + 22L) {
                loadingStateChangedAt = gameLoop;

                Optional<Region> currentRegion = centreOfMass.flatMap(point2d -> data.mapAwareness().getRegionDataForPoint(point2d).map(RegionData::region));
                Optional<Region> targetRegion = targetPosition.flatMap(point2d -> data.mapAwareness().getRegionDataForPoint(point2d).map(RegionData::region));
                Optional<Region> nextRegion = suggestedAttackMovePosition.flatMap(point2d -> data.mapAwareness().getRegionDataForPoint(point2d).map(RegionData::region));
                if (currentRegion.equals(targetRegion)) {
                    loadingState = LoadingState.UNLOADING;
                } else if (groundUnits.size() > 0 && nextRegion.equals(targetRegion)) {
                    loadingState = LoadingState.LOADING;
                } else {
                    loadingState = LoadingState.MOVING;
                }
            }
            if (loadingState == LoadingState.UNLOADING) {
                for (Unit unit : airUnitsWithCargo) {
                    actionInterface.unitCommand(unit, Abilities.UNLOAD_ALL_AT_MEDIVAC, unit.getPosition().toPoint2d(), false);
                }
                if (groundUnits.size() > 0) {
                    actionInterface.unitCommand(groundUnits, Abilities.ATTACK, suggestedAttackMovePosition.get(), false);
                }
            } else if (loadingState == LoadingState.MOVING) {
                if (airUnits.size() > 0) {
                    actionInterface.unitCommand(airUnits, Abilities.MOVE, suggestedAttackMovePosition.get(), false);
                    actionInterface.unitCommand(airUnits, Abilities.EFFECT_MEDIVAC_IGNITE_AFTERBURNERS, false);
                }
                if (groundUnits.size() > 0) {
                    actionInterface.unitCommand(groundUnits, Abilities.ATTACK, suggestedAttackMovePosition.get(), false);
                }
            } else if (loadingState == LoadingState.LOADING) {
                if (groundUnits.size() > 0 && airUnitsNotFull.size() > 0) {
                    actionInterface.unitCommand(groundUnits, Abilities.SMART, airUnitsNotFull.get(0), false);
                } else if (groundUnits.size() > 0 && suggestedAttackMovePosition.isPresent()) {
                    actionInterface.unitCommand(groundUnits, Abilities.MOVE, suggestedAttackMovePosition.get(), false);
                }
                if (airUnits.size() > 0) {
                    if (centreOfMass.isPresent()) {
                        actionInterface.unitCommand(airUnits, Abilities.MOVE, centreOfMass.get(), false);
                    }
                }
            }
        } else {
            loadingState = LoadingState.UNLOADING;
            List<Unit> airUnitsNotEmpty = unitsStream.apply(armyUnits)
                    .filter(unit -> unit.getFlying().orElse(false))
                    .filter(unit -> unit.getCargoSpaceTaken().orElse(0) > 0)
                    .collect(Collectors.toList());
            if (airUnitsNotEmpty.size() > 0) {
                for (Unit unit : airUnitsNotEmpty) {
                    actionInterface.unitCommand(unit, Abilities.UNLOAD_ALL_AT_MEDIVAC, unit.getPosition().toPoint2d(), false);
                }
            }
        }
    }

    @Override
    public List<UnitTypeRequest> requestingUnitTypes() {
        return desiredComposition;
    }

    @Override
    public boolean isComplete() {
        return isComplete;
    }

    @Override
    public boolean isSimilarTo(Task otherTask) {
        if (otherTask instanceof TerranBioHarassArmyTask) {
            return ((TerranBioHarassArmyTask) otherTask).armyName.equals(this.armyName);
        }
        return false;
    }

    @Override
    public boolean wantsUnit(Unit unit) {
        // Stop accepting ground units once we're in air mode.
        if (harassMode == HarassMode.AIR && !(unit.getFlying().orElse(true))) {
            return false;
        }
        return super.wantsUnit(unit);
    }

    @Override
    public String getDebugText() {
        return super.getDebugText() + " [" + harassMode + ", " + loadingState + "]";
    }
}
