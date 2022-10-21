package com.supalosa.bot.builds;

import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.supalosa.bot.task.PlacementRules;
import com.supalosa.bot.utils.UnitFilter;
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

    /*
    Optional<Unit> unitToUse();

    Optional<Unit> unitToTarget();
    */
}
