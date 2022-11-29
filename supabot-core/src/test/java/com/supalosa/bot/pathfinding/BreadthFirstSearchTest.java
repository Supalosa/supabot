package com.supalosa.bot.pathfinding;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.Tile;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.analysis.utils.TileGridUtils;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.*;

public class BreadthFirstSearchTest {

    private Grid<Tile> testGrid;

    @BeforeEach
    void setUp() {
        testGrid = TileGridUtils.getExampleGrid(new StringBuilder()
                .append("xxxxxxxxxxx\n")
                .append("x *       x\n")
                .append("xxxxxx  xxx\n")
                .append("x x     x x\n")
                .append("xxx     x x\n")
                .append("x         x\n")
                .append("xxxxxxxxxxx\n")
                .toString()
        );
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void testTestGrid() {
        assertThat(testGrid.get(0, 0)).matches(t -> !t.pathable);
        assertThat(testGrid.get(1, 1)).matches(t -> t.pathable);
        assertThat(testGrid.get(2, 1)).matches(t -> !t.pathable);
        assertThat(testGrid.get(2, 1)).matches(t -> t.traversableCliff);
        assertThat(testGrid.get(3, 1)).matches(t -> t.pathable);
    }

    @Test
    void testBfsSearch() {
        // Empty result
        assertThat(
                BreadthFirstSearch.bfsSearch(Point2d.of(1, 3),
                        testGrid,
                        tile -> tile.traversableCliff)
        ).isEqualTo(Lists.list());
        // Valid path
        assertThat(
                BreadthFirstSearch.bfsSearch(Point2d.of(9, 3),
                        testGrid,
                        tile -> tile.traversableCliff)
        ).isEqualTo(Lists.list(
                Point2d.of(9, 3),
                Point2d.of(9, 4),
                Point2d.of(8, 5),
                Point2d.of(7, 4),
                Point2d.of(6, 3),
                Point2d.of(6, 2),
                Point2d.of(5, 1),
                Point2d.of(4, 1),
                Point2d.of(3, 1),
                Point2d.of(2, 1)
        ));
    }

    @Test
    void testGetFirstPoint() {
        assertThat(
                BreadthFirstSearch.getFirstPoint(Point2d.of(9, 3),
                        testGrid,
                        tile -> tile.traversableCliff)
        ).isEqualTo(Optional.of(Point2d.of(2, 1)));
        assertThat(
                BreadthFirstSearch.getFirstPoint(Point2d.of(1, 3),
                        testGrid,
                        tile -> tile.traversableCliff)
        ).isEqualTo(Optional.empty());
    }

}