package com.supalosa.bot;

import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.supalosa.bot.awareness.Army;
import com.supalosa.bot.production.ImmutableUnitTypeRequest;
import com.supalosa.bot.production.UnitTypeRequest;
import com.supalosa.bot.task.army.ArmyTask;

import java.util.ArrayList;
import java.util.List;

public class TerranMarineDropCompositionChooser implements CompositionChooser {

    private List<UnitTypeRequest> desiredComposition = new ArrayList<>();
    private long desiredCompositionUpdatedAt = Long.MIN_VALUE;

    public TerranMarineDropCompositionChooser() {
        desiredComposition.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARINE)
                .productionAbility(Abilities.TRAIN_MARINE)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .amount(8)
                .build()
        );
        desiredComposition.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MARAUDER)
                .productionAbility(Abilities.TRAIN_MARAUDER)
                .producingUnitType(Units.TERRAN_BARRACKS)
                .needsTechLab(true)
                .amount(4)
                .build()
        );
        desiredComposition.add(ImmutableUnitTypeRequest.builder()
                .unitType(Units.TERRAN_MEDIVAC)
                .productionAbility(Abilities.TRAIN_MEDIVAC)
                .producingUnitType(Units.TERRAN_STARPORT)
                .amount(2)
                .build()
        );
    }

    @Override
    public void onStep(AgentWithData agentWithData) {
    }

    @Override
    public List<UnitTypeRequest> getRequestedUnits() {
        return desiredComposition;
    }
}
