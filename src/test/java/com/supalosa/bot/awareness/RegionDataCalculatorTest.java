package com.supalosa.bot.awareness;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.analysis.AnalyseMap;
import com.supalosa.bot.analysis.Analysis;
import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.analysis.utils.BitmapGrid;
import com.supalosa.bot.analysis.utils.Grid;
import com.supalosa.bot.engagement.WorkerDefenceThreatCalculator;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionDataCalculatorTest {

    private static AnalysisResults analysisResults;
    private static int spawnRegionId;
    private static int naturalRampRegionId;
    private static int naturalRegionId;

    @BeforeAll
    static void setUp() throws IOException {
        BufferedImage terrainBmp = ImageIO.read(new File("src/test/resources/terrainHeight.bmp"));
        BufferedImage pathingBmp = ImageIO.read(new File("src/test/resources/pathingGrid.bmp"));
        BufferedImage placementBmp = ImageIO.read(new File("src/test/resources/placementGrid.bmp"));
        Grid terrain = new BitmapGrid(terrainBmp);
        Grid pathing = new BitmapGrid(pathingBmp);
        Grid placement = new BitmapGrid(placementBmp);
        Point2d start = AnalyseMap.findAnyPathable(pathing);
        analysisResults = Analysis.run(start, terrain, pathing, placement);

        // These positions are based on the 5 o clock spawn.
        spawnRegionId = analysisResults.getTile(116, 26).get().regionId;
        assertThat(analysisResults.getTile(124, 35).get().regionId == spawnRegionId);
        assertThat(analysisResults.getTile(125, 35).get().regionId == -1);
        assertThat(analysisResults.getTile(126, 35).get().regionId == -1);
        assertThat(analysisResults.getTile(127, 35).get().regionId == spawnRegionId);

        naturalRampRegionId = analysisResults.getTile(113, 39).get().regionId;
        naturalRegionId = analysisResults.getTile(122, 44).get().regionId;
    }

    @Test
    void getDiffuseEnemyThreatForRegion() {
        RegionDataCalculator regionDataCalculator = new RegionDataCalculator(new WorkerDefenceThreatCalculator());
        Map<Integer, Double> baseThreat = new HashMap<>();
        analysisResults.getRegions().forEach(region -> {
            baseThreat.put(region.regionId(), 0.0);
        });

        Region mainBase = analysisResults.getRegion(spawnRegionId);
        Region rampToNatural = analysisResults.getRegion(naturalRampRegionId);
        Region natural = analysisResults.getRegion(naturalRegionId);

        Map<Integer, Double> threatAtNatural = new HashMap<>(baseThreat);
        threatAtNatural.put(naturalRegionId, 10.0);

        /*analysisResults.getRegions().forEach(region -> {
            double threat = regionDataCalculator.getDiffuseEnemyThreatForRegion(
                    analysisResults,
                    threatAtNatural,
                    region
            ).get();
            double distance = natural.centrePoint().distance(region.centrePoint());
            System.out.println("Region " + region.regionId() + " = " + threat + ", distance is " + distance);
        });*/

        double distanceFromMainBaseToRamp = mainBase.centrePoint().distance(rampToNatural.centrePoint());
        double distanceFromRampToNatural = rampToNatural.centrePoint().distance(natural.centrePoint());
        double distanceFromMainBaseToNatural = distanceFromMainBaseToRamp + distanceFromRampToNatural;

        System.out.println("Main = " + spawnRegionId + ", Ramp = " + naturalRampRegionId + ", Natural =  " + naturalRegionId);
        double mainBaseThreat = regionDataCalculator.getDiffuseEnemyThreatForRegion(
                analysisResults,
                threatAtNatural,
                mainBase
        ).get();

        double expectedThreatForMainBase = (10.0 * (RegionDataCalculator.DIFFUSE_THREAT_CONSTANT / distanceFromMainBaseToNatural));
        assertThat(mainBaseThreat).isCloseTo(expectedThreatForMainBase, Offset.offset(0.01));
/*
        double rampThreat = regionDataCalculator.getDiffuseEnemyThreatForRegion(
                analysisResults,
                threatAtNatural,
                rampToNatural
        ).get();

        double expectedThreatForRamp = (10.0 * (RegionDataCalculator.DIFFUSE_THREAT_CONSTANT / distanceFromRampToNatural));
        assertThat(rampThreat).isCloseTo(expectedThreatForRamp, Offset.offset(0.01));*/

    }
}