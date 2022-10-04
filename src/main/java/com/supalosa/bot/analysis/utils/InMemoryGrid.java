package com.supalosa.bot.analysis.utils;

import java.lang.reflect.Array;
import java.util.function.Supplier;

public class InMemoryGrid<T> implements Grid<T> {

    private T[][] data;
    private int width;
    private int height;
    private Supplier<T> defaultSupplier;

    public InMemoryGrid(Class<?> type, int width, int height, Supplier<T> defaultValue) {
        Class<?> typeOneDimArray = Array.newInstance(type, 0).getClass();
        this.data = (T[][])Array.newInstance(typeOneDimArray, width);
        for (int i = 0; i < width; ++i) {
            this.data[i] = (T[])Array.newInstance(type, height);
        }
        this.width = width;
        this.height = height;
        this.defaultSupplier = defaultValue;
    }

    @Override
    public int getWidth() {
        return this.width;
    }

    @Override
    public int getHeight() {
        return this.height;
    }

    @Override
    public T get(int x, int y) {
        T value = this.data[x][y];
        if (value == null) {
            return defaultSupplier.get();
        } else {
            return value;
        }
    }

    @Override
    public void set(int x, int y, T value) {
        this.data[x][y] = value;
    }

    @Override
    public boolean isInBounds(int x, int y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }
}
