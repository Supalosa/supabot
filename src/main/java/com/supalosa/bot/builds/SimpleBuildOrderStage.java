package com.supalosa.bot.builds;

import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.supalosa.bot.Constants;
import com.supalosa.bot.placement.PlacementRules;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.Validate;
import org.immutables.value.Value;

import java.util.Optional;
import java.util.function.Supplier;

@Value.Immutable
public abstract class SimpleBuildOrderStage {

    /**
     * A {@code SimpleBuildOrderCondition} that must be satisfied before this build order stage will be presented
     * to the runner.
     */
    @Value.Parameter
    public abstract SimpleBuildOrderCondition trigger();

    @Value.Parameter
    public abstract Optional<Ability> ability();

    public abstract Optional<PlacementRules> placementRules();

    @Value.Default
    public boolean stopWorkerProduction() {
        return false;
    }

    /**
     * Triggers all reserve army to go into the attacking army.
     * This can be called multiple times.
     */
    public abstract Optional<Boolean> attack();

    @Value.Default
    public boolean repeat() {
        return false;
    }

    @Value.Default
    public boolean stopRepeating() {
        return false;
    }

    @Value.Parameter
    public abstract Optional<UnitFilter> unitFilter();

    public abstract Optional<Integer> gasMiners();

    public abstract Optional<Supplier<Task>> dispatchTask();

    /**
     * Required because addons are tag-only.
     */
    @Value.Parameter
    public abstract Optional<UnitType> addonType();

    @Value.Check
    protected void check() {
        Validate.isTrue(addonType().isEmpty() ||
                (Constants.TERRAN_ADDON_TYPES.contains(addonType().get())),
                "Only tech lab/reactor types are supported",
                addonType());
        Validate.isTrue(ability().isPresent() ||
                        gasMiners().isPresent() ||
                        attack().isPresent() ||
                        dispatchTask().isPresent(),
                "Order does not do anything.");
    }

    public BuildOrderOutput toBuildOrderOutput() {
        // one might think that SimpleBuildOrderStage should just implement BuildOrderOutput...
        return ImmutableBuildOrderOutput.builder()
                .outputId(hashCode())
                .abilityToUse(ability())
                .eligibleUnitTypes(unitFilter())
                .addonRequired(addonType())
                .placementRules(placementRules())
                .performAttack(attack())
                .dispatchTask(dispatchTask())
                .repeat(repeat())
                .build();
    }
}
