package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Abilities;

public interface BuildOrder {
    Abilities getNextAbility(ObservationInterface observationInterface);
}
