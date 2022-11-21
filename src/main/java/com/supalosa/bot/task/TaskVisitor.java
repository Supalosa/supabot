package com.supalosa.bot.task;

public interface TaskVisitor<T> {
    void visit(Task armyTask);

    T getResult();
}
