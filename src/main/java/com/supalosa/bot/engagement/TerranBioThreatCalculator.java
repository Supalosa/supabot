package com.supalosa.bot.engagement;

import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.data.Upgrade;
import com.github.ocraft.s2client.protocol.data.Upgrades;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TerranBioThreatCalculator implements ThreatCalculator {

    @Override
    public double calculatePower(Collection<UnitType> myComposition, Set<Upgrade> upgrades) {
        return calculatePower(listToMap(myComposition), upgrades);
    }

    @Override
    public double calculatePower(Map<UnitType, Integer> myComposition, Set<Upgrade> upgrades) {
        boolean hasStim = upgrades.contains(Upgrades.STIMPACK);
        return myComposition.entrySet().stream().mapToDouble(entry -> {
            UnitType unitType = entry.getKey();
            int amount = entry.getValue();
            if (unitType instanceof Units) {
                switch ((Units)unitType) {
                    case TERRAN_GHOST:
                        return amount * 4.0;
                    case TERRAN_MEDIVAC:
                    case TERRAN_LIBERATOR_AG:
                    case TERRAN_WIDOWMINE_BURROWED:
                        return amount * 3.0;
                    case TERRAN_VIKING_FIGHTER:
                        return amount * 2.5;
                    case TERRAN_MARAUDER:
                        return amount * (hasStim ? 2.5 : 1.5);
                    case TERRAN_VIKING_ASSAULT:
                        return amount * 2.0;
                    case TERRAN_LIBERATOR:
                    case TERRAN_WIDOWMINE:
                        return amount * 1.5;
                    case TERRAN_MARINE:
                    default:
                        return amount * (hasStim ? 1.5 : 1.0);
                    case TERRAN_SCV:
                        return 0.1;
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
                    case TERRAN_SIEGE_TANK_SIEGED:
                        return amount * 33.0;
                    case ZERG_LURKER_MP_BURROWED:
                        return amount * 25.0;
                    case ZERG_ULTRALISK:
                    case PROTOSS_MOTHERSHIP:
                    case PROTOSS_CARRIER:
                    case TERRAN_PLANETARY_FORTRESS:
                    case ZERG_INFESTOR: // TODO - based on energy
                    case PROTOSS_HIGH_TEMPLAR:
                        return amount * 10.0;
                    case TERRAN_THOR:
                    case ZERG_BROODLORD:
                    case PROTOSS_IMMORTAL:
                    case ZERG_BANELING:
                    case PROTOSS_PHOTON_CANNON:
                    case TERRAN_BUNKER:
                    case ZERG_SPORE_CRAWLER:
                    case PROTOSS_ARCHON:
                        return amount * 5.0;
                    case ZERG_QUEEN:
                        return amount * 3.5;
                    case TERRAN_MARAUDER:
                    case ZERG_ROACH:
                    case TERRAN_SIEGE_TANK:
                    case TERRAN_VIKING_ASSAULT:
                        return amount * 3.0;
                    case PROTOSS_ZEALOT:
                    case PROTOSS_STALKER:
                    case TERRAN_MARINE:
                    case PROTOSS_PHOENIX:
                        return amount * 2.0;
                    case ZERG_ZERGLING:
                    case ZERG_DRONE:
                    case TERRAN_SCV:
                    case PROTOSS_PROBE:
                    case ZERG_BANELING_COCOON:
                    case ZERG_RAVAGER_COCOON:
                    case ZERG_BROODLORD_COCOON:
                    case ZERG_CORRUPTOR:
                    case TERRAN_VIKING_FIGHTER:
                        return amount * 0.5;
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
