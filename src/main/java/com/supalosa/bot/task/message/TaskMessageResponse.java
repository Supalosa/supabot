package com.supalosa.bot.task.message;

import com.supalosa.bot.task.Task;

/**
 * A response from a message sent from one task to another.
 */
public interface TaskMessageResponse {
    Task getRespondingTask();
    boolean isSuccess();
}
