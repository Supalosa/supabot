package com.supalosa.bot.builds;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.supalosa.bot.Constants;
import com.supalosa.bot.utils.UnitFilter;
import org.apache.commons.lang3.Validate;
import org.immutables.value.Value;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

@Value.Immutable
public abstract class SimpleBuildOrderStage {
    @Value.Parameter
    public abstract int supplyTrigger();

    @Value.Parameter
    public abstract Optional<Ability> ability();

    @Value.Default
    public boolean expand() {
        return false;
    }

    @Value.Default
    public boolean stopWorkerProduction() {
        return false;
    }

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
        Validate.isTrue(ability().isPresent() || gasMiners().isPresent() || expand(),
                "Order does not do anything.");
    }
}
