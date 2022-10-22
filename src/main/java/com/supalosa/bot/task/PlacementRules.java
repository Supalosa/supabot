package com.supalosa.bot.task;

import org.immutables.value.Value;

@Value.Immutable
public interface PlacementRules {
    enum Region {
        /**
         * Place it in any player owned region.
         */
        ANY_PLAYER_BASE,
        /**
         * Place it on an expansion only.
         */
        EXPANSION
    }

    Region regionType();

    int maxVariation();

    static PlacementRules expansion() {
        return ImmutablePlacementRules.builder().regionType(Region.EXPANSION).maxVariation(0).build();
    }

    static PlacementRules inBase() {
        return ImmutablePlacementRules.builder().regionType(Region.ANY_PLAYER_BASE).maxVariation(20).build();
    }

}
