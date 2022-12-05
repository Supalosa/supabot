package com.supalosa.bot;

import com.supalosa.bot.production.UnitRequester;
import com.supalosa.bot.production.UnitTypeRequest;

import java.util.List;

public interface CompositionChooser extends UnitRequester {

    void onStep(AgentWithData agentWithData);
}
