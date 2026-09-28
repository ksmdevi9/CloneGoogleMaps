package com.example.maps.controller;

import com.example.maps.dto.NavigationResponse;
import com.example.maps.model.TravelMode;
import com.example.maps.service.NavigationService;
import com.example.maps.service.RouteException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1")
@CrossOrigin
public class NavigationController {
    private final NavigationService service;

    public NavigationController(NavigationService service) { this.service = service; }

    /** GET /v1/nav?originLat=..&originLng=..&destLat=..&destLng=..&mode=driving|cycling|walking */
    @GetMapping("/nav")
    public NavigationResponse nav(@RequestParam double originLat, @RequestParam double originLng,
                                  @RequestParam double destLat, @RequestParam double destLng,
                                  @RequestParam(defaultValue = "driving") String mode) {
        check(originLat, originLng);
        check(destLat, destLng);
        return service.route(originLat, originLng, destLat, destLng, TravelMode.parse(mode));
    }

    private static void check(double lat, double lng) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180)
            throw new RouteException(HttpStatus.BAD_REQUEST, "Coordinates out of range.");
    }

    @ExceptionHandler(RouteException.class)
    public ResponseEntity<Map<String, String>> handle(RouteException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
