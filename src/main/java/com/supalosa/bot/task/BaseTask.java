package com.supalosa.bot.task;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public abstract class BaseTask implements Task {

    private List<Consumer<TaskResult>> onCompleteListeners;
    private List<Consumer<TaskResult>> onFailureListeners;

    protected BaseTask() {
        this.onCompleteListeners = new ArrayList<>();
        this.onFailureListeners = new ArrayList<>();
    }

    @Override
    public Task onComplete(Consumer<TaskResult> callback) {
        this.onCompleteListeners.add(callback);
        return this;
    }

    @Override
    public Task onFailure(Consumer<TaskResult> callback) {
        this.onFailureListeners.add(callback);
        return this;
    }

    protected void onComplete() {
        if (getResult().isPresent()) {
            this.onCompleteListeners.forEach(listener -> listener.accept(getResult().get()));
        }
    }

    protected void onFailure() {
        if (getResult().isPresent()) {
            this.onFailureListeners.forEach(listener -> listener.accept(getResult().get()));
        }
    }
}
