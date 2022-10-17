package com.supalosa.bot.analysis.utils;

import com.github.ocraft.s2client.protocol.data.Buff;
import com.github.ocraft.s2client.protocol.game.raw.StartRaw;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Tile;
import com.supalosa.bot.analysis.TileSet;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.Stack;
import java.util.function.BiFunction;
import java.util.function.Function;

public class VisualisationUtils {
    static final int TILE_SIZE = 32;
    static final int LETTER_WIDTH = 5;

    public static final int BLACK = makeRgb(0, 0, 0);
    public static final int GREEN = makeRgb(0, 255, 0);
    public static final int GRAY = makeRgb(128, 128, 128);
    public static final int WHITE = makeRgb(255, 255, 255);

    private static int getInvertedY(int gameY, int gridHeight) {
        return gridHeight - gameY - 1;
    }

    /**
     * Render the game-oriented (bottom-left-origin) Grid into a top-left-origin Bitmap.
     */
    public static <T> BufferedImage renderNewGrid(Grid<T> grid, Function<T, Integer> valueToRgb) {
        BufferedImage image = new BufferedImage(grid.getWidth(), grid.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        for (int x = 0; x < grid.getWidth(); ++x) {
            for (int gameY = 0; gameY < grid.getHeight(); ++gameY) {
                int y = getInvertedY(gameY, grid.getHeight());
                image.setRGB(x, y, valueToRgb.apply(grid.get(x, gameY)));
            }
        }
        return image;
    }

    public static BufferedImage renderTileSet(BufferedImage image,
                                              TileSet tileSet,
                                              Function<Integer, Integer> valueAndBlending,
                                              Function<Integer, Integer> valueAndBlendingForBorder) {
        for (Point2d point2d : tileSet.getTiles()) {
            int x = (int)point2d.getX();
            int y = getInvertedY((int)point2d.getY(), image.getHeight());
            int newValue = valueAndBlending.apply(image.getRGB(x, y));
            image.setRGB(x, y, newValue);
        }
        if (tileSet.getBorderTiles().isPresent()) {
            for (Point2d point2d : tileSet.getBorderTiles().get()) {
                int x = (int)point2d.getX();
                int y = getInvertedY((int)point2d.getY(), image.getHeight());
                int newValue = valueAndBlendingForBorder.apply(image.getRGB(x, y));
                image.setRGB(x, y, newValue);
            }
        }
        return image;
    }

    public static <T> BufferedImage renderTileSet(BufferedImage image,
                                                  Grid<T> grid, Function<T, Integer> valueToRgb,
                                                  BiFunction<Integer, Integer, Integer> blending) {
        for (int x = 0; x < grid.getWidth(); ++x) {
            for (int gameY = 0; gameY < grid.getHeight(); ++gameY) {
                int y = getInvertedY(gameY, grid.getHeight());
                int existingValue = image.getRGB(x, y);
                int newValue = valueToRgb.apply(grid.get(x, gameY));
                image.setRGB(x, y, blending.apply(existingValue, newValue));
            }
        }
        return image;
    }
    public static void writeCombinedData(Point2d playerStartLocation,
                                          Optional<StartRaw> startRaw,
                                          AnalysisResults data,
                                          String filename) {
        Grid<Tile> grid = data.getGrid();
        // NB: the game origin (0, 0) is bottom-left.
        // The buffered image origin is top-left.
        // Therefore we need to invert all Y positions.
        int gridHeight = grid.getHeight();
        BufferedImage img = new BufferedImage(
                grid.getWidth()* TILE_SIZE,
                grid.getHeight()* TILE_SIZE,
                BufferedImage.TYPE_3BYTE_BGR);

        final int blackColor = makeRgb(0, 0, 0);
        final int greenColor = makeRgb(0, 255, 0);
        for (int x = 0; x < grid.getWidth(); ++x) {
            for (int gameY = 0; gameY < grid.getHeight(); ++gameY) {
                // See note above about why we're inverting.
                int y = getInvertedY(gameY, gridHeight);
                Tile tile = grid.get(x, gameY);
                int bgColor = makeRgb(tile.terrain, tile.terrain, tile.terrain);
                int dataColor = makeRgb(0, 0, 0);
                if (tile.isTopOfRamp) {
                    dataColor = makeRgb(0, 0, 255);
                }
                if (tile.isRamp) {
                    dataColor = makeRgb(255, 0, 0);
                }
                fillSquare(
                        img,
                        x * TILE_SIZE,
                        y * TILE_SIZE,
                        x * TILE_SIZE + TILE_SIZE,
                        y * TILE_SIZE + TILE_SIZE,
                        bgColor);
                drawSquare(img, x * TILE_SIZE, y * TILE_SIZE,
                        x * TILE_SIZE + TILE_SIZE,
                        y * TILE_SIZE + TILE_SIZE, dataColor);
                if (!tile.pathable) {
                    int borderColor = tile.traversableCliff ? greenColor : blackColor;
                    drawSquare(img, x * TILE_SIZE + 1, y * TILE_SIZE + 1,
                            x * TILE_SIZE + TILE_SIZE - 1,
                            y * TILE_SIZE + TILE_SIZE - 1, borderColor);
                }

                drawSquare(img, x * TILE_SIZE + 2, y * TILE_SIZE + 2,
                        x * TILE_SIZE + TILE_SIZE - 2,
                        y * TILE_SIZE + TILE_SIZE - 2, dataColor);
                drawNumber(img, x * TILE_SIZE + 5,
                        (int) (y * TILE_SIZE + (TILE_SIZE * 0.2)),
                        tile.terrain, 0);
                drawNumber(img, x * TILE_SIZE + 5,
                        (int) (y * TILE_SIZE + (TILE_SIZE * 0.2) + 6),
                        tile.pathable ? 1 : 0, 0);
                drawNumber(img, x * TILE_SIZE + 5,
                        (int) (y * TILE_SIZE + (TILE_SIZE * 0.2) + 12),
                        tile.placeable ? 1 : 0, 0);
                if (tile.regionId > 0) {
                    drawNumber(img, x * TILE_SIZE + 5,
                            (int) (y * TILE_SIZE + (TILE_SIZE * 0.2) + 18),
                            tile.regionId, 0);
                }

                drawNumber(img, x * TILE_SIZE + 15,
                        (int) (y * TILE_SIZE + (TILE_SIZE * 0.2) + 6),
                        tile.distanceToBorder, 0);

                if (tile.rampId >= 0) {
                    drawNumber(img, x * TILE_SIZE + 15,
                            (int) (y * TILE_SIZE + (TILE_SIZE * 0.2) + 12),
                            tile.rampId, 0);
                }
            }
        }

        /*int green = makeRgb(0, 255, 0);
        drawSquare(img,
                (int)(playerStartLocation.getX() * TILE_SIZE),
                (getInvertedY((int)playerStartLocation.getY(), gridHeight) * TILE_SIZE),
                (int)(playerStartLocation.getX() * TILE_SIZE) + TILE_SIZE,
                (getInvertedY((int)playerStartLocation.getY(), gridHeight) * TILE_SIZE) + TILE_SIZE,
                green);
        startRaw.ifPresent(r -> {
            r.getStartLocations().forEach(startLocation -> {
                int red = makeRgb(255, 0, 0);
                img.setRGB((int)startLocation.getX()* TILE_SIZE +2, (int)startLocation.getY()* TILE_SIZE +2, red);
            });
        });*/

        File outputFile = new File(filename);
        try {
            ImageIO.write(img, "bmp", outputFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     *
     * @param red 0-255
     * @param green 0-255
     * @param blue 0-255
     * @return packed rgb value
     */
    public static int makeRgb(int red, int green, int blue) {
        return blue & 0xFF | (green & 0XFF) << 8 | (red & 0XFF) << 16;
    }

    static void fillSquare(BufferedImage canvas, int xStart, int yStart, int xEnd, int yEnd, int color) {
        for (int x = xStart; x < xEnd; ++x) {
            for (int y = yStart; y < yEnd; ++y) {
                canvas.setRGB(x, y, color);
            }
        }
    }

    static void drawSquare(BufferedImage canvas, int xStart, int yStart, int xEnd, int yEnd, int color) {
        for (int x = xStart; x < xEnd; ++x) {
            for (int y = yStart; y < yEnd; ++y) {
                if (x == xStart || y == yStart || x == xEnd - 1 || y == yEnd - 1) {
                    canvas.setRGB(x, y, color);
                }
            }
        }
    }

    private static void drawLetter(BufferedImage canvas, int x, int y, char c, int color) {
        int[] bits;
        switch (c) {
            case '0':
                bits = new int[]{
                        0b01110,0b01010,0b01010,0b01010,0b01110,
                };
                break;
            case '1':
                bits = new int[]{
                        0b00100,0b00100,0b00100,0b00100,0b00100,
                };
                break;
            case '2':
                bits = new int[]{
                        0b01110,0b00010,0b01110,0b01000,0b01110,
                };
                break;
            case '3':
                bits = new int[]{
                        0b01110,0b00010,0b01110,0b00010,0b01110,
                };
                break;
            case '4':
                bits = new int[]{
                        0b01010,0b01010,0b01110,0b00010,0b00010,
                };
                break;
            case '5':
                bits = new int[]{
                        0b01110,0b01000,0b01110,0b00010,0b01110,
                };
                break;
            case '6':
                bits = new int[]{
                        0b01110,0b01000,0b01110,0b01010,0b01110,
                };
                break;
            case '7':
                bits = new int[]{
                        0b01110,0b00100,0b00100,0b01000,0b01000,
                };
                break;
            case '8':
                bits = new int[]{
                        0b01110,0b01010,0b01110,0b01010,0b01110,
                };
                break;
            case '9':
                bits = new int[]{
                        0b01110,0b01010,0b01110,0b00010,0b00010,
                };
                break;
            case '-':
                bits = new int[]{
                        0b00000, 0b00000, 0b01110, 0b00000, 0b00000,
                };
                break;
            default:
                bits = new int[]{};
        }
        for (int row = 0; row < bits.length; ++row) {
            int bitsForRow = bits[row];
            for (int col = 0; col < LETTER_WIDTH; ++col) {
                int masked = (bitsForRow & (0x1 << col));
                if (masked != 0) {
                    canvas.setRGB(x + (LETTER_WIDTH - col), y + row, color);
                }
            }
        }
    }

    static void drawNumber(BufferedImage canvas, int x, int y, int number, int color) {
        int position = 1;
        int test;
        // 36 % 10 = 6, (36 % 10) / 1 = 6
        // 36 % 100 = 36, (36 % 100) / 10 = 3
        /*Stack<Character> characters = new Stack<Character>();
        do {
            test = (int) Math.pow(10, position);
            int digit = (number % test) / (test / 10);
            //System.out.println(number + " = Pos " + position + ", test " + test + ", digit " + digit);
            characters.push(Character.forDigit(digit, 10));
            ++position;
        } while (test <= number);
        int characterPos = 0;
        while (!characters.isEmpty()) {
            char c = characters.pop();
            drawLetter(canvas, x + (characterPos * LETTER_WIDTH), y, c, color);
            ++characterPos;
        }*/
        String integerAsString = Integer.toString(number);
        int characterPos = 0;
        char[] characters = integerAsString.toCharArray();
        for (int i = 0; i < characters.length; ++i) {
            drawLetter(canvas, x + (i * LETTER_WIDTH), y, characters[i], color);
        }
    }

    public static Function<Tile, Integer> getDistanceTransformRenderer() {
        return tile -> {
            if (!tile.pathable && !tile.placeable) {
                return BLACK;
            }
            if (tile.isPostFilteredLocalMaximum) {
                return GREEN;
            }
            if (tile.isLocalMaximum) {
                return GRAY;
            }
            if (tile.distanceToBorder == 1) {
                return WHITE;
            }
            float dbRatio = tile.distanceToBorder / 20f;
            int colComponent = (int)(255 * dbRatio);
            return VisualisationUtils.makeRgb(0, colComponent, colComponent);
        };
    }

    public static Function<Tile, Integer> getRegionMapRenderer() {
        return tile -> {
            if (!tile.pathable && !tile.placeable) {
                return BLACK;
            }
            if (tile.isPostFilteredLocalMaximum) {
                return GREEN;
            }
            if (tile.distanceToBorder == 1) {
                return WHITE;
            }
            int colComponent = (int)(tile.regionId);
            colComponent = 31 * colComponent + (int) colComponent;
            return VisualisationUtils.makeRgb(
                    (colComponent ^ (colComponent >>> 3)) & 0xFF,
                    (colComponent ^ (colComponent >>> 7)) & 0xFF,
                    (colComponent ^ (colComponent >>> 11)) & 0xFF);
        };
    }
}
