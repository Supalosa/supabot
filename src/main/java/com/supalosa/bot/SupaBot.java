package com.supalosa.bot;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.ChatReceived;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.supalosa.bot.analysis.AnalyseMap;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.awareness.*;
import com.supalosa.bot.builds.ThreeRaxStimCombatConcussivePush;
import com.supalosa.bot.debug.DebugTarget;
import com.supalosa.bot.engagement.TerranBioThreatCalculator;
import com.supalosa.bot.engagement.ThreatCalculator;
import com.supalosa.bot.placement.StructurePlacementCalculator;
import com.supalosa.bot.task.*;
import com.supalosa.bot.task.terran.BaseTerranTask;
import com.supalosa.bot.task.terran.OrbitalCommandManagerTask;
import com.supalosa.bot.task.terran.SimpleBuildOrderTask;
import com.supalosa.bot.utils.UnitComparator;
import com.supalosa.bot.utils.UnitFilter;
import com.supalosa.bot.utils.Utils;

import java.util.*;

public class SupaBot extends S2Agent implements AgentData {

    private final TaskManager taskManager;
    private final FightManager fightManager;
    private final GameData gameData;
    private final MapAwareness mapAwareness;
    private final EnemyAwareness enemyAwareness;
    private boolean isDebug = false;
    private boolean isSlow = false;
    private Optional<AnalysisResults> mapAnalysis = Optional.empty();
    private Optional<StructurePlacementCalculator> structurePlacementCalculator = Optional.empty();
    private Map<UnitType, UnitTypeData> unitTypeData = null;
    private long lastRebalanceAt = 0L;

    private Multimap<Integer, Task> singletonTasksToDispatch = ArrayListMultimap.create();

    private final DebugTarget debugTarget;

    public SupaBot(boolean isDebug, DebugTarget debugTarget) {
        this.isDebug = isDebug;
        ThreatCalculator threatCalculator = new TerranBioThreatCalculator();
        this.taskManager = new TaskManagerImpl();
        this.fightManager = new FightManager(this);
        this.mapAwareness = new MapAwarenessImpl(threatCalculator);
        this.enemyAwareness = new EnemyAwarenessImpl(threatCalculator);
        this.gameData = new GameData(observation());
        this.debugTarget = debugTarget;
    }

    @Override
    public void onGameEnd() {
        this.debugTarget.stop();
    }

    @Override
    public void onGameStart() {
        this.debugTarget.initialise(this);
        this.unitTypeData = observation().getUnitTypeData(true);
        mapAnalysis = observation().getGameInfo(true).getStartRaw().map(startRaw -> AnalyseMap.analyse(
                observation(),
                gameData,
                startRaw));
        structurePlacementCalculator = mapAnalysis
                .map(analysisResults -> new StructurePlacementCalculator(analysisResults, gameData,
                        observation().getStartLocation().toPoint2d()));
        this.mapAwareness.setStartPosition(observation().getStartLocation().toPoint2d());
        mapAnalysis.ifPresent(analysis -> this.mapAwareness.setMapAnalysisResults(analysis));

        dispatchTaskOnce(18, new ScoutTask(mapAwareness.getMaybeEnemyPositionNearEnemyBase(), 1));
        dispatchTaskOnce(15, new OrbitalCommandManagerTask(100));
        dispatchTaskOnce(1, new SimpleBuildOrderTask(
                new ThreeRaxStimCombatConcussivePush(),
                new BaseTerranTask()));
    }

