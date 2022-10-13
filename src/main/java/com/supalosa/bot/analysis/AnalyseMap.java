package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.protocol.game.raw.StartRaw;
import com.github.ocraft.s2client.protocol.observation.spatial.ImageData;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.GameData;
import com.supalosa.bot.analysis.utils.BitmapGrid;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.analysis.utils.VisualisationUtils;
import com.supalosa.bot.placement.StructurePlacementCalculator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

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
        AnalysisResults data = Analysis.run(start, terrain, pathing, placement);
        GameData gameData = new GameData(null);
        StructurePlacementCalculator spc = new StructurePlacementCalculator(data, gameData, start);
        VisualisationUtils.writeCombinedData(start, Optional.empty(), data, "combined.bmp");
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
    public static AnalysisResults analyse(ObservationInterface observationInterface, StartRaw startRaw) {
        BufferedImage terrainBmp = writeImageData(startRaw, startRaw.getTerrainHeight(), "terrainHeight.bmp");
        BufferedImage pathingBmp = writeImageData(startRaw, startRaw.getPathingGrid(), "pathingGrid.bmp");
        BufferedImage placementBmp = writeImageData(startRaw, startRaw.getPlacementGrid(), "placementGrid.bmp");

        Grid terrain = new BitmapGrid(terrainBmp);
        Grid pathing = new BitmapGrid(pathingBmp);
        Grid placement = new BitmapGrid(placementBmp);

        Point playerStartLocation = observationInterface.getStartLocation();
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
}
