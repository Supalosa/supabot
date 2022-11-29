package com.supalosa.bot.analysis.utils;

import java.lang.reflect.Array;
import java.util.function.Function;
import java.util.function.Supplier;

public class InMemoryGrid<T> implements Grid<T> {

    private final Class<?> type;
    private final int width;
    private final int height;
    private final Supplier<T> defaultSupplier;
    private T[][] data;

    public InMemoryGrid(Class<?> type, int width, int height, Supplier<T> defaultValue) {
        this.type = type;
        this.data = createNewDataGrid(type, width, height);
        this.width = width;
        this.height = height;
        this.defaultSupplier = defaultValue;
    }

    private T[][] createNewDataGrid(Class<?> type, int width, int height) {
        Class<?> typeOneDimArray = Array.newInstance(type, 0).getClass();
        T[][] result = (T[][])Array.newInstance(typeOneDimArray, width);
        for (int i = 0; i < width; ++i) {
            result[i] = (T[])Array.newInstance(type, height);
        }
        return result;
    }

    public static <T> InMemoryGrid copyOf(Class<?> type, Grid<T> input, Supplier<T> defaultValue) {
        InMemoryGrid grid = new InMemoryGrid(type, input.getWidth(), input.getHeight(), defaultValue);
        for (int x = 0; x < input.getWidth(); ++x) {
            for (int y = 0; y < input.getHeight(); ++y) {
                if (input.isSet(x, y)) {
                    grid.set(x, y, input.get(x, y));
                }
            }
        }
        return grid;
    }

    public static <T, K> InMemoryGrid<K> copyOf(Class<?> type, Grid<T> input, Supplier<K> defaultValue, Function<T,K> adapter) {
        InMemoryGrid grid = new InMemoryGrid(type, input.getWidth(), input.getHeight(), defaultValue);
        for (int x = 0; x < input.getWidth(); ++x) {
            for (int y = 0; y < input.getHeight(); ++y) {
                if (input.isSet(x, y)) {
                    grid.set(x, y, adapter.apply(input.get(x, y)));
                }
            }
        }
        return grid;
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
        if (!isInBounds(x, y)) {
            return defaultSupplier.get();
        }
        T value = this.data[x][y];
        if (value == null) {
            return defaultSupplier.get();
        } else {
            return value;
        }
    }

    @Override
    public void set(int x, int y, T value) {
        if (isInBounds(x, y)) {
            this.data[x][y] = value;
        }
    }

    @Override
    public boolean isInBounds(int x, int y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    @Override
    public boolean isSet(int x, int y) {
        return this.data[x][y] != null;
    }

    @Override
    public void clear() {
        this.data = createNewDataGrid(type, width, height);
    }
}
