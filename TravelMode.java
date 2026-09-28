package com.example.maps.model;

import com.example.maps.service.RouteException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Each mode sees a different road network: which OSM ways it may use, one-way rules and speeds. */
public enum TravelMode {
    DRIVING(
        "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|service",
        "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link",
        40, 8, 400, 2500),
    CYCLING(
        "trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|service|cycleway|path|track",
        "trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|cycleway",
        20, 8, 400, 2500),
    WALKING(
        "trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|service|pedestrian|footway|path|steps|track|corridor",
        "trunk|primary|secondary|tertiary|unclassified|residential|footway|path|steps",
        10, 1000, 400, 400);

    private final String detailed, major;
    private final double maxKm, majorAboveKm, snapMeters, majorSnapMeters;

    TravelMode(String detailed, String major, double maxKm, double majorAboveKm, double snapMeters, double majorSnapMeters) {
        this.detailed = detailed; this.major = major; this.maxKm = maxKm;
        this.majorAboveKm = majorAboveKm; this.snapMeters = snapMeters; this.majorSnapMeters = majorSnapMeters;
    }

    public double maxKm() { return maxKm; }
    public double majorAboveKm() { return majorAboveKm; }
    public double snapMeters() { return snapMeters; }
    public double majorSnapMeters() { return majorSnapMeters; }
    public String highwayRegex(boolean useMajor) { return useMajor ? major : detailed; }
    public String label() { return name().toLowerCase(); }

    public static TravelMode parse(String s) {
        return switch (s.toLowerCase()) {
            case "driving" -> DRIVING;
            case "cycling" -> CYCLING;
            case "walking" -> WALKING;
            default -> throw new RouteException(HttpStatus.BAD_REQUEST, "Unknown travel mode: " + s);
        };
    }

    /** Access tags: mode-specific tag wins, then the generic access tag. */
    public boolean allows(JsonNode tags) {
        String specific = switch (this) { case DRIVING -> "motor_vehicle"; case CYCLING -> "bicycle"; case WALKING -> "foot"; };
        String v = tags.path(specific).asText("");
        if (v.equals("no") || v.equals("private")) return false;
        if (v.equals("yes") || v.equals("designated") || v.equals("permissive")) return true;
        if (this == DRIVING && tags.path("vehicle").asText("").equals("no")) return false;
        String access = tags.path("access").asText("");
        return !(access.equals("no") || access.equals("private"));
    }

    public double speedKmh(String hw, String maxspeed) {
        switch (this) {
            case WALKING: return hw.equals("steps") ? 3 : 5;
            case CYCLING: return (hw.equals("path") || hw.equals("track")) ? 12 : 15;
            default:
                Matcher m = Pattern.compile("^(\\d+)").matcher(maxspeed);
                if (m.find()) {
                    double v = Double.parseDouble(m.group(1));
                    if (maxspeed.contains("mph")) v *= 1.609;
                    return Math.max(5, v * 0.75);
                }
                return switch (hw) {
                    case "motorway" -> 80;
                    case "trunk" -> 60;
                    case "motorway_link", "trunk_link" -> 40;
                    case "primary", "primary_link" -> 45;
                    case "secondary", "secondary_link" -> 40;
                    case "tertiary", "tertiary_link" -> 35;
                    case "unclassified" -> 30;
                    case "residential" -> 25;
                    case "living_street" -> 10;
                    case "service" -> 15;
                    default -> 25;
                };
        }
    }

    /** Route-choice preference (>= 1). Does not change the reported travel time. */
    public double penalty(String hw) {
        if (this == DRIVING && hw.equals("service")) return 2.0;
        if (this == CYCLING && (hw.startsWith("trunk") || hw.startsWith("primary"))) return 1.4;
        if (this == WALKING && (hw.startsWith("trunk") || hw.startsWith("primary"))) return 1.2;
        return 1.0;
    }
}
