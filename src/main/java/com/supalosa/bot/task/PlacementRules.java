package com.supalosa.bot.task;

import org.immutables.value.Value;

@Value.Immutable
public interface PlacementRules {
    enum Region {
        /**
         * Place it in any player owned region.
         */
        ANY_PLAYER_OWNED,
        /**
         * Place it on an expansion only.
         */
        EXPANSION
    }

    Region regionType();

    int maxVariation();

}
