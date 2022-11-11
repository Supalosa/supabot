package com.supalosa.bot.engagement;

import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Upgrade;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public interface ThreatCalculator {

    /**
     * Returns the estimated power of the given list of units, given the playstyle of this implementation.
     */
    double calculatePower(Collection<UnitType> myComposition, Set<Upgrade> upgrades);

    /**
     * Returns the estimated power of the given list of units, given the playstyle of this implementation.
     */
    double calculatePower(Map<UnitType, Integer> myComposition, Set<Upgrade> upgrades);

    /**
     * Returns the estimated threat of the given list of units against the playstyle of this implementation.
     */
    double calculateThreat(Collection<UnitType> enemyComposition);

    /**
     * Returns the estimated threat of the given list of units against the playstyle of this implementation.
     */
    double calculateThreat(Map<UnitType, Integer> enemyComposition);
}
