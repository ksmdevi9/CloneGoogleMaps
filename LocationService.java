package com.example.maps.service;

import com.example.maps.dto.LocationRequest;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores batched location updates. In-memory here; the design doc swaps this for
 * Cassandra (partition key user_id, clustering key timestamp) plus a Kafka topic.
 */
@Service
public class LocationService {
    private static final int MAX_PER_USER = 500;
    private final Map<String, Deque<LocationRequest.Loc>> store = new ConcurrentHashMap<>();

    public int record(LocationRequest req) {
        Deque<LocationRequest.Loc> q = store.computeIfAbsent(req.userId(), k -> new ArrayDeque<>());
        synchronized (q) {
            req.locs().stream().sorted(Comparator.comparingLong(LocationRequest.Loc::timestamp)).forEach(q::addLast);
            while (q.size() > MAX_PER_USER) q.pollFirst();
        }
        return req.locs().size();
    }

    public List<LocationRequest.Loc> history(String userId) {
        Deque<LocationRequest.Loc> q = store.getOrDefault(userId, new ArrayDeque<>());
        synchronized (q) { return new ArrayList<>(q); }
    }
}