    @Override
    public void onStep() {
        mapAwareness.onStep(this, this);
        enemyAwareness.onStep(this.observation(), this);
        structurePlacementCalculator.ifPresent(spc -> spc.onStep(this, this));
        taskManager.onStep(this, this);
        fightManager.onStep(taskManager, this);

        // Dispatch one-off tasks.
        if (singletonTasksToDispatch.size() > 0) {
            Set<Integer> suppliesReached = new HashSet<>();
            Map<Task, Integer> notDispatched = new HashMap<>();
            singletonTasksToDispatch.forEach((supplyTrigger, task) -> {
                if (observation().getFoodUsed() >= supplyTrigger) {
                    suppliesReached.add(supplyTrigger);
                    if (!taskManager.addTask(task, 1)) {
                        notDispatched.put(task, supplyTrigger);
                    }
                }
            });
            suppliesReached.forEach(supplyTrigger -> singletonTasksToDispatch.removeAll(supplyTrigger));
            notDispatched.forEach((task, supplyTrigger) -> singletonTasksToDispatch.put(supplyTrigger, task));
        }

        List<ChatReceived> chat = observation().getChatMessages();
        for (ChatReceived chatReceived : chat) {
            if (chatReceived.getPlayerId() != observation().getPlayerId()) {
                continue;
            }
            if (chatReceived.getMessage().contains("debug")) {
                this.isDebug = !isDebug;
                // send one more debug command to flush the buffer.
                debug().sendDebug();
                actions().sendChat("Debug: " + isDebug, ActionChat.Channel.TEAM);
            }

            if (chatReceived.getMessage().contains("slow")) {
                this.isDebug = true;
                this.isSlow = !this.isSlow;
                actions().sendChat("Slow: " + isSlow, ActionChat.Channel.TEAM);
            }
        }

        debugTarget.onStep(this, this);
        if (isDebug) {
            if (isSlow) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (this.taskManager != null) {
                this.taskManager.debug(this);
            }
            this.mapAwareness().debug(this);
            this.enemyAwareness.debug(this);
            this.structurePlacementCalculator.ifPresent(spc -> spc.debug(this));
            this.fightManager.debug(this);
            mapAwareness.getObservedCreepCoverage().ifPresent(creep ->
                    debug().debugTextOut(String.format("Creep: %.1f%%", (creep * 100f)), Point2d.of(0.92f, 0.25f), Color.WHITE, 8));

            debug().sendDebug();
        }
    }

    private void dispatchTaskOnce(int atSupply, Task task) {
        singletonTasksToDispatch.put(atSupply, task);
    }

    private void sendTeamChat(String message) {
        actions().sendChat(message, ActionChat.Channel.TEAM);
    }

    @Override
    public void onUnitCreated(UnitInPool unitInPool) {
        if (!(unitInPool.unit().getType() instanceof Units)) {
            return;
        }
        taskManager.dispatchUnit(unitInPool.unit());
    }

    private Optional<Unit> findNearestCommandCentreWithMinerals(Point2d start) {
        List<UnitInPool> units = observation().getUnits(
                UnitFilter.builder()
                        .alliance(Alliance.SELF)
                        .unitTypes(Constants.TERRAN_CC_TYPES)
                        .filter(unit -> unit.getIdealHarvesters().isPresent())
                        .build());
        return units.stream()
                .min(UnitComparator.builder()
                        .distanceToPoint(start)
                        .ascending(true).build())
                .map(unitInPool -> unitInPool.unit());
    }

    @Override
    public void onUnitIdle(UnitInPool unitInPool) {
        Unit unit = unitInPool.unit();
        if (!(unit.getType() instanceof Units)) {
            return;
        }
        switch ((Units) unit.getType()) {
            case TERRAN_SCV:
                // TODO mining task
                findNearestCommandCentreWithMinerals(unit.getPosition().toPoint2d()).ifPresent(commandCentre -> {
                    Utils.findNearestMineralPatch(observation(), commandCentre.getPosition().toPoint2d()).ifPresent(mineralPatch ->
                            actions().unitCommand(unit, Abilities.SMART, mineralPatch, false));
                });
                break;
            default:
                fightManager.onUnitIdle(unitInPool);
                break;
        }
    }

    @Override
    public Optional<StructurePlacementCalculator> structurePlacementCalculator() {
        return structurePlacementCalculator;
    }

    @Override
    public GameData gameData() {
        return gameData;
    }

    @Override
    public Optional<AnalysisResults> mapAnalysis() {
        return this.mapAnalysis;
    }

    @Override
    public MapAwareness mapAwareness() {
        return mapAwareness;
    }

    @Override
    public FightManager fightManager() {
        return fightManager;
    }

    @Override
    public TaskManager taskManager() {
        return taskManager;
    }

    @Override
    public EnemyAwareness enemyAwareness() {
        return enemyAwareness;
    }

    @Override
    public void onUnitDestroyed(UnitInPool unit) {
        enemyAwareness.onUnitDestroyed(unit);
    }

}
