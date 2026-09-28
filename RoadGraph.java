package com.example.maps.model;

import com.example.maps.service.GeoUtil;

import java.util.*;

/** Directed road graph built from OpenStreetMap ways for one travel mode. */
public class RoadGraph {
    private final Map<Long, double[]> coords = new HashMap<>();
    private final Map<Long, List<RoadEdge>> adj = new HashMap<>();
    private Set<Long> mainComponent = Set.of();
    private double maxMps = 1;

    public void addNode(long id, double lat, double lng) { coords.put(id, new double[]{lat, lng}); }

    public void addEdge(long from, RoadEdge e) {
        adj.computeIfAbsent(from, k -> new ArrayList<>()).add(e);
        adj.computeIfAbsent(e.to(), k -> new ArrayList<>());
        maxMps = Math.max(maxMps, e.meters() / e.seconds());
    }

    public double[] coord(long id) { return coords.get(id); }
    public List<RoadEdge> edges(long id) { return adj.getOrDefault(id, List.of()); }
    /** Fastest possible speed in the graph (m/s): keeps the A* heuristic admissible. */
    public double maxMps() { return maxMps; }

    /** Call once after loading: finds the largest connected network so picks never snap onto an isolated fragment. */
    public void finish() {
        Map<Long, Long> parent = new HashMap<>();
        for (Long id : adj.keySet()) parent.put(id, id);
        for (Map.Entry<Long, List<RoadEdge>> en : adj.entrySet())
            for (RoadEdge e : en.getValue()) {
                long a = find(parent, en.getKey()), b = find(parent, e.to());
                if (a != b) parent.put(a, b);
            }
        Map<Long, Integer> size = new HashMap<>();
        for (Long id : adj.keySet()) size.merge(find(parent, id), 1, Integer::sum);
        long best = -1; int bestSize = 0;
        for (Map.Entry<Long, Integer> s : size.entrySet())
            if (s.getValue() > bestSize) { best = s.getKey(); bestSize = s.getValue(); }
        Set<Long> main = new HashSet<>();
        for (Long id : adj.keySet()) if (find(parent, id) == best) main.add(id);
        mainComponent = main;
    }

    private static long find(Map<Long, Long> p, long x) {
        while (p.get(x) != x) { p.put(x, p.get(p.get(x))); x = p.get(x); }
        return x;
    }

    /** Nearest node of the main road network, or -1 when the graph is empty. */
    public long nearest(double lat, double lng) {
        long best = -1; double bestD = Double.MAX_VALUE;
        for (long id : mainComponent) {
            double[] c = coords.get(id);
            double d = GeoUtil.haversine(lat, lng, c[0], c[1]);
            if (d < bestD) { bestD = d; best = id; }
        }
        return best;
    }
}
