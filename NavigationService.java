package com.example.maps.service;

import com.example.maps.dto.NavigationResponse;
import com.example.maps.dto.NavigationResponse.Step;
import com.example.maps.model.RoadEdge;
import com.example.maps.model.RoadGraph;
import com.example.maps.model.TravelMode;
import com.example.maps.repository.RoadGraphRepository;
import com.example.maps.service.AStarService.PathResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class NavigationService {
    private static final double WALK_MPS = 1.4;   // used for the short hop between a pin and the nearest road

    private final RoadGraphRepository repo;
    private final AStarService aStar;

    public NavigationService(RoadGraphRepository repo, AStarService aStar) {
        this.repo = repo;
        this.aStar = aStar;
    }

    public NavigationResponse route(double oLat, double oLng, double dLat, double dLng, TravelMode mode) {
        double km = GeoUtil.haversine(oLat, oLng, dLat, dLng) / 1000;
        if (km < 0.01) throw new RouteException(HttpStatus.BAD_REQUEST, "Start and destination are the same place.");
        if (km > mode.maxKm())
            throw new RouteException(HttpStatus.UNPROCESSABLE_ENTITY,
                String.format("Too far to %s in this demo (limit %.0f km, this trip is %.0f km).", mode.label(), mode.maxKm(), km));

        boolean major = km > mode.majorAboveKm();          // long trips use main roads only, to keep the data small
        double snapLimit = major ? mode.majorSnapMeters() : mode.snapMeters();

        for (double scale : new double[]{1.0, 3.0}) {      // second attempt searches a wider area
            double padLat = Math.max(0.008, 0.35 * Math.abs(oLat - dLat)) * scale;
            double padLng = Math.max(0.008, 0.35 * Math.abs(oLng - dLng)) * scale;
            RoadGraph g = repo.load(mode, major,
                Math.min(oLat, dLat) - padLat, Math.min(oLng, dLng) - padLng,
                Math.max(oLat, dLat) + padLat, Math.max(oLng, dLng) + padLng);

            long s = g.nearest(oLat, oLng), t = g.nearest(dLat, dLng);
            if (s < 0 || t < 0) continue;
            double so = dist(g, s, oLat, oLng), sd = dist(g, t, dLat, dLng);
            if (so > snapLimit)
                throw new RouteException(HttpStatus.UNPROCESSABLE_ENTITY,
                    String.format("No %s road within %.0f m of the start point. Move the pin closer to a road.", mode.label(), snapLimit));
            if (sd > snapLimit)
                throw new RouteException(HttpStatus.UNPROCESSABLE_ENTITY,
                    String.format("No %s road within %.0f m of the destination. Move the pin closer to a road.", mode.label(), snapLimit));

            Optional<PathResult> r = aStar.findPath(g, s, t);
            if (r.isPresent()) return toResponse(mode, g, r.get(), oLat, oLng, dLat, dLng, so, sd);
        }
        throw new RouteException(HttpStatus.UNPROCESSABLE_ENTITY,
            "No " + mode.label() + " route exists between these points (for example, they may be separated by a river or a motorway).");
    }

    private static double dist(RoadGraph g, long id, double lat, double lng) {
        double[] c = g.coord(id);
        return GeoUtil.haversine(lat, lng, c[0], c[1]);
    }

    private NavigationResponse toResponse(TravelMode mode, RoadGraph g, PathResult r,
                                          double oLat, double oLng, double dLat, double dLng, double so, double sd) {
        List<double[]> path = new ArrayList<>();
        path.add(new double[]{oLat, oLng});
        for (long id : r.nodes()) path.add(g.coord(id));
        path.add(new double[]{dLat, dLng});
        return new NavigationResponse(mode.label(),
            r.meters() + so + sd,
            r.seconds() + (so + sd) / WALK_MPS,
            path, buildSteps(path, r.edges(), so, sd), so, sd);
    }

    /** Turns the road path into turn-by-turn steps: a new step starts when the street changes or the road bends sharply. */
    private List<Step> buildSteps(List<double[]> path, List<RoadEdge> edges, double so, double sd) {
        List<Step> steps = new ArrayList<>();
        int n = edges.size();
        int last = path.size() - 1;
        if (n == 0) {
            steps.add(new Step("Arrive at your destination", "arrive", 0, last));
            return steps;
        }
        // edge k runs between path[k+1] and path[k+2]
        double[] brg = new double[n];
        for (int k = 0; k < n; k++) {
            double[] a = path.get(k + 1), b = path.get(k + 2);
            brg[k] = GeoUtil.bearing(a[0], a[1], b[0], b[1]);
        }
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        double since = edges.get(0).meters();
        for (int k = 1; k < n; k++) {
            double delta = norm(brg[k] - brg[k - 1]);
            boolean streetChanged = !label(edges.get(k)).equals(label(edges.get(k - 1)));
            if ((streetChanged || Math.abs(delta) >= 45) && since >= 15) { starts.add(k); since = 0; }
            since += edges.get(k).meters();
        }
        for (int j = 0; j < starts.size(); j++) {
            int k = starts.get(j);
            int end = j + 1 < starts.size() ? starts.get(j + 1) : n;
            double meters = 0;
            for (int i = k; i < end; i++) meters += edges.get(i).meters();
            if (j == 0) meters += so;
            if (j == starts.size() - 1) meters += sd;
            String label = label(edges.get(k));
            if (k == 0) {
                steps.add(new Step("Head " + cardinal(brg[0]) + " on " + label, "depart", meters, 0));
            } else {
                double delta = norm(brg[k] - brg[k - 1]);
                double a = Math.abs(delta);
                String side = delta > 0 ? "right" : "left";
                String type, text;
                if (a < 20) { type = "straight"; text = "Continue onto " + label; }
                else if (a < 60) { type = "slight-" + side; text = "Bear " + side + " onto " + label; }
                else if (a < 135) { type = side; text = "Turn " + side + " onto " + label; }
                else { type = "sharp-" + side; text = "Make a sharp " + side + " onto " + label; }
                steps.add(new Step(text, type, meters, k + 1));
            }
        }
        steps.add(new Step("Arrive at your destination", "arrive", 0, last));
        return steps;
    }

    private static String label(RoadEdge e) {
        if (e.name() != null && !e.name().isBlank()) return e.name();
        return switch (e.highway()) {
            case "footway", "pedestrian", "corridor" -> "the footpath";
            case "path", "track" -> "the path";
            case "steps" -> "the steps";
            case "cycleway" -> "the cycle path";
            case "service" -> "the service road";
            default -> "the road";
        };
    }

    private static double norm(double d) {
        d %= 360;
        if (d > 180) d -= 360;
        if (d <= -180) d += 360;
        return d;
    }

    private static String cardinal(double b) {
        String[] names = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
        return names[(int) Math.round(b / 45) % 8];
    }
}
