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
import com.github.ocraft.s2client.protocol.unit.DisplayType;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.Constants;
import com.supalosa.bot.Expansions;
import com.supalosa.bot.engagement.ThreatCalculator;
import com.supalosa.bot.utils.UnitFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class EnemyAwarenessImpl implements EnemyAwareness {

    private long maybeEnemyArmyCalculatedAt = 0L;
    private Optional<ImmutableArmy> maybeLargestEnemyArmy = Optional.empty();
    private Optional<Army> missingEnemyArmy = Optional.empty();
    private Army fullEnemyArmy = ImmutableArmy.builder().build();

    /**
     * A relatively up-to-date snapshot of existing clusters, units within them etc.
     */
    private Map<Point, ImmutableArmy> enemyClusters = new HashMap<>();
    private Multimap<ImmutableArmy, UnitInPool> knownUnitsToClusters = HashMultimap.create();

    /**
     * Tracking of units that we have seen before, but can't figure out where they are.
     */
    private Set<UnitInPool> missingEnemyUnits = new HashSet<>();
    private List<UnitInPool> destroyedUnits = new ArrayList<>();

    private final ThreatCalculator threatCalculator;

    public EnemyAwarenessImpl(ThreatCalculator threatCalculator) {
        this.threatCalculator = threatCalculator;
    }

    @Override
    public void onStep(ObservationInterface observationInterface, AgentData data) {
        final long gameLoop = observationInterface.getGameLoop();
        if (gameLoop > maybeEnemyArmyCalculatedAt + 22L) {
            maybeEnemyArmyCalculatedAt = observationInterface.getGameLoop();
            List<UnitInPool> allEnemyUnits = observationInterface.getUnits(
                    UnitFilter.builder()
                            .alliance(Alliance.ENEMY)
                            .unitTypes(Constants.ARMY_UNIT_TYPES)
                            .build());
            // Store this before we wipe knownUnitsToClusters.
            Collection<UnitInPool> previousKnownEnemyUnits = new ArrayList<>(knownUnitsToClusters.values());

            // Update clusters by merging, splitting etc.
            Map<Point, List<UnitInPool>> clusters = Expansions.cluster(allEnemyUnits, 10f);

            maybeLargestEnemyArmy = maybeLargestEnemyArmy.filter(army -> army.size() > 1.0);

            // TODO: instead of clearing this out, merge it (and apply decay)
            this.enemyClusters = new HashMap();
            if (clusters.size() > 0) {
                knownUnitsToClusters.clear();
                int biggestArmySize = Integer.MIN_VALUE;
                Point biggestArmy = null;
                for (Map.Entry<Point, List<UnitInPool>> entry : clusters.entrySet()) {
                    Point point = entry.getKey();
                    List<UnitInPool> units = entry.getValue();
                    Collection<UnitType> composition = getComposition(units);
                    double threat = threatCalculator.calculateThreat(composition);
                    if (threat >= 0.5f) {
                        ImmutableArmy army = ImmutableArmy.builder()
                                .position(point.toPoint2d())
                                .size(units.size())
                                .composition(Army.createComposition(composition))
                                .threat(threat)
                                .unitTags(entry.getValue().stream().map(UnitInPool::getTag).collect(Collectors.toSet()))
                                .build();
                        knownUnitsToClusters.putAll(army, entry.getValue());
                        enemyClusters.put(point, army);
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
            List<UnitType> fullArmyComposition = allEnemyUnits.stream().map(unitInPool -> unitInPool.unit().getType())
                    .collect(Collectors.toList());
            fullEnemyArmy = ImmutableArmy.builder()
                    .position(Optional.empty())
                    .composition(Army.createComposition(fullArmyComposition))
                    .threat(threatCalculator.calculateThreat(fullArmyComposition))
                    .size(fullArmyComposition.size())
                    .build();
            if (missingEnemyArmy.isPresent()) {
                fullEnemyArmy = fullEnemyArmy.plus(missingEnemyArmy.get());
            }
            // Army threat decays slowly, unless we can see it.
            if (maybeLargestEnemyArmy.flatMap(Army::position).isPresent() &&
                    observationInterface.getVisibility(maybeLargestEnemyArmy.flatMap(Army::position).get()) == Visibility.VISIBLE) {
                maybeLargestEnemyArmy = maybeLargestEnemyArmy.map(army -> army
                        .withSize(army.size() * 0.95f - 1.0f)
                        .withThreat(army.threat() * 0.95f - 1.0f));
            } else {
                maybeLargestEnemyArmy = maybeLargestEnemyArmy.map(army -> army
                        .withSize(army.size() * 0.999f)
                        .withThreat(army.threat() * 0.999f));
            }

            Set<Tag> seenTags = allEnemyUnits.stream().map(unitInPool ->
                    unitInPool.getTag()).collect(Collectors.toSet());;

            // If a unit was known on the last update, but not in this update, track it.
            previousKnownEnemyUnits.forEach(previousKnownEnemyUnit -> {
                if (!seenTags.contains(previousKnownEnemyUnit.getTag())) {
                    missingEnemyUnits.add(previousKnownEnemyUnit);
                    /*if (missingEnemyUnits.add(previousKnownEnemyUnit)) {
                        System.out.println("A " + previousKnownEnemyUnit.unit().getType() + " is missing.");
                    }*/
                } else {
                    /*if (missingEnemyUnits.contains(previousKnownEnemyUnit)) {
                        System.out.println("We found a " + previousKnownEnemyUnit.unit().getType() + " that went missing.");
                    }*/
                }
            });
            destroyedUnits.forEach(unitInPool -> {
                if (unitInPool.unit().getAlliance() == Alliance.ENEMY) {
                    if (missingEnemyUnits.remove(unitInPool.getTag())) {
                        System.out.println("Removed a destroyed " + unitInPool.unit().getType() + " from missing set");
                    }
                }
            });
            // Stop tracking missing units after 2 minutes or if we saw them on this tick.
            // Snapshots are forgotten after 30 seconds because the tag changes.
            missingEnemyUnits = missingEnemyUnits.stream()
                    .filter(unitInPool -> gameLoop < unitInPool.getLastSeenGameLoop() +
                            (unitInPool.unit().getDisplayType() == DisplayType.SNAPSHOT ? 30 : 120) * 22L)
                    .filter(unitInPool -> !seenTags.contains(unitInPool.getTag()))
                    .collect(Collectors.toSet());
            destroyedUnits.clear();
            // Construct a fake army to represent units that we've seen but can't see anymore.
            if (missingEnemyUnits.size() > 0) {
                List<UnitType> unknownArmyComposition = missingEnemyUnits.stream()
                        .filter(UnitInPool::isAlive)
                        .map(unitInPool -> unitInPool.unit().getType())
                        .collect(Collectors.toList());
                missingEnemyArmy = Optional.of(ImmutableArmy.builder()
                        .composition(Army.createComposition(unknownArmyComposition))
                        .position(Optional.empty())
                        .size(missingEnemyUnits.size())
                        .threat(threatCalculator.calculateThreat(unknownArmyComposition))
                        .build());
            } else {
                missingEnemyArmy = Optional.of(Army.empty());
            }
        }
    }

    @Override
    public List<Army> getMaybeEnemyArmies(Point2d point2d, float searchRadius) {
        return this.enemyClusters.values().stream()
                .filter(immutableArmy -> immutableArmy.position().isPresent())
                .filter(immutableArmy -> point2d.distance(immutableArmy.position().get()) <= searchRadius)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Army> getMissingEnemyArmy() {
        return missingEnemyArmy;
    }

    @Override
    public Army getOverallEnemyArmy() {
        return fullEnemyArmy;
    }

    private Collection<UnitType> getComposition(List<UnitInPool> unitInPools) {
        return unitInPools.stream().map(unitInPool -> unitInPool.unit().getType()).collect(Collectors.toList());
    }

    @Override
    public void debug(S2Agent agent) {
        this.enemyClusters.forEach((point, units) -> {
            agent.debug().debugSphereOut(point, 10f, Color.RED);
            agent.debug().debugTextOut("[" + units.threat() + "]", point, Color.WHITE, 10);
        });
        maybeLargestEnemyArmy.ifPresent(army -> {
            army.position().ifPresent(armyPosition -> {
                if (army.size() > 0) {
                    float z = agent.observation().terrainHeight(armyPosition);
                    Point point = Point.of(armyPosition.getX(), armyPosition.getY(), z);
                    agent.debug().debugSphereOut(point, 10f, Color.RED);
                    agent.debug().debugTextOut("Largest: [" + army.threat() + "]", point, Color.WHITE, 10);
                }
            });
        });
    }

    @Override
    public void onUnitDestroyed(UnitInPool unit) {
        this.destroyedUnits.add(unit);
    }
}
