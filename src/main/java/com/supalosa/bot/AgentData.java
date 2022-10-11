package com.supalosa.bot;

import com.github.ocraft.s2client.api.S2Client;
import com.supalosa.bot.placement.StructurePlacementCalculator;

import java.util.Optional;

public interface AgentData {

    Optional<StructurePlacementCalculator> structurePlacementCalculator();

    GameData gameData();
}
