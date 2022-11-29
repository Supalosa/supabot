package com.supalosa.bot.placement;

public enum PlacementRegion {
    /**
     * Place it in any player owned region.
     */
    PLAYER_BASE_ANY(true),
    /**
     * Place it on the border of the player base.
     */
    PLAYER_BASE_BORDER(true),
    /**
     * Place it on the centre of the player base.
     */
    PLAYER_BASE_CENTRE(true),
    /**
     * Place it on an expansion only.
     */
    EXPANSION(true),
    /**
     * Place it on a free gas structure only.
     */
    FREE_VESPENE_GEYSER(true),
    /**
     * Place it on the terran ramp.
     */
    MAIN_RAMP_SUPPLY_DEPOT_1(true),
    /**
     * Place it on the terran ramp.
     */
    MAIN_RAMP_SUPPLY_DEPOT_2(true),
    /**
     * Place it on the terran ramp with space for the addon.
     */
    MAIN_RAMP_BARRACKS_WITH_ADDON(true),
    /**
     * Place it at the natural choke point, or else the main base choke point.
     */
    NATURAL_CHOKE_POINT(false);

    private boolean playerBase;

    PlacementRegion(boolean playerBase) {
        this.playerBase = playerBase;
    }

    /**
     * True if the worker must be pulled from a player base.
     */
    public boolean isPlayerBase() {
        return this.playerBase;
    }
}
