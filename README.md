# Maps: real-road routing + live navigation

Spring Boot backend (A* over OpenStreetMap roads) and React + Leaflet frontend. No database, no Docker.

## Requirements
JDK 17+, Maven 3.9+, Node 18+, internet access (road data comes from OpenStreetMap).

## Run
```bash
cd backend && mvn spring-boot:run        # http://localhost:8080
cd frontend && npm install && npm run dev # http://localhost:5173
```

## API
- GET  /v1/nav?originLat=&originLng=&destLat=&destLng=&mode=driving|cycling|walking
- POST /v1/locations  {"userId":"u1","locs":[{"latitude":..,"longitude":..,"timestamp":..}]}
- GET  /v1/locations/{userId}

## How it works
1. Frontend sends real coordinates (searched place, map click or GPS).
2. Backend downloads the OSM ways for that area, filtered per mode, and builds a directed graph.
3. Both points snap to the nearest node of the main road network, then A* finds the cheapest path.
4. Steps are generated from street-name changes and bearing changes.
