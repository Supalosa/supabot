package com.supalosa.bot;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.AbilityData;
import com.github.ocraft.s2client.protocol.data.UnitType;
import com.github.ocraft.s2client.protocol.data.UnitTypeData;

import java.util.Map;
import java.util.Optional;

public class GameData {

    private final ObservationInterface observationInterface;
    private Map<UnitType, UnitTypeData> typeData = null;
    private Map<Ability, AbilityData> abilityData = null;

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
    private Map<Ability, AbilityData> getOrInitAbilityData() {
        if (this.abilityData == null) {
            this.abilityData = observationInterface.getAbilityData(true);
        }
        return this.abilityData;
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
    public Optional<UnitTypeData> getUnitTypeData(UnitType unitType) {
        UnitTypeData unitTypeData = getOrInitUnitTypeData().get(unitType);
        if (unitTypeData == null) {
            return Optional.empty();
        } else {
            return Optional.of(unitTypeData);
        }
    }

    public Optional<Float> getAbilityRadius(Ability ability) {
        AbilityData abilityData = getOrInitAbilityData().get(ability);
        if (abilityData == null) {
            return Optional.empty();
        } else {
            return abilityData.getFootprintRadius();
        }
    }
}
