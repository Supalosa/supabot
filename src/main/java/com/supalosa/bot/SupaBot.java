package com.supalosa.bot;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.game.raw.StartRaw;
import com.github.ocraft.s2client.protocol.observation.Alert;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.analysis.AnalyseMap;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.placement.StructurePlacementCalculator;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public class SupaBot extends S2Agent {

    private final boolean isDebug;

    private Set<Tag> attackingArmy = new HashSet<>();
    private Set<Tag> reserveArmy = new HashSet<>();
    private Optional<Point2d> attackPosition = Optional.empty();
    private Optional<Point2d> regroupPosition = Optional.empty();

    private Set<Point2d> scoutedLocations = new HashSet<>();
    private long scoutResetLoopTime = 0;

    private Optional<AnalysisResults> mapAnalysis = Optional.empty();
    private Optional<StructurePlacementCalculator> structurePlacementCalculator = Optional.empty();

    public SupaBot(boolean isDebug) {
        this.isDebug = isDebug;
    }

    @Override
    public void onGameStart() {
        System.out.println("Hello world of Starcraft II bots!");

        mapAnalysis = observation().getGameInfo().getStartRaw().map(startRaw -> AnalyseMap.analyse(
                observation().getStartLocation(),
                startRaw));
        structurePlacementCalculator = mapAnalysis
                .map(analysisResults -> new StructurePlacementCalculator(analysisResults, observation().getStartLocation().toPoint2d()));
    }

    @Override
    public void onStep() {
        /*if (observation().getGameLoop() == 0) {
            observation().getGameInfo().getStartRaw().ifPresent(startRaw -> AnalyseMap.analyse(
                    observation().getStartLocation(),
                    startRaw));
        }*/
        if (observation().getGameLoop() > scoutResetLoopTime) {
            scoutedLocations.clear();
            scoutResetLoopTime = observation().getGameLoop() + 100;
        }

        tryBuildSupplyDepot();
        tryBuildBarracks();

        tryBuildScvs();
        Map<UnitType, UnitTypeData> unitTypes = observation().getUnitTypeData(true);
        Map<Ability, AbilityData> abilities = observation().getAbilityData(true);
        observation().getUnits(Alliance.NEUTRAL).forEach(unitInPool -> {
            unitInPool.getUnit().ifPresent(unit -> {
                /*if (unit.getDisplayType() == DisplayType.SNAPSHOT) {
                    System.out.println("Neutral snapshot " + unit.getType().toString() + " at " + unit.getPosition().toPoint2d());
                    unitTypes.get(unit.getType()).getAbility().ifPresent(ability -> {
                        abilities.get(ability).getFootprintRadius().ifPresent(footprint -> {
                            System.out.println("Footprint is " + footprint);
                        });
                    });
                }*/
            });
        });

        /*if (observation().getGameInfo().getStartRaw().isPresent()) {
            StartRaw startRaw = observation().getGameInfo().getStartRaw().get();
            for (int x = 0; x < startRaw.getTerrainHeight().getSize().getX(); ++x) {
                for (int y = 0; y < startRaw.getTerrainHeight().getSize().getY(); ++y) {
                    Point2d point2d = Point2d.of(x, y);
                    int height = startRaw.getTerrainHeight().sample(point2d, ImageData.Origin.BOTTOM_LEFT);
                    if (height == 0) {
                        continue;
                    }
                    float z = -16f + 32f * (height / 255f);
                    float fx = (float)x, fy = (float)y;
                    //debug().debugBoxOut(
                            Point.of(Math.max(0.0f, fx - .45f), Math.max(0.0f, fy - .45f), z + 0.1f),
                            Point.of(fx + .45f, fy + .45f, z + 0.1f),
                            Color.WHITE);
                    String text = "" + height;
                    Point point = Point.of(x, y, z);
                    //debug().debugTextOut(text, point, Color.WHITE, 10);
                }
            }
            //debug().sendDebug();
        }*/

        Optional<UnitInPool> randomCc = getRandomUnit(Units.TERRAN_COMMAND_CENTER);
        if (randomCc.isPresent()) {
            regroupPosition = randomCc.map(unitInPool -> unitInPool.unit().getPosition().toPoint2d());
        } else {
            regroupPosition = Optional.empty();
        }
        attackPosition = findEnemyPosition();

        // update ramp
        structurePlacementCalculator.ifPresent(spc -> {
            spc.getFirstSupplyDepot(observation()).ifPresent(supplyDepot -> {
                if (supplyDepot.unit().getType() == Units.TERRAN_SUPPLY_DEPOT) {
                    actions().unitCommand(supplyDepot.getTag(), Abilities.MORPH_SUPPLY_DEPOT_LOWER, false);
                }
            });
            spc.getSecondSupplyDepot(observation()).ifPresent(supplyDepot -> {
                if (supplyDepot.unit().getType() == Units.TERRAN_SUPPLY_DEPOT) {
                    actions().unitCommand(supplyDepot.getTag(), Abilities.MORPH_SUPPLY_DEPOT_LOWER, false);
                }
            });
            spc.getFirstSupplyDepotLocation().ifPresent(
                    spl -> drawDebugSquare(spl.getX(), spl.getY(), 1.0f, 1.0f, Color.GREEN));
            spc.getSecondSupplyDepotLocation().ifPresent(
                    spl -> drawDebugSquare(spl.getX(), spl.getY(), 1.0f, 1.0f, Color.GREEN));
        });
        debug().sendDebug();
    }

    private void drawDebugSquare(float x, float y, float w, float h, Color color) {
        // debug
        Point2d point2d = Point2d.of(x, y);
        float z = observation().terrainHeight(point2d);
        debug().debugBoxOut(
            Point.of(Math.max(0.0f, x), Math.max(0.0f, y), z + 0.1f),
                    Point.of(x+w, y+h, z + 0.1f),
                color);
    }

    private boolean needsSupplyDepot() {
        // If we are not supply capped, don't build a supply depot.
        if (observation().getFoodUsed() <= observation().getFoodCap() - 2) {
            return false;
        }
        return true;
    }

    private boolean tryBuildSupplyDepot() {
        if (!needsSupplyDepot()) {
            return false;
        }
        // Try and build a depot. Find a random TERRAN_SCV and give it the order.
        Optional<Point2d> position = Optional.empty();
        int numSupplyDepots = countUnitType(Units.TERRAN_SUPPLY_DEPOT, Units.TERRAN_SUPPLY_DEPOT_LOWERED);
        if (numSupplyDepots < 2 && structurePlacementCalculator.isPresent()) {
            if (numSupplyDepots == 0) {
                position = structurePlacementCalculator.get()
                        .getFirstSupplyDepotLocation();
            } else {
                position = structurePlacementCalculator.get()
                        .getSecondSupplyDepotLocation();
            }
            position.ifPresent(spl -> drawDebugSquare(spl.getX(), spl.getY(), 0.1f, 0.1f, Color.RED));
        }
        return tryBuildStructure(Abilities.BUILD_SUPPLY_DEPOT, Units.TERRAN_SCV, position);
    }

    private boolean tryBuildScvs() {
        int numBases = countUnitType(Units.TERRAN_COMMAND_CENTER);
        observation().getUnits(Alliance.SELF, UnitInPool.isUnit(Units.TERRAN_COMMAND_CENTER)).forEach(commandCentre -> {
            if (commandCentre.unit().getOrders().isEmpty()) {
                if (countUnitType(Units.TERRAN_SCV) < numBases * 24) {
                    actions().unitCommand(commandCentre.unit(), Abilities.TRAIN_SCV, false);
                }
            }
        });
        return true;
    }

    private boolean tryBuildStructure(Ability abilityTypeForStructure, UnitType unitType, Optional<Point2d> specificPosition) {
        // If a unit already is building a supply structure of this type, do nothing.
        if (!observation().getUnits(Alliance.SELF, doesBuildWith(abilityTypeForStructure)).isEmpty()) {
            return false;
        }

        // Just try a random location near the unit.
        Optional<UnitInPool> unitInPool = getRandomUnit(unitType);
        if (unitInPool.isPresent()) {
            Unit unit = unitInPool.get().unit();
            actions().unitCommand(
                    unit,
                    abilityTypeForStructure,
                    specificPosition
                            .map(point2d -> point2d.add(0.1f, 0.1f))
                            .orElseGet(() ->
                                    unit.getPosition()
                                            .toPoint2d()
                                            .add(Point2d.of(getRandomScalar(), getRandomScalar())
                                            .mul(15.0f))),
                    false);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onAlert(Alert alert) {
        System.out.println("Alert: " + alert.name());
    }

    private Predicate<UnitInPool> doesBuildWith(Ability abilityTypeForStructure) {
        return unitInPool -> unitInPool.unit()
                .getOrders()
                .stream()
                .anyMatch(unitOrder -> abilityTypeForStructure.equals(unitOrder.getAbility()));
    }

    private Optional<UnitInPool> getRandomUnit(UnitType unitType) {
        List<UnitInPool> units = observation().getUnits(Alliance.SELF, UnitInPool.isUnit(unitType));
        return units.isEmpty()
                ? Optional.empty()
                : Optional.of(units.get(ThreadLocalRandom.current().nextInt(units.size())));
    }

    private float getRandomScalar() {
        return ThreadLocalRandom.current().nextFloat() * 2 - 1;
    }

    private void attackCommand() {
        attackPosition.ifPresentOrElse(point2d ->
                actions().unitCommand(attackingArmy, Abilities.ATTACK_ATTACK, point2d, false),
                () -> regroupPosition.ifPresent(point2d -> actions().unitCommand(attackingArmy, Abilities.MOVE, point2d, false)));
    }

    @Override
    public void onUnitCreated(UnitInPool unitInPool) {
        switch ((Units) unitInPool.unit().getType()) {
            case TERRAN_MARINE:
                reserveArmy.add(unitInPool.getTag());
                if (reserveArmy.size() > 10) {
                    attackingArmy.addAll(reserveArmy);
                    reserveArmy.clear();
                    attackCommand();
                }
                break;
        }
    }

    @Override
    public void onUnitIdle(UnitInPool unitInPool) {
        Unit unit = unitInPool.unit();
        switch ((Units) unit.getType()) {
            case TERRAN_SCV:
                findNearestMineralPatch(unit.getPosition().toPoint2d()).ifPresent(mineralPath ->
                        actions().unitCommand(unit, Abilities.SMART, mineralPath, false));
                break;
            case TERRAN_BARRACKS:
                actions().unitCommand(unit, Abilities.TRAIN_MARINE, false);
                break;
            case TERRAN_MARINE:
                if (attackingArmy.contains(unit.getTag())) {
                    findRandomEnemyPosition().ifPresent(point2d ->
                            actions().unitCommand(unit, Abilities.ATTACK_ATTACK, point2d, false));
                }
                break;
            default:
                break;
        }
    }

    private Optional<Unit> findNearestMineralPatch(Point2d start) {
        List<UnitInPool> units = observation().getUnits(Alliance.NEUTRAL);
        double distance = Double.MAX_VALUE;
        Unit target = null;
        for (UnitInPool unitInPool : units) {
            Unit unit = unitInPool.unit();
            if (unit.getType().equals(Units.NEUTRAL_MINERAL_FIELD)) {
                double d = unit.getPosition().toPoint2d().distance(start);
                if (d < distance) {
                    distance = d;
                    target = unit;
                }
            }
        }
        return Optional.ofNullable(target);
    }

    private boolean tryBuildBarracks() {
        if (countUnitType(Units.TERRAN_SUPPLY_DEPOT) < 1) {
            return false;
        }
        if (needsSupplyDepot()) {
            return false;
        }

        int numBarracks = countUnitType(Units.TERRAN_BARRACKS);
        if (numBarracks > 10) {
            return false;
        }
        Optional<Point2d> position = Optional.empty();

        if (numBarracks == 0 && structurePlacementCalculator.isPresent()) {
            position = structurePlacementCalculator.get()
                    .getFirstBarracksLocation(observation().getStartLocation().toPoint2d());
        }

        return tryBuildStructure(Abilities.BUILD_BARRACKS, Units.TERRAN_SCV, position);
    }

    private int countUnitType(Units... unitType) {
        if (unitType.length == 1) {
            return observation().getUnits(Alliance.SELF, UnitInPool.isUnit(unitType[0])).size();
        } else {
            Set<Units> unitTypes = Set.of(unitType);
            return observation().getUnits(Alliance.SELF, unitInPool -> unitTypes.contains(unitInPool.unit().getType())).size();
        }
    }

    // Finds a worthwhile enemy position to move units towards.
    private Optional<Point2d> findEnemyPosition() {
        List<UnitInPool> enemyUnits = observation().getUnits(Alliance.ENEMY);
        if (enemyUnits.size() > 0) {
            // Move towards the closest to our base (for now)
            Point startLocation = observation().getStartLocation();
            return enemyUnits.stream()
                    .min(Comparator.comparing(unit -> unit.unit().getPosition().distance(startLocation)))
                    .map(minUnit -> {
                        //double dist = minUnit.unit().getPosition().distance(startLocation);
                        //System.out.println("Min unit " + minUnit.unit().getTag() + " (" + dist + ")");
                        return minUnit;
                    }).map(minUnit -> minUnit.unit().getPosition().toPoint2d())
                    .or(() -> findRandomEnemyPosition());
        } else {
            return findRandomEnemyPosition();
        }
    }

    // Tries to find a random location that can be pathed to on the map.
    // Returns Point2d if a new, random location has been found that is pathable by the unit.
    private Optional<Point2d> findRandomEnemyPosition() {
        ResponseGameInfo gameInfo = observation().getGameInfo();
        Optional<StartRaw> startRaw = gameInfo.getStartRaw();
        if (startRaw.isPresent()) {
            Set<Point2d> startLocations = new HashSet<>(startRaw.get().getStartLocations());
            startLocations.remove(observation().getStartLocation().toPoint2d());
            startLocations.removeAll(scoutedLocations);
            if (startLocations.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ArrayList<>(startLocations)
                    .get(ThreadLocalRandom.current().nextInt(startLocations.size())));
        } else {
            return Optional.empty();
        }
    }
}
