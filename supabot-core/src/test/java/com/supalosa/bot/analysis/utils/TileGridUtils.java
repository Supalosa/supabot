package com.supalosa.bot.analysis.utils;

import com.supalosa.bot.analysis.Tile;
import org.apache.commons.lang3.StringUtils;

public class TileGridUtils {
    /**
     * Return a grid represented by the following:
     * ' ' (space) = pathable
     * 'x' = unpathable
     * '*' = pathable by reaper/colossus
     * example:
     * xxxxxxxx
     * x  *   x
     * x  *   x
     * xxxxxxxx
     */
    public static Grid<Tile> getExampleGrid(String repr) {
        int gridWidth = repr.indexOf('\n');
        if (gridWidth == -1) {
            throw new IllegalArgumentException("No newlines found in grid repr");
        }
        int gridHeight = StringUtils.countMatches(repr, '\n') + 1;

        Grid<Tile> grid = new InMemoryGrid<>(Tile.class, gridWidth, gridHeight, () -> new Tile());
        int x = 0, y = 0;
        for (int i = 0; i < repr.length(); ++i) {
            switch (repr.charAt(i)) {
                case 'x':
                    grid.set(x++, y, unpathableTile());
                    break;
                case ' ':
                    grid.set(x++, y, pathableTile());
                    break;
                case '*':
                    grid.set(x++, y, jumpableTile());
                    break;
                case '\n':
                    x = 0;
                    ++y;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid character '" + repr.charAt(i) + "' at pos " + i);
            }
        }
        return grid;
    }

    private static Tile unpathableTile() {
        Tile t = new Tile();
        t.pathable = false;
        return t;
    }

    private static Tile pathableTile() {
        Tile t = new Tile();
        t.pathable = true;
        return t;
    }

    private static Tile jumpableTile() {
        Tile t = new Tile();
        t.pathable = false;
        t.traversableCliff = true;
        return t;
    }
}
