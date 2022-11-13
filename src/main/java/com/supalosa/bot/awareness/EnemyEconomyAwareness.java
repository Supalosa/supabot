package com.supalosa.bot.awareness;

import java.util.Optional;

public interface EnemyEconomyAwareness {

    /**
     * Returns the estimated enemy mineral income per minute.
     */
    Estimation estimatedEnemyMineralIncome();

    /**
     * Returns the estimated number of enemy bases.
     */
    Estimation estimatedEnemyBases();

    /**
     * Returns the potential expansion that we have the least confidence on.
     */
    Optional<RegionData> getLeastConfidentEnemyExpansion();

    /**
     * Returns true if we think our economy is stronger than the enemies'.
     */
    boolean isMyEconomyStronger();
}
