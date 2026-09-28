package com.example.maps.dto;

import java.util.List;

/** path: [lat, lng] pairs. Each step's pathIndex is the index in path where its maneuver happens. */
public record NavigationResponse(
        String mode,
        double distanceMeters,
        double durationSeconds,
        List<double[]> path,
        List<Step> steps,
        double originSnapMeters,
        double destSnapMeters) {

    public record Step(String instruction, String type, double distanceMeters, int pathIndex) {}
}
