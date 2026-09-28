package com.example.maps.service;

import org.springframework.http.HttpStatus;

public class RouteException extends RuntimeException {
    private final HttpStatus status;
    public RouteException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
}
