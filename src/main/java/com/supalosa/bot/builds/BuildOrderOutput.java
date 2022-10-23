package com.supalosa.bot.builds;

import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.task.PlacementRules;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.StringUtils;
import org.immutables.value.Value;

import java.util.Optional;
import java.util.Set;

/**
 * Command from a {@code BuildOrder} to the driver.
 */
@Value.Immutable
public interface BuildOrderOutput {

    Optional<Ability> abilityToUse();

    Optional<UnitFilter> eligibleUnitTypes();
    Optional<UnitType> addonRequired();

    Optional<PlacementRules> placementRules();

    Optional<Boolean> performAttack();

    /*
    Optional<Unit> unitToUse();

    Optional<Unit> unitToTarget();
    */
    default String asHumanReadableString() {
        if (performAttack().isPresent()) {
            return performAttack().get() ? "Start Attack" : "Cancel Attack";
        }
        if (abilityToUse().isPresent()) {
            return StringUtils.capitalize(abilityToUse().get().toString()
                            .replace("BUILD_", "")
                            .replace("TRAIN_", "")
                            .replace("_", "")
                            .toLowerCase());
        }
        return "Unknown(" + toString() + ")";
    }
}
