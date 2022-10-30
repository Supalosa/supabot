package com.supalosa.bot.task.army;

public enum AggressionState {
    /**
     * Army is in an aggressive attack mode.
     */
    ATTACKING(1),
    /**
     * Army is in a defensive mode awaiting reinforcements.
     */
    REGROUPING(1),
    /**
     * Army is disengaging from a fight.
     */
    RETREATING(1),
    /**
     * Army is moving between locations.
     */
    IDLE(11);

    private int updateInterval;

    AggressionState(int updateInterval) {
        this.updateInterval = updateInterval;
    }

    public int getUpdateInterval() {
        return updateInterval;
    }
}
