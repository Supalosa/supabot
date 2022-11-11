package com.supalosa.bot.engagement;

import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.data.Upgrade;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class WorkerDefenceThreatCalculator implements ThreatCalculator {

    @Override
    public double calculatePower(Collection<UnitType> myComposition, Set<Upgrade> upgrades) {
        return calculatePower(listToMap(myComposition), upgrades);
    }

    @Override
    public double calculatePower(Map<UnitType, Integer> myComposition, Set<Upgrade> upgrades) {
        return myComposition.entrySet().stream().mapToDouble(entry -> {
            UnitType unitType = entry.getKey();
            int amount = entry.getValue();
            if (unitType instanceof Units) {
                switch ((Units)unitType) {
                    case TERRAN_SCV:
                    case PROTOSS_PHOENIX:
                    case ZERG_DRONE:
                        return amount * 1.25;
                    default:
                        return amount * 1.0;
                }
            } else {
                return amount * 1.5;
            }
        }).sum();
    }

    @Override
    public double calculateThreat(Collection<UnitType> enemyComposition) {
        return calculateThreat(listToMap(enemyComposition));
    }

    @Override
    public double calculateThreat(Map<UnitType, Integer> enemyComposition) {
        return enemyComposition.entrySet().stream().mapToDouble(entry -> {
            UnitType unitType = entry.getKey();
            int amount = entry.getValue();
            if (unitType instanceof Units) {
                switch ((Units)unitType) {
                    case TERRAN_SCV:
                    case PROTOSS_PHOENIX:
                    case ZERG_DRONE:
                        return amount * 1.25;
                    default:
                        return amount * 1.5;
                }
            } else {
                return amount * 1.5;
            }
        }).sum();
    }

    private Map<UnitType, Integer> listToMap(Collection<UnitType> composition) {
        Map<UnitType, Integer> result = new HashMap<>();
        composition.forEach(unitType -> {
            result.put(unitType, result.getOrDefault(unitType, 0) + 1);
        });
        return result;
    }
}
