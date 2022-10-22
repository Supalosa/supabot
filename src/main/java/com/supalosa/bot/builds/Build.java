package com.supalosa.bot.builds;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.supalosa.bot.Constants;
import com.supalosa.bot.task.PlacementRules;
import com.supalosa.bot.utils.UnitFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

        private Builder(Units workerType, Set<UnitType> townHallTypes) {
            this.stages = new ArrayList<>();
            this.workerType = workerType;
            this.townHallTypes = townHallTypes;
        }

        public BuilderWithCondition atSupply(int supply) {
            return new BuilderWithCondition(this, new SimpleBuildOrderCondition.AtSupplyCondition(supply));
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
         * Builds a structure with the appropriate worker type for the race, in the player's base regions only.
         */
        public Builder buildStructure(Abilities ability) {
            this.builder.stages.add(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(builder.workerType))
                    .placementRules(PlacementRules.inBase())
                    .build());
            return builder;
        }

        public Builder useAbility(UnitType unitType, Abilities ability) {
            // Currently this is just a proxy for trainUnit, but in theory we might
            // have custom logic e.g swapping addons around.
            return trainUnit(unitType, ability);
        }

        public Builder trainUnit(UnitType unitType, Abilities ability) {
            this.builder.stages.add(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(unitType))
                    .build());
            return builder;
        }

        public Builder trainUnitUsingAddon(UnitType unitType, UnitType addonType, Abilities ability) {
            this.builder.stages.add(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(unitType))
                    .addonType(addonType)
                    .build());
            return builder;
        }

        public Builder setGasMiners(int miners) {
            this.builder.stages.add(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .gasMiners(miners)
                    .build());
            return builder;
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
            this.builder.stages.add(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(builder.workerType))
                    .placementRules(PlacementRules.expansion())
                    .build());
            return builder;
        }

        public Builder startRepeatingUnit(UnitType unitType, Abilities ability) {
            this.builder.stages.add(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(unitType))
                    .repeat(true)
                    .build());
            return builder;
        }

        public Builder startRepeatingUnit(Set<UnitType> unitTypes, Abilities ability) {
            this.builder.stages.add(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .unitFilter(UnitFilter.mine(unitTypes))
                    .repeat(true)
                    .build());
            return builder;
        }

        public Builder startRepeatingUnitWithAddon(UnitType unitType, UnitType addonType, Abilities ability) {
            this.builder.stages.add(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .addonType(addonType)
                    .unitFilter(UnitFilter.mine(unitType))
                    .repeat(true)
                    .build());
            return builder;
        }

        public Builder startRepeatingUnitWithAddon(Set<UnitType> unitTypes, UnitType addonType, Abilities ability) {
            this.builder.stages.add(ImmutableSimpleBuildOrderStage.builder()
                    .trigger(this.condition)
                    .ability(ability)
                    .addonType(addonType)
                    .unitFilter(UnitFilter.mine(unitTypes))
                    .repeat(true)
                    .build());
            return builder;
        }

        // Race-specific helpers go here.
        public Builder buildSupplyDepot() {
            return buildStructure(Abilities.BUILD_SUPPLY_DEPOT);
        }

        public Builder morphOrbital() {
            return trainUnit(Units.TERRAN_COMMAND_CENTER, Abilities.MORPH_ORBITAL_COMMAND);
        }
    }

    public static Builder terran() {
        return new Builder(Units.TERRAN_SCV, Constants.TERRAN_CC_TYPES);
    }
}
