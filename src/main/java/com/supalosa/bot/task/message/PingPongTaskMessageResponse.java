package com.supalosa.bot.task.message;

import com.supalosa.bot.task.Task;

public class PingPongTaskMessageResponse implements TaskMessageResponse {

    private final long pingReceivedAt;
    private final long pingRepliedAt;
    private final Task respondingTask;

    public PingPongTaskMessageResponse(Task respondingTask, long receivedAt, long repliedAt) {
        this.respondingTask = respondingTask;
        this.pingReceivedAt = receivedAt;
        this.pingRepliedAt = repliedAt;
    }

    @Override
    public Task getRespondingTask() {
        return respondingTask;
    }

    @Override
    public boolean isSuccess() {
        return true;
    }

    public long getPingReceivedAt() {
        return pingReceivedAt;
    }

    public long getPingRepliedAt() {
        return pingRepliedAt;
    }
}
