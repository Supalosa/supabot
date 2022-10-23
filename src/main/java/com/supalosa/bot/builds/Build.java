package com.supalosa.bot.builds;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.supalosa.bot.Constants;
import com.supalosa.bot.task.PlacementRules;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.task.terran.SwapAddonsTask;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.Validate;

import java.util.*;
import java.util.function.Supplier;

/**
 * Helper class to construct build order stages.
 */
public class Build {

    private List<SimpleBuildOrderStage> stages;

    private Build(List<SimpleBuildOrderStage> stages) {
        this.stages = stages;
    }

    public static class Builder {
        private List<SimpleBuildOrderStage> stages;
        private Units workerType;
        private Set<UnitType> townHallTypes;
        private Map<Ability, Integer> expectedCountOfAbility;

        private Builder(Units workerType, Set<UnitType> townHallTypes) {
            this.stages = new ArrayList<>();
            this.workerType = workerType;
            this.townHallTypes = townHallTypes;
            this.expectedCountOfAbility = new HashMap<>();
        }

        /**
         * Waits until the given supply value is reached until performing the next step.
         * Warning: This can cause blockages in the build order if not carefully managed, e.g. when supply blocked,
         * or tech requirements are not met due to bugs or losing structures.
         * In particular, it's not recommended to use this after initiating an attack.
         */
        public BuilderWithCondition atSupply(int supply) {
            return new BuilderWithCondition(this, new SimpleBuildOrderCondition.AtSupplyCondition(supply));
        }

        /**
         * Does not perform the next step until we have a certain amount of unit.
         */
        public UnitCountCondition after(int count) {
            return new UnitCountCondition(this, count);
        }

        class UnitCountCondition {
            private final int count;
            private final Builder builder;
            private UnitCountCondition(Builder builder, int count) {
                this.count = count;
                this.builder = builder;
            }
            public BuilderWithCondition of(UnitType type) {
                return new BuilderWithCondition(this.builder,
                        new SimpleBuildOrderCondition.AtUnitCountCondition(Map.of(type, count)));
            }
        }

        /**
         * Does not perform the next step until all previous steps have resolved.
         */
        public BuilderWithCondition then() {
            return new BuilderWithCondition(this,
                    new SimpleBuildOrderCondition.AtAbilityCountCondition(new HashMap<>(expectedCountOfAbility)));
        }

        // Mutators (which modify the previously added stage) go here.
        public Builder at(PlacementRules rules) {
            Validate.isTrue(stages.size() > 0);
            stages.set(stages.size() - 1, ImmutableSimpleBuildOrderStage.builder()
                    .from(stages.get(stages.size() - 1))
                    .placementRules(rules)
                    .build());
            return this;
        }

        public Builder near(UnitType unitType) {
            Validate.isTrue(stages.size() > 0);
            stages.set(stages.size() - 1, ImmutableSimpleBuildOrderStage.builder()
                    .from(stages.get(stages.size() - 1))
                    .placementRules(PlacementRules.inBaseNear(unitType))
                    .build());
            return this;
        }

        private Builder addStage(SimpleBuildOrderStage stage) {
            this.stages.add(stage);
            if (stage.ability().isPresent()) {
                this.expectedCountOfAbility.compute(stage.ability().get(), (k, v) -> v == null ? 1 : v + 1);
            }
            return this;
        }

        public List<SimpleBuildOrderStage> build() {
            return stages;
        }
    }

    public static class BuilderWithCondition {
        private Builder builder;
        private SimpleBuildOrderCondition condition;

        private BuilderWithCondition(Builder builder, SimpleBuildOrderCondition condition) {
            this.builder = builder;
            this.condition = condition;
        }

        // Generic stages go here.

        /**
         * Triggers all reserve army to go into the attacking army.
         * This can be called multiple times.
         */
        public Builder attack() {
            return builder.addStage(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .attack(true)
                    .build());
        }

        /**
         * Triggers all reserve army to constantly go into the attacking army.
         */
        public Builder repeatAttack() {
            return builder.addStage(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .attack(true)
                    .repeat(true)
                    .build());
        }

        /**
         * Builds a structure with the appropriate worker type for the race, in the player's base regions only.
         */
        public Builder buildStructure(Abilities ability) {
            return builder.addStage(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(builder.workerType))
                    .placementRules(PlacementRules.inBase())
                    .build());
        }

        public Builder useAbility(UnitType unitType, Abilities ability) {
            // Currently this is just a proxy for trainUnit, but in theory we might
            // have custom logic e.g swapping addons around.
            return trainUnit(unitType, ability);
        }

        public Builder trainUnit(UnitType unitType, Abilities ability) {
            return builder.addStage(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(unitType))
                    .build());
        }

        public Builder trainUnitUsingAddon(UnitType unitType, UnitType addonType, Abilities ability) {
            return builder.addStage(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(unitType))
                    .addonType(addonType)
                    .build());
        }

        public Builder setGasMiners(int miners) {
            return builder.addStage(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .gasMiners(miners)
                    .build());
        }

        public Builder expand() {
            Ability ability;
            switch (builder.workerType) {
                case TERRAN_SCV:
                    ability = Abilities.BUILD_COMMAND_CENTER;
                    break;
                case ZERG_DRONE:
                    ability = Abilities.BUILD_HATCHERY;
                    break;
                case PROTOSS_PROBE:
                    ability = Abilities.BUILD_NEXUS;
                    break;
                default:
                    throw new IllegalStateException("Invalid worker type, cannot determine expansion ability to use.");
            }
            return builder.addStage(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(builder.workerType))
                    .placementRules(PlacementRules.expansion())
                    .build());
        }

        public Builder startRepeatingUnit(UnitType unitType, Abilities ability) {
            return builder.addStage(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(unitType))
                    .repeat(true)
                    .build());
        }

        public Builder startRepeatingUnit(Set<UnitType> unitTypes, Abilities ability) {
            return builder.addStage(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(unitTypes))
                    .repeat(true)
                    .build());
        }

        public Builder startRepeatingUnitWithAddon(UnitType unitType, UnitType addonType, Abilities ability) {
            return builder.addStage(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .addonType(addonType)
                    .unitFilter(UnitFilter.mine(unitType))
                    .repeat(true)
                    .build());
        }

        public Builder startRepeatingStructure(Abilities ability) {
            this.builder.stages.add(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(builder.workerType))
                    .repeat(true)
                    .build());
            return builder;
        }

        public Builder startRepeatingUnitWithAddon(Set<UnitType> unitTypes, UnitType addonType, Abilities ability) {
            return builder.addStage(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .addonType(addonType)
                    .unitFilter(UnitFilter.mine(unitTypes))
                    .repeat(true)
                    .build());
        }

        // Race-specific helpers go here.
        public Builder buildSupplyDepot() {
            return buildStructure(Abilities.BUILD_SUPPLY_DEPOT);
        }

        public Builder morphOrbital() {
            return trainUnit(Units.TERRAN_COMMAND_CENTER, Abilities.MORPH_ORBITAL_COMMAND);
        }

        public Builder swapAddonsBetween(UnitType structure1, UnitType structure2) {
            return dispatchTask(() -> new SwapAddonsTask(structure1, structure2));
        }

        public Builder dispatchTask(Supplier<Task> task) {
            return builder.addStage(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .dispatchTask(task)
                    .build());
        }
    }

    public static Builder terran() {
        return new Builder(Units.TERRAN_SCV, Constants.TERRAN_CC_TYPES);
    }
}
