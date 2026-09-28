package com.example.maps.repository;

import com.example.maps.model.RoadEdge;
import com.example.maps.model.RoadGraph;
import com.example.maps.model.TravelMode;
import com.example.maps.service.GeoUtil;
import com.example.maps.service.RouteException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Loads real road data from OpenStreetMap (Overpass API) and caches the graphs it builds. */
@Repository
public class RoadGraphRepository {
    private static final String[] ENDPOINTS = {
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    };

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, RoadGraph> cache = new LinkedHashMap<String, RoadGraph>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, RoadGraph> eldest) { return size() > 6; }
    };

    public RoadGraph load(TravelMode mode, boolean major, double south, double west, double north, double east) {
        // Round the box outwards so nearby requests share one cached graph.
        double s = Math.floor(south * 100) / 100, w = Math.floor(west * 100) / 100;
        double n = Math.ceil(north * 100) / 100, e = Math.ceil(east * 100) / 100;
        String key = mode + "|" + major + "|" + s + "|" + w + "|" + n + "|" + e;
        synchronized (cache) {
            RoadGraph hit = cache.get(key);
            if (hit != null) return hit;
        }
        String query = String.format(Locale.ROOT,
            "[out:json][timeout:50];way[\"highway\"~\"^(%s)$\"](%f,%f,%f,%f);(._;>;);out body qt;",
            mode.highwayRegex(major), s, w, n, e);
        RoadGraph graph = build(mode, fetch(query));
        synchronized (cache) { cache.put(key, graph); }
        return graph;
    }

    private JsonNode fetch(String query) {
        String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        for (String url : ENDPOINTS) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(70))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "maps-learning-project/0.2")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = res.body()) {
                    if (res.statusCode() == 200) return mapper.readTree(in);
                }
            } catch (IOException ex) {
                // try the next mirror
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new RouteException(HttpStatus.BAD_GATEWAY,
            "The road-data service (OpenStreetMap Overpass) is busy or unreachable. Wait a moment and try again.");
    }

    private RoadGraph build(TravelMode mode, JsonNode root) {
        Map<Long, double[]> pts = new HashMap<>();
        List<JsonNode> ways = new ArrayList<>();
        for (JsonNode el : root.path("elements")) {
            String type = el.path("type").asText();
            if (type.equals("node")) pts.put(el.path("id").asLong(), new double[]{el.path("lat").asDouble(), el.path("lon").asDouble()});
            else if (type.equals("way")) ways.add(el);
        }

        RoadGraph g = new RoadGraph();
        for (JsonNode w : ways) {
            JsonNode tags = w.path("tags");
            String hw = tags.path("highway").asText("");
            if (!mode.allows(tags)) continue;

            boolean fwd = true, bwd = true;
            if (mode != TravelMode.WALKING) {            // pedestrians ignore one-way rules
                String ow = tags.path("oneway").asText("");
                String junction = tags.path("junction").asText("");
                boolean applies = !(mode == TravelMode.CYCLING && tags.path("oneway:bicycle").asText("").equals("no"));
                if (applies) {
                    if (ow.equals("-1") || ow.equals("reverse")) fwd = false;
                    else if (ow.equals("yes") || ow.equals("1") || ow.equals("true")) bwd = false;
                    else if (ow.isEmpty() && (junction.equals("roundabout") || junction.equals("circular")
                            || (mode == TravelMode.DRIVING && hw.startsWith("motorway")))) bwd = false;
                }
            }

            String name = tags.path("name").asText(tags.path("ref").asText(""));
            double speedMps = mode.speedKmh(hw, tags.path("maxspeed").asText("")) / 3.6;
            double penalty = mode.penalty(hw);
            JsonNode ids = w.path("nodes");
            for (int i = 0; i + 1 < ids.size(); i++) {
                long a = ids.get(i).asLong(), b = ids.get(i + 1).asLong();
                double[] pa = pts.get(a), pb = pts.get(b);
                if (pa == null || pb == null) continue;
                double m = GeoUtil.haversine(pa[0], pa[1], pb[0], pb[1]);
                if (m < 0.01) continue;
                double sec = m / speedMps;
                g.addNode(a, pa[0], pa[1]);
                g.addNode(b, pb[0], pb[1]);
                if (fwd) g.addEdge(a, new RoadEdge(b, m, sec, sec * penalty, name, hw));
                if (bwd) g.addEdge(b, new RoadEdge(a, m, sec, sec * penalty, name, hw));
            }
        }
        g.finish();
        return g;
    }
}
