package com.supalosa.bot;

import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.awareness.MapAwareness;
import com.supalosa.bot.placement.StructurePlacementCalculator;

import java.util.Optional;

public interface AgentData {

    Optional<StructurePlacementCalculator> structurePlacementCalculator();

    GameData gameData();

    Optional<AnalysisResults> mapAnalysis();

    MapAwareness mapAwareness();
}
