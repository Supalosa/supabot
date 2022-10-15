package com.supalosa.bot.pathfinding;

import com.supalosa.bot.analysis.AnalysisResults;
import com.supalosa.bot.analysis.Region;
import com.supalosa.bot.awareness.RegionData;
import org.jgrapht.graph.DefaultWeightedEdge;

import java.util.Map;
import java.util.function.BiFunction;

public class GraphUtils {

    public static RegionGraph createGraph(AnalysisResults analysisResults,
                                                                 Map<Integer, RegionData> allRegionData,
                                                                 BiFunction<RegionData, RegionData, Double> regionMapper) {
        RegionGraph g = new RegionGraph(DefaultWeightedEdge.class);

        analysisResults.getRegions().forEach(region -> {
            g.addVertex(region);
        });

        analysisResults.getRegions().forEach(region -> {
            RegionData regionData = allRegionData.get(region.regionId());
            region.connectedRegions().forEach(connectedRegionId -> {
                Region otherRegion = analysisResults.getRegion(connectedRegionId);
                RegionData otherRegionData = allRegionData.get(otherRegion.regionId());

                double distance = region.centrePoint().distance(otherRegion.centrePoint());
                Double factor = regionMapper.apply(regionData, otherRegionData);
                if (factor != null && !regionData.isBlocked() && !otherRegionData.isBlocked()) {
                    g.addEdge(region, otherRegion);
                    distance *= factor;
                    g.setEdgeWeight(region, otherRegion, distance);
                }
                //System.out.println("Weight = " + g.getEdgeWeight(g.getEdge(region, otherRegion)));
            });
        });


        return g;
    }
}
