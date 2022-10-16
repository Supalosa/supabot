package com.supalosa.bot.engagement;

import com.github.ocraft.s2client.protocol.data.UnitType;

import java.util.Collection;
import java.util.Map;

public interface ThreatCalculator {

    /**
     * Returns the estimated power of the given list of units, givem the playstyle of this implementation.
     */
    double calculatePower(Collection<UnitType> myComposition);

    /**
     * Returns the estimated power of the given list of units, givem the playstyle of this implementation.
     */
    double calculatePower(Map<UnitType, Integer> myComposition);

    /**
     * Returns the estimated threat of the given list of units against the playstyle of this implementation.
     */
    double calculateThreat(Collection<UnitType> enemyComposition);

    /**
     * Returns the estimated threat of the given list of units against the playstyle of this implementation.
     */
    double calculateThreat(Map<UnitType, Integer> enemyComposition);
}
