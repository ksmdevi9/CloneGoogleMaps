package com.example.maps.dto;

import java.util.List;

/** Batched location updates: the client buffers points and sends them together. */
public record LocationRequest(String userId, List<Loc> locs) {
    public record Loc(double latitude, double longitude, long timestamp) {}
}
