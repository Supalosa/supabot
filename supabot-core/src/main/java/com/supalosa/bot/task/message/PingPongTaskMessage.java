package com.supalosa.bot.task.message;

public class PingPongTaskMessage implements TaskMessage {

    private final long pingSentAt;

    public PingPongTaskMessage(long sentAt) {
        this.pingSentAt = sentAt;
    }

    public long getPingSentAt() {
        return this.pingSentAt;
    }
}
