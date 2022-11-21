package com.supalosa.bot.builds;

import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.google.common.base.Preconditions;
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
     * The unique ID of the output. Used to deduplicate tasks that are identical.
     * If an identical BuildOrderOutput is produced by a BuildOrder, the engine will only ever run it once, so
     * this field provides a way to differentiate it.
     */
    @Value.Default
    default int outputId() {
        return hashCode();
    }

    Optional<Ability> abilityToUse();

    Optional<UnitFilter> eligibleUnitTypes();
    Optional<Tag> specificUnit();

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

    @Value.Check
    default void check() {
        Preconditions.checkState(eligibleUnitTypes().isPresent() ^ specificUnit().isPresent(),
                "Eligible unit type or specific unit is required for BuildOrderOutput");
    }

    default String asHumanReadableString() {
        if (abilityToUse().isPresent()) {
            return (repeat() ? "*" : "") +
                    StringUtils.capitalize(abilityToUse().get().toString()
                            .replace("BUILD_", "")
                            .replace("TRAIN_", "")
                            .replace("_", " ")
                            .toLowerCase()) +
                    " - " +
                    eligibleUnitTypes().map(UnitFilter::unitType).map(Optional::toString).or(() -> specificUnit().map(Tag::toString));
        }
        if (performAttack().isPresent()) {
            return performAttack().get() ? "Start Attack" : "Cancel Attack";
        }
        return (repeat() ? "*" : "") + "Unknown(" + toString() + ")";
    }
}
