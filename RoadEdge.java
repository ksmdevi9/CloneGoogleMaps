package com.example.maps.model;

/** Directed road segment. seconds = real travel time, cost = what A* minimises (seconds x preference penalty). */
public record RoadEdge(long to, double meters, double seconds, double cost, String name, String highway) {}
