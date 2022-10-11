package com.supalosa.bot.analysis.utils;

public interface Grid<T> {
    int getWidth();
    int getHeight();
    T get(int x, int y);
    void set(int x, int y, T value);
    boolean isInBounds(int x, int y);
    boolean isSet(int x, int y);
}
