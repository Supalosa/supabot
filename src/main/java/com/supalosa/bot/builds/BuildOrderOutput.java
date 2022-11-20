package com.supalosa.bot.builds;

import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.supalosa.bot.placement.PlacementRules;
import com.supalosa.bot.task.Task;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.StringUtils;
import org.immutables.value.Value;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Command from a {@code BuildOrder} to the driver.
 */
@Value.Immutable
public interface BuildOrderOutput {

    /**
     * The originating hash code of the output. Used to deduplicate tasks that are identical.
     * If an identical BuildOrderOutput is produced by a BuildOrder, the engine will only run it once, so
     * this field provides a way to differentiate it.
     */
    int originatingHashCode();

    Optional<Ability> abilityToUse();

    Optional<UnitFilter> eligibleUnitTypes();
    Optional<UnitType> addonRequired();

    Optional<PlacementRules> placementRules();

    Optional<Boolean> performAttack();

    Optional<Supplier<Task>> dispatchTask();

    // TODO: this is more like 'is non-critical step' - repeat output is handled by continuing to show this BuildOrderOutput
    // even after it's been started.
    @Value.Default
    default boolean repeat() {
        return false;
    }

    default String asHumanReadableString() {
        if (abilityToUse().isPresent()) {
            return (repeat() ? "*" : "") + StringUtils.capitalize(abilityToUse().get().toString()
                            .replace("BUILD_", "")
                            .replace("TRAIN_", "")
                            .replace("_", " ")
                            .toLowerCase());
        }
        if (performAttack().isPresent()) {
            return performAttack().get() ? "Start Attack" : "Cancel Attack";
        }
        return (repeat() ? "*" : "") + "Unknown(" + toString() + ")";
    }
}
