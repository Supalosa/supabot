package com.supalosa.bot.builds;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.supalosa.bot.AgentData;

public interface SimpleBuildOrderCondition {

    boolean accept(SimpleBuildOrder buildOrder, ObservationInterface observationInterface);

    class AtSupplyCondition implements SimpleBuildOrderCondition {

        private final int atSupply;

        AtSupplyCondition(int atSupply) {
            this.atSupply = atSupply;
        }

        @Override
        public boolean accept(SimpleBuildOrder buildOrder, ObservationInterface observationInterface) {
            return observationInterface.getFoodUsed() >= atSupply;
        }
    }
}
