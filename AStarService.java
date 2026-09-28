package com.example.maps.service;

import com.example.maps.model.RoadEdge;
import com.example.maps.model.RoadGraph;
import org.springframework.stereotype.Service;

import java.util.*;

/** The only routing algorithm in the project: A* on travel cost, heuristic = straight-line time at top speed. */
@Service
public class AStarService {

    public record PathResult(List<Long> nodes, List<RoadEdge> edges, double meters, double seconds) {}
    private record Entry(long id, double f) {}

    public Optional<PathResult> findPath(RoadGraph g, long start, long goal) {
        double[] goalC = g.coord(goal);
        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> prevNode = new HashMap<>();
        Map<Long, RoadEdge> prevEdge = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::f));

        gScore.put(start, 0.0);
        open.add(new Entry(start, h(g, start, goalC)));

        while (!open.isEmpty()) {
            long cur = open.poll().id();
            if (!closed.add(cur)) continue;                       // stale queue entry
            if (cur == goal) return Optional.of(rebuild(start, goal, prevNode, prevEdge));
            double gc = gScore.get(cur);
            for (RoadEdge e : g.edges(cur)) {
                if (closed.contains(e.to())) continue;
                double cand = gc + e.cost();
                if (cand < gScore.getOrDefault(e.to(), Double.MAX_VALUE)) {
                    gScore.put(e.to(), cand);
                    prevNode.put(e.to(), cur);
                    prevEdge.put(e.to(), e);
                    open.add(new Entry(e.to(), cand + h(g, e.to(), goalC)));
                }
            }
        }
        return Optional.empty();
    }

    private double h(RoadGraph g, long id, double[] goal) {
        double[] c = g.coord(id);
        return GeoUtil.haversine(c[0], c[1], goal[0], goal[1]) / g.maxMps();
    }

    private PathResult rebuild(long start, long goal, Map<Long, Long> prevNode, Map<Long, RoadEdge> prevEdge) {
        LinkedList<Long> nodes = new LinkedList<>();
        LinkedList<RoadEdge> edges = new LinkedList<>();
        double meters = 0, seconds = 0;
        for (long n = goal; ; n = prevNode.get(n)) {
            nodes.addFirst(n);
            if (n == start) break;
            RoadEdge e = prevEdge.get(n);
            edges.addFirst(e);
            meters += e.meters();
            seconds += e.seconds();
        }
        return new PathResult(nodes, edges, meters, seconds);
    }
}
