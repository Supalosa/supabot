package com.supalosa.bot.pathfinding;

import com.supalosa.bot.analysis.Region;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.AStarShortestPath;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.jgrapht.traverse.ClosestFirstIterator;
import org.jgrapht.traverse.GraphIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class RegionGraph extends SimpleWeightedGraph<Region, DefaultWeightedEdge> {

    public RegionGraph(Class<? extends DefaultWeightedEdge> edgeClass) {
        super(edgeClass);
    }

    /**
     * Returns the region which minimises the distance between the given list of regions.
     * Useful for calculating which region we should be defending from.
     */
    /*public Optional<Region> getConnectedBases(List<Region> regions) {
        SimpleWeightedGraph<Region, DefaultWeightedEdge> subgraph = new AsSubgraph(this);
        // we'd use connected components to find bases which are connected to query point
    }*/

    /**
     * Returns the closest Region (by pathing distance) to the {@code startRegion} that matches the {@code predicate}
     *
     * @param startRegion Region to start the iteration from
     * @param predicate Predicate to match.
     * @return
     */
    public Optional<Region> closestFirstSearch(Region startRegion, Predicate<Region> predicate) {
        GraphIterator<Region, DefaultWeightedEdge> iterator = new ClosestFirstIterator<>(this, startRegion);
        while (iterator.hasNext()) {
            Region head = iterator.next();
            if (predicate.test(head)) {
                return Optional.of(head);
            }
        }
        return Optional.empty();
    }

    public Optional<RegionGraphPath> findPath(Region startRegion, Region endRegion) {
        AStarShortestPath<Region, DefaultWeightedEdge> pathfinder = new AStarShortestPath<>(
                this, (sourceVertex, targetVertex) -> sourceVertex.centrePoint().distance(targetVertex.centrePoint()));

        try {
            GraphPath<Region, DefaultWeightedEdge> path = pathfinder.getPath(startRegion, endRegion);
            if (path == null || path.getEndVertex() == null || !path.getEndVertex().equals(endRegion)) {
                return Optional.empty();
            }
            return Optional.of(ImmutableRegionGraphPath.builder()
                    .path(path.getVertexList())
                    .weight(path.getWeight())
                    .build());
        } catch (Exception ex) {
            System.err.println("ERROR in pathfinder");
            ex.printStackTrace();
            //System.out.println(this.toString());
            return Optional.empty();
        }
    }
}
