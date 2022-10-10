package com.supalosa.bot;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.UnitTypeData;

import java.util.Map;
import java.util.Optional;

public class GameData {

    private final ObservationInterface observationInterface;
    private Map<UnitType, UnitTypeData> typeData = null;

    public GameData(ObservationInterface observationInterface) {
        this.observationInterface = observationInterface;
    }

    // Do not call until game has started.
    private Map<UnitType, UnitTypeData> getOrInitUnitTypeData() {
        if (this.typeData == null) {
            this.typeData = observationInterface.getUnitTypeData(true);
        }
        return this.typeData;
    }

    public Optional<Integer> getUnitMineralCost(UnitType unitType) {
        UnitTypeData unitTypeData = getOrInitUnitTypeData().get(unitType);
        if (unitTypeData == null) {
            return Optional.empty();
        } else {
            return unitTypeData.getMineralCost();
        }
    }

    public Optional<Integer> getUnitVespeneCost(UnitType unitType) {
        UnitTypeData unitTypeData = getOrInitUnitTypeData().get(unitType);
        if (unitTypeData == null) {
            return Optional.empty();
        } else {
            return unitTypeData.getVespeneCost();
        }
    }
}
