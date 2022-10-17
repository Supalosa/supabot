package com.supalosa.bot.task.message;

import org.apache.commons.lang3.NotImplementedException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Special implementation of TaskPromise that does not allow for .get() (as we are running
 * single threaded).
 */
public class TaskPromise extends CompletableFuture<TaskMessageResponse> {

    @Override
    public TaskMessageResponse get() throws InterruptedException, ExecutionException {
        throw new NotImplementedException("Do not use .get(), use listener functions like thenAccept.");
    }

}
