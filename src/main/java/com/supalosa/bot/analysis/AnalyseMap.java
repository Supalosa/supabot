package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.game.raw.StartRaw;
import com.github.ocraft.s2client.protocol.observation.spatial.ImageData;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.utils.BitmapGrid;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.analysis.utils.InMemoryGrid;
import com.supalosa.bot.analysis.utils.VisualisationUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class AnalyseMap {
    private static final int MAX_BYTE = 255;

    public static void main(String[] args) throws IOException {
        File inputFile = new File("image.bmp");
        BufferedImage terrainBmp = ImageIO.read(new File("terrainHeight.bmp"));
        BufferedImage pathingBmp = ImageIO.read(new File("pathingGrid.bmp"));
        BufferedImage placementBmp = ImageIO.read(new File("placementGrid.bmp"));
        Grid terrain = new BitmapGrid(terrainBmp);
        Grid pathing = new BitmapGrid(pathingBmp);
        Grid placement = new BitmapGrid(placementBmp);
        // TODO make this a dynamic point on the map (just has to be somewhere that is pathable)
        Point2d start = Point2d.of(37.5f, 53.5f);
        Analysis.AnalysisResults data = Analysis.floodFill(start, terrain, pathing, placement);
        StructurePlacementCalculator spc = new StructurePlacementCalculator(data);
        VisualisationUtils.writeCombinedData(start, Optional.empty(), data, "combined.bmp");

        spc.getFirstSupplyDepot(start).ifPresentOrElse(location -> {
            System.out.println("First supply depot at " + location);
        }, () -> System.out.println("No location found for first supply depot."));
    }

    public static void analyse(Point playerStartLocation, StartRaw startRaw) {
        BufferedImage terrainBmp = writeImageData(startRaw, startRaw.getTerrainHeight(), "terrainHeight.bmp");
        BufferedImage pathingBmp = writeImageData(startRaw, startRaw.getPathingGrid(), "pathingGrid.bmp");
        BufferedImage placementBmp = writeImageData(startRaw, startRaw.getPlacementGrid(), "placementGrid.bmp");

        Grid terrain = new BitmapGrid(terrainBmp);
        Grid pathing = new BitmapGrid(pathingBmp);
        Grid placement = new BitmapGrid(placementBmp);
        Analysis.AnalysisResults data = Analysis.floodFill(playerStartLocation.toPoint2d(), terrain, pathing, placement);
        System.out.println("Start location=" + playerStartLocation.toPoint2d());
        VisualisationUtils.writeCombinedData(playerStartLocation.toPoint2d(), Optional.of(startRaw), data, "combined.bmp");
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
