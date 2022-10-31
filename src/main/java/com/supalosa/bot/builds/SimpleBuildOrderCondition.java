package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.GameData;
import com.supalosa.bot.strategy.StrategicObservation;

import java.util.HashMap;
import java.util.Map;

public interface SimpleBuildOrderCondition {

    /**
     * Returns whether the condition (and associated stage) is blocking or not.
     * A non-blocking task will be added to the front of the stage list as a blocking task once the condition is met.
     */
    default boolean isBlocking() {
        return true;
    }

    boolean accept(SimpleBuildOrder buildOrder, ObservationInterface observationInterface, AgentWithData gameData);

    class AtSupplyCondition implements SimpleBuildOrderCondition {

        private final int atSupply;

        AtSupplyCondition(int atSupply) {
            this.atSupply = atSupply;
        }

        @Override
        public boolean accept(SimpleBuildOrder buildOrder, ObservationInterface observationInterface, AgentWithData gameData) {
            return observationInterface.getFoodUsed() >= atSupply;
        }

        @Override
        public String toString() {
            return "at " + atSupply + " supply";
        }
    }

    class AtAbilityCountCondition implements SimpleBuildOrderCondition {

        private final Map<Ability, Integer> expectedCount;

        AtAbilityCountCondition(Map<Ability, Integer> expectedCount) {
            this.expectedCount = expectedCount;
        }

        @Override
        public boolean accept(SimpleBuildOrder buildOrder, ObservationInterface observationInterface, AgentWithData gameData) {
            for (Map.Entry<Ability, Integer> entry : buildOrder.getAbilitiesUsedCount().entrySet()) {
                Ability ability = entry.getKey();
                int expectedCountOfAbility = expectedCount.getOrDefault(ability, 0);
                if (entry.getValue() < expectedCountOfAbility) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("after");
            expectedCount.forEach((ability, count) -> {
                builder.append(" " + count + "x" + ability);
            });
            return builder.toString();
        }
    }

    class AtUnitCountCondition implements SimpleBuildOrderCondition {
        private final Map<UnitType, Integer> expectedCount;
        AtUnitCountCondition(Map<UnitType, Integer> expectedCount) {
            this.expectedCount = expectedCount;
        }

        @Override
        public boolean accept(SimpleBuildOrder buildOrder, ObservationInterface observationInterface, AgentWithData gameData) {
            Map<UnitType, Integer> currentCount = new HashMap<>();
            observationInterface.getUnits(Alliance.SELF).forEach(unitInPool -> {
                Unit unit = unitInPool.unit();
                if (unit != null && expectedCount.containsKey(unit.getType())) {
                    currentCount.compute(unit.getType(), (k, v) -> v == null ? 1 : v + 1);
                }
            });
            for (Map.Entry<UnitType, Integer> entry : expectedCount.entrySet()) {
                UnitType unit = entry.getKey();
                int expectedCountOfUnit = expectedCount.getOrDefault(unit, 0);
                if (entry.getValue() < expectedCountOfUnit) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("after");
            expectedCount.forEach((unitType, count) -> {
                builder.append(" " + count + "x" + unitType);
            });
            return builder.toString();
        }
    }

    class OrCondition implements SimpleBuildOrderCondition {
        private final SimpleBuildOrderCondition condition1;
        private final SimpleBuildOrderCondition condition2;
        OrCondition(SimpleBuildOrderCondition condition1, SimpleBuildOrderCondition condition2) {
            this.condition1 = condition1;
            this.condition2 = condition2;
        }
        @Override
        public boolean accept(SimpleBuildOrder buildOrder, ObservationInterface observationInterface,
                              AgentWithData gameData) {
            return condition1.accept(buildOrder, observationInterface, gameData) ||
                    condition2.accept(buildOrder, observationInterface, gameData);
        }

        @Override
        public String toString() {
            return "(" + condition1 + " or " + condition2 + ")";
        }
    }

    /**
     * Triggers the condition when the StrategicObservation is matched.
     */
    class StrategicObservationCondition implements SimpleBuildOrderCondition {

        private final Class<? extends StrategicObservation> observation;

        public StrategicObservationCondition(Class<? extends StrategicObservation> observation) {
            this.observation = observation;
        }

        @Override
        public boolean accept(SimpleBuildOrder buildOrder, ObservationInterface observationInterface,
                              AgentWithData data) {
            return data.strategyTask().hasSeenObservation(observation);
        }

        @Override
        public boolean isBlocking() {
            return false;
        }

        @Override
        public String toString() {
            return "Scouted " + observation.getSimpleName();
        }
    }
}
