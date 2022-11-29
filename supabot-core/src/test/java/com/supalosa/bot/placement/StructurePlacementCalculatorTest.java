package com.supalosa.bot.placement;

import SC2APIProtocol.Data;
import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.data.AbilityData;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.AgentWithData;
import com.supalosa.bot.GameData;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Tile;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.analysis.utils.InMemoryGrid;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructurePlacementCalculatorTest {

    private static final int GRID_WIDTH = 255;
    private static final int GRID_HEIGHT = 10;

    private AgentWithData agentWithData;
    private GameData gameData;
    private AnalysisResults analysisResults;
    private StructurePlacementCalculator structurePlacementCalculator;

    @BeforeEach
    void setUp() {
        ObservationInterface observationInterface = mock(ObservationInterface.class);
        HashMap<Ability, AbilityData> ability = new HashMap<>();
        Data.AbilityData builder = Data.AbilityData.newBuilder()
                .setAbilityId(1)
                .setLinkName("test")
                .setLinkIndex(1)
                .setFootprintRadius(1.5f)
                .build();
        AbilityData barracksData = AbilityData.from(builder);
        ability.put(Abilities.BUILD_BARRACKS, barracksData);
        when(observationInterface.getAbilityData(anyBoolean())).thenReturn(ability);
        gameData = new GameData(observationInterface);
        agentWithData = mock(AgentWithData.class);
        when(agentWithData.gameData()).thenReturn(gameData);
        analysisResults = mock(AnalysisResults.class);
        Point2d start = Point2d.of(0f, 0f);

        Grid<Tile> grid = new InMemoryGrid<>(Tile.class, GRID_WIDTH, GRID_HEIGHT, () -> new Tile());
        final Tile unplaceableTile = new Tile();
        unplaceableTile.placeable = false;
        // Make the entire border unplaceable.
        for (int x = 0; x < grid.getWidth(); ++x) {
            grid.set(x, 0, unplaceableTile);
            grid.set(x, GRID_HEIGHT - 1, unplaceableTile);
        }
        for (int y = 0; y < grid.getHeight(); ++y) {
            grid.set(0, y, unplaceableTile);
            grid.set(GRID_WIDTH - 1, y, unplaceableTile);
        }

        when(analysisResults.getGrid()).thenReturn(grid);
        structurePlacementCalculator = new StructurePlacementCalculator(analysisResults, gameData, start);
    }

    @Test
    void testCanPlaceAt1x1() {
        for (int x = 0; x < GRID_WIDTH; ++x) {
            for (int y = 0; y < GRID_HEIGHT; ++y) {
                if (x == 0 || y == 0 || x == GRID_WIDTH - 1 || y == GRID_HEIGHT - 1) {
                    assertThat(structurePlacementCalculator._canPlaceAt(
                            Point2d.of((float) x, (float) y),
                            1,
                            1,
                            true,
                            Optional.empty()))
                            .withFailMessage("Position " + x + "," + y + " was buildable, but should not be")
                            .isFalse();
                } else {
                    assertThat(structurePlacementCalculator._canPlaceAt(
                            Point2d.of((float) x, (float) y),
                            1,
                            1,
                            true,
                            Optional.empty()))
                            .withFailMessage("Position " + x + "," + y + " was not buildable, but should be")
                            .isTrue();
                }
            }
        }
    }

    @Test
    void testCanPlaceAt2x2() {
        for (int x = 0; x < GRID_WIDTH; ++x) {
            for (int y = 0; y < GRID_HEIGHT; ++y) {
                if (x <= 1 || y <= 1 || x == GRID_WIDTH - 1 || y == GRID_HEIGHT - 1) {
                    assertThat(structurePlacementCalculator._canPlaceAt(
                            Point2d.of((float) x, (float) y),
                            2,
                            2,
                            true,
                            Optional.empty()))
                            .withFailMessage("Position " + x + "," + y + " was buildable, but should not be")
                            .isFalse();
                } else {
                    assertThat(structurePlacementCalculator._canPlaceAt(
                            Point2d.of((float) x, (float) y),
                            2,
                            2,
                            true,
                            Optional.empty()))
                            .withFailMessage("Position " + x + "," + y + " was not buildable, but should be")
                            .isTrue();
                }
            }
        }
    }

    @Test
    void testCanPlaceAtIgnoringStatic() {
        for (int x = 0; x < GRID_WIDTH; ++x) {
            for (int y = 0; y < GRID_HEIGHT; ++y) {
                assertThat(structurePlacementCalculator._canPlaceAt(
                        Point2d.of((float) x, (float) y),
                        1,
                        1,
                        false,
                        Optional.empty()))
                        .withFailMessage("Position " + x + "," + y + " was not buildable, but should be")
                        .isTrue();
            }
        }
    }

    @Test
    void testModifiedFootprint() {
        // Barracks is given a 5x3 footprint.
        // However the placement of the barracks is actually 1 tile to the left.
        Point2d point = Point2d.of(5, 5);
        Optional<ResolvedPlacementResult> result = structurePlacementCalculator.suggestLocationForFreePlacement(agentWithData, point, Abilities.BUILD_BARRACKS,
                Units.TERRAN_BARRACKS, Optional.of(PlacementRules.exact()));
        assertThat(result).isNotEmpty();
        assertThat(result.get().point2d().isPresent());
        assertThat(result).isEqualTo(Optional.of(ResolvedPlacementResult.point2d(Point2d.of(4, 5))));
    }

    @Test
    void testModifiedFootprint2() {
        Point2d point = Point2d.of(6, 5);
        Optional<ResolvedPlacementResult> result = structurePlacementCalculator.suggestLocationForFreePlacement(agentWithData, point, Abilities.BUILD_BARRACKS,
                Units.TERRAN_BARRACKS, Optional.of(PlacementRules.exact()));
        assertThat(result).isNotEmpty();
        assertThat(result.get().point2d().isPresent());
        assertThat(result).isEqualTo(Optional.of(ResolvedPlacementResult.point2d(Point2d.of(5, 5))));
    }


    @Test
    void testModifiedFootprint3() {
        Point2d point = Point2d.of(GRID_WIDTH - 3, 5);
        Optional<ResolvedPlacementResult> result = structurePlacementCalculator.suggestLocationForFreePlacement(agentWithData, point, Abilities.BUILD_BARRACKS,
                Units.TERRAN_BARRACKS, Optional.of(PlacementRules.exact()));
        assertThat(result).isEmpty();
    }

    @Test
    void testModifiedFootprintScan() {
        for (float x = 5.0f; x < GRID_WIDTH - 4; x+= 0.01f) {
            Point2d point = Point2d.of(x, 5);
            Optional<ResolvedPlacementResult> result = structurePlacementCalculator.suggestLocationForFreePlacement(agentWithData, point, Abilities.BUILD_BARRACKS,
                    Units.TERRAN_BARRACKS, Optional.of(PlacementRules.exact()));
            assertThat(result).withFailMessage("Position " + x).isNotEmpty();
            assertThat(result.get().point2d()).withFailMessage("Position " + x).isNotEmpty();
            assertThat(result).withFailMessage("Position " + x).isEqualTo(
                    Optional.of(ResolvedPlacementResult.point2d(Point2d.of(x - 1f, 5))));
            structurePlacementCalculator.clearMutableGrid();
        }
    }
}