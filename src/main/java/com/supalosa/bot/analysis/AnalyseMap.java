package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.game.raw.StartRaw;
import com.github.ocraft.s2client.protocol.observation.spatial.ImageData;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.GameData;
import com.supalosa.bot.analysis.utils.BitmapGrid;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.analysis.utils.VisualisationUtils;
import com.supalosa.bot.debug.JFrameDebugTarget;
import com.supalosa.bot.placement.StructurePlacementCalculator;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

public class AnalyseMap {
    private static final int MAX_BYTE = 255;

    /**
     * Running this method allows for offline analysis.
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        File inputFile = new File("image.bmp");
        BufferedImage terrainBmp = ImageIO.read(new File("terrainHeight.bmp"));
        BufferedImage pathingBmp = ImageIO.read(new File("pathingGrid.bmp"));
        BufferedImage placementBmp = ImageIO.read(new File("placementGrid.bmp"));
        Grid terrain = new BitmapGrid(terrainBmp);
        Grid pathing = new BitmapGrid(pathingBmp);
        Grid placement = new BitmapGrid(placementBmp);
        // TODO make this a dynamic point on the map (just has to be somewhere that is pathable)

        Point2d start = findAnyPathable(pathing);

        long startTime = System.currentTimeMillis();
        // TODO: process observations offline.
        AnalysisResults data = Analysis.run(start, terrain, pathing, placement);
        GameData gameData = new GameData(null);
        StructurePlacementCalculator spc = new StructurePlacementCalculator(data, gameData, start);
        long endTime = System.currentTimeMillis();

        niceRender("niceTransform.bmp", data, tile -> {
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
        });
        niceRender("regionMap.bmp", data, tile -> {
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
        });
        VisualisationUtils.writeCombinedData(start, Optional.empty(), data, "combined.bmp");
        PathfinderTest.run(data);

        System.out.println("Calculation took " + (endTime - startTime) + "ms");
    }

    /**
     * Remove observations (such as initial structures, minerals, rocks) from the map.
     */
    private static void processObservations(List<UnitInPool> observations, GameData data, Grid<Integer> pathing, Grid<Integer> placement) {
        observations.forEach(unitInPool -> {
            data.getUnitFootprint(unitInPool.unit().getType()).ifPresent(footprint -> {
                float x = unitInPool.unit().getPosition().getX();
                float y = unitInPool.unit().getPosition().getY();
                float w = footprint.getX();
                float h = footprint.getY();
                // Note that a structure's origin is at its centre (biased to northeast for even numbers, hence the `ceil`)
                int xStart = (int)Math.ceil(x - w / 2);
                int yStart = (int)Math.ceil(y - h / 2);
                for (int xx = xStart; xx < xStart + w; ++xx) {
                    for (int yy = yStart; yy < yStart + h; ++yy) {
                        pathing.set(xx, yy, Integer.MAX_VALUE);
                    }
                }
            });
        });
    }

    private static Point2d findAnyPathable(Grid<Integer> pathing) {
        int iters = 0;
        while (++iters < 1000000) {
            int x = ThreadLocalRandom.current().nextInt(pathing.getWidth());
            int y = ThreadLocalRandom.current().nextInt(pathing.getHeight());
            if ((pathing.get(x, y) & 0xFF) > 0) {
                System.out.println("Found pathable point in " + iters + " iterations");
                return Point2d.of(x, y);
            }
        }
        throw new IllegalStateException("Could not find pathable point on the map");
    }

    /**
     * This is the entry point for online analysis.
     * @param observationInterface
     * @param startRaw
     * @return
     */
    public static AnalysisResults analyse(ObservationInterface observationInterface, GameData gameData, StartRaw startRaw) {
        BufferedImage terrainBmp = writeImageData(startRaw, startRaw.getTerrainHeight(), "terrainHeight.bmp");
        BufferedImage pathingBmp = writeImageData(startRaw, startRaw.getPathingGrid(), "pathingGrid.bmp");
        BufferedImage placementBmp = writeImageData(startRaw, startRaw.getPlacementGrid(), "placementGrid.bmp");

        Grid terrain = new BitmapGrid(terrainBmp);
        Grid pathing = new BitmapGrid(pathingBmp);
        Grid placement = new BitmapGrid(placementBmp);

        Point playerStartLocation = observationInterface.getStartLocation();
        List<UnitInPool> observations = observationInterface.getUnits();
        processObservations(observations, gameData, pathing, placement);
        AnalysisResults data = Analysis.run(playerStartLocation.toPoint2d(), terrain, pathing, placement);
        System.out.println("Start location=" + playerStartLocation.toPoint2d());
        //VisualisationUtils.writeCombinedData(playerStartLocation.toPoint2d(), Optional.of(startRaw), data, "combined.bmp");

        return data;
    }

    private static BufferedImage writeImageData(StartRaw startRaw, ImageData imageData, String filename) {
        BufferedImage img = convertImageDataToBufferedImage(imageData);
        // NOTE: the data has bottom-left origin, but BufferedImage is top-left origin.

        File outputFile = new File(filename);
        try {
            ImageIO.write(img, "bmp", outputFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return img;
    }

    private static BufferedImage convertImageDataToBufferedImage(ImageData imageData) {
        return imageData.getImage();
        /*
        // although we could use imageData.getImage(), we want to change it to a 3byte RGB.
        BufferedImage img = new BufferedImage(
                imageData.getSize().getX(),
                imageData.getSize().getY(),
                BufferedImage.TYPE_3BYTE_BGR);
        for (int x = 0; x < imageData.getSize().getX(); ++x) {
            for (int y = 0; y < imageData.getSize().getY(); ++y) {
                int height = imageData.sample(Point2d.of(x, y), ImageData.Origin.UPPER_LEFT);
                int rgb = VisualisationUtils.makeRgb(height, height, height);
                //System.out.println("Height " + height + " = ratio " + ratio + "(" + heightMin + "/" + heightMax + "), BV " + byteValue + ", rgb " + rgb);
                img.setRGB(x, y, rgb);
            }
        }
        return img;*/
    }

    public static final int WHITE = VisualisationUtils.makeRgb(255, 255, 255);
    public static final int BLACK = VisualisationUtils.makeRgb(0, 0, 0);
    public static final int GREEN = VisualisationUtils.makeRgb(0, 255, 0);
    public static final int GRAY = VisualisationUtils.makeRgb(128, 128, 128);

    private static void niceRender(String fileName, AnalysisResults data, Function<Tile, Integer> tileRenderer) {
        BufferedImage niceBmp = VisualisationUtils.renderNewGrid(
                data.getGrid(), tileRenderer);

        //niceBmp.setRGB(48, niceBmp.getHeight() - 109, VisualisationUtils.makeRgb(255, 0, 0));
        //niceBmp.setRGB(110, niceBmp.getHeight() - 161, VisualisationUtils.makeRgb(255, 0, 0));

        // scale the bitmap
        AffineTransform transform = new AffineTransform();
        transform.scale(2.0, 2.0);
        AffineTransformOp transformOp = new AffineTransformOp(transform, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);

        BufferedImage after = new BufferedImage(niceBmp.getWidth() * 2, niceBmp.getHeight() * 2, BufferedImage.TYPE_3BYTE_BGR);
        File outputFile = new File(fileName);
        try {
            ImageIO.write(transformOp.filter(niceBmp, after), "bmp", outputFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
