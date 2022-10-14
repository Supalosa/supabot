package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.AStarShortestPath;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleGraph;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.jgrapht.traverse.DepthFirstIterator;

import java.util.Iterator;

public class PathfinderTest {

    public static void run(AnalysisResults analysisResults) {
        Graph<Region, DefaultWeightedEdge> pathfindingGraph = createGraph(analysisResults);
        AStarShortestPath<Region, DefaultWeightedEdge> pathfinder = new AStarShortestPath<>(
                pathfindingGraph, (sourceVertex, targetVertex) -> sourceVertex.centrePoint().distance(targetVertex.centrePoint()));

        Region startRegion = analysisResults.getRegion(1);
        Region endRegion = analysisResults.getRegion(20);
        GraphPath<Region, DefaultWeightedEdge> path = pathfinder.getPath(startRegion, endRegion);
        if (path != null) {
            path.getVertexList().forEach(region -> {
                System.out.println("Region: " + region.regionId());
            });
        } else {
            System.out.println("No path");
        }
    }

    private static Graph<Region, DefaultWeightedEdge> createGraph(AnalysisResults analysisResults) {
        Graph<Region, DefaultWeightedEdge> g = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);

        analysisResults.getRegions().forEach(region -> {
           g.addVertex(region);
        });

        analysisResults.getRegions().forEach(region -> {
            region.connectedRegions().forEach(connectedRegionId -> {
                Region otherRegion = analysisResults.getRegion(connectedRegionId);
                g.addEdge(region, otherRegion);
                double distance = region.centrePoint().distance(otherRegion.centrePoint());
                if (otherRegion.regionId() == 30) {
                    distance *= 10;
                }
                g.setEdgeWeight(region, otherRegion, distance);

                //System.out.println("Weight = " + g.getEdgeWeight(g.getEdge(region, otherRegion)));
            });
        });

        return g;
    }


}
