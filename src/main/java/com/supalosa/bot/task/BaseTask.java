package com.supalosa.bot.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public abstract class BaseTask implements Task {

    private List<Consumer<Optional<TaskResult>>> onStartedListeners;
    private List<Consumer<Optional<TaskResult>>> onCompleteListeners;
    private List<Consumer<Optional<TaskResult>>> onFailureListeners;

    protected BaseTask() {
        this.onStartedListeners = new ArrayList<>();
        this.onCompleteListeners = new ArrayList<>();
        this.onFailureListeners = new ArrayList<>();
    }

    @Override
    public Task onStarted(Consumer<Optional<TaskResult>> callback) {
        this.onStartedListeners.add(callback);
        return this;
    }

    @Override
    public Task onComplete(Consumer<Optional<TaskResult>> callback) {
        this.onCompleteListeners.add(callback);
        return this;
    }

    @Override
    public Task onFailure(Consumer<Optional<TaskResult>> callback) {
        this.onFailureListeners.add(callback);
        return this;
    }

    protected void onStarted() {
        this.onStartedListeners.forEach(listener -> listener.accept(getResult()));
    }

    protected void onComplete() {
        this.onCompleteListeners.forEach(listener -> listener.accept(getResult()));
    }

    protected void onFailure() {
        this.onFailureListeners.forEach(listener -> listener.accept(getResult()));
    }
}
