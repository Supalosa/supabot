package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.Expansions;
import com.supalosa.bot.engagement.ThreatCalculator;
import com.supalosa.bot.utils.UnitFilter;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class EnemyAwarenessImpl implements EnemyAwareness {

    private long maybeEnemyArmyCalculatedAt = 0L;
    private Optional<ImmutableArmy> maybeLargestEnemyArmy = Optional.empty();
    private Map<Point, ImmutableArmy> enemyClusters = new HashMap<>();

    private final ThreatCalculator threatCalculator;

    public EnemyAwarenessImpl(ThreatCalculator threatCalculator) {
        this.threatCalculator = threatCalculator;
    }

    @Override
    public void onStep(ObservationInterface observationInterface, AgentData data) {


        if (observationInterface.getGameLoop() > maybeEnemyArmyCalculatedAt + 22L) {
            maybeEnemyArmyCalculatedAt = observationInterface.getGameLoop();
            List<UnitInPool> enemyArmy = observationInterface.getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.ENEMY)
                            .unitTypes(Constants.ARMY_UNIT_TYPES)
                            .build());
            Map<Point,List<UnitInPool>> clusters = Expansions.cluster(enemyArmy, 10f);

            // Army threat decays slowly
            if (maybeLargestEnemyArmy.isPresent() && observationInterface.getVisibility(maybeLargestEnemyArmy.get().position()) == Visibility.VISIBLE) {
                maybeLargestEnemyArmy = maybeLargestEnemyArmy.map(army -> army
                        .withSize(army.size() * 0.5f - 1.0f)
                        .withThreat(army.threat() * 0.5f - 1.0f));
            } else {
                maybeLargestEnemyArmy = maybeLargestEnemyArmy.map(army -> army
                        .withSize(army.size() * 0.999f)
                        .withThreat(army.threat() * 0.999f));
            }
            maybeLargestEnemyArmy = maybeLargestEnemyArmy.filter(army -> army.size() > 1.0);
            if (clusters.size() > 0) {
                int biggestArmySize = Integer.MIN_VALUE;
                this.enemyClusters = new HashMap();
                Point biggestArmy = null;
                for (Map.Entry<Point, List<UnitInPool>> entry : clusters.entrySet()) {
                    Point point = entry.getKey();
                    List<UnitInPool> units = entry.getValue();
                    Collection<UnitType> composition = getComposition(units);
                    double threat = threatCalculator.calculateThreat(composition);
                    if (threat > 5.0f) {
                        enemyClusters.put(point, ImmutableArmy.builder()
                                .position(point.toPoint2d())
                                .size(units.size())
                                .composition(composition)
                                .threat(threat)
                                .build());
                        int size = units.size();
                        if (size > biggestArmySize) {
                            biggestArmySize = size;
                            biggestArmy = point;
                        }
                    }
                }
                if (biggestArmy != null) {
                    if (this.maybeLargestEnemyArmy.isEmpty() || biggestArmySize > this.maybeLargestEnemyArmy.get().size()) {
                        this.maybeLargestEnemyArmy = Optional.of(enemyClusters.get(biggestArmy));
                    }
                }
            }
        }
    }

    @Override
    public Optional<Army> getMaybeEnemyArmy(Point2d point2d) {
        Point closest = null;
        double closestDistance = Float.MAX_VALUE;
        for (Map.Entry<Point, ImmutableArmy> entry : this.enemyClusters.entrySet()) {
            Point point = entry.getKey();
            ImmutableArmy army = entry.getValue();
            double distance = point2d.distance(point.toPoint2d());
            // TODO configurable distance.
            if (distance < 25f && distance < closestDistance) {
                closestDistance = distance;
                closest = point;
            }
        }
        return Optional.ofNullable(this.enemyClusters.get(closest));
    }

    @Override
    public Optional<Army> getLargestEnemyArmy() {
        return maybeLargestEnemyArmy.map(Function.identity());
    }

    private Collection<UnitType> getComposition(List<UnitInPool> unitInPools) {
        return unitInPools.stream().map(unitInPool -> unitInPool.unit().getType()).collect(Collectors.toList());
    }

    @Override
    public void debug(S2Agent agent) {
        this.enemyClusters.forEach((point, units) -> {
            agent.debug().debugSphereOut(point, units.size(), Color.RED);
        });
        maybeLargestEnemyArmy.ifPresent(army -> {
            float z = agent.observation().terrainHeight(army.position());
            Point point = Point.of(army.position().getX(), army.position().getY(), z);
            agent.debug().debugSphereOut(point, army.size(), Color.RED);
            agent.debug().debugTextOut("[" + army.size() + ", " + army.threat() + "]", point, Color.WHITE, 10);
        });
    }
}
