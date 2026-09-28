package com.example.maps.controller;

import com.example.maps.dto.LocationRequest;
import com.example.maps.service.LocationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/locations")
@CrossOrigin
public class LocationController {
    private final LocationService service;

    public LocationController(LocationService service) { this.service = service; }

    @PostMapping
    public Map<String, Integer> post(@RequestBody LocationRequest req) {
        return Map.of("accepted", service.record(req));
    }

    @GetMapping("/{userId}")
    public List<LocationRequest.Loc> history(@PathVariable String userId) {
        return service.history(userId);
    }
}
