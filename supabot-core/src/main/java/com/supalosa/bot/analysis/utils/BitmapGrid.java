package com.supalosa.bot.analysis.utils;

import org.apache.commons.lang3.NotImplementedException;

import java.awt.image.BufferedImage;

public class BitmapGrid implements Grid<Integer> {

    private final BufferedImage image;

    public BitmapGrid(BufferedImage image) {
        this.image = image;
    }

    @Override
    public int getWidth() {
        return image.getWidth();
    }

    @Override
    public int getHeight() {
        return image.getHeight();
    }

    @Override
    public Integer get(int x, int y) {
        return image.getRGB(x, y);
    }

    @Override
    public void set(int x, int y, Integer value) {
        image.setRGB(x, y, value);
    }

    @Override
    public boolean isInBounds(int x, int y) {
        return x >= 0 && x < image.getWidth() && y >= 0 && y < image.getHeight();
    }

    @Override
    public boolean isSet(int x, int y) {
        return this.isInBounds(x, y);
    }

    @Override
    public void clear() {
        throw new NotImplementedException("Clear is not implemented for bitmap grid.");
    }
}
