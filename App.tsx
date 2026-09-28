import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import L from "leaflet";
import { Circle, MapContainer, Marker, Polyline, TileLayer, useMap, useMapEvents } from "react-leaflet";
import "leaflet/dist/leaflet.css";
import { bearing, cumulative, haversine, LatLng, pointAt, projectOnPath } from "./services/geo";
import { fetchRoute, Mode, Place, reverseGeocode, Route, searchPlaces, sendLocations } from "./services/navigationApi";

type Target = "origin" | "dest";
interface Fix { lat: number; lng: number; accuracy: number; heading: number }
interface Progress { seg: number; stepIdx: number; distToNext: number; remainingM: number; remainingS: number }

const MODES: Mode[] = ["driving", "cycling", "walking"];
const GLYPH: Record<string, string> = {
  depart: "↑", straight: "↑", "slight-left": "↖", left: "←", "sharp-left": "↙",
  "slight-right": "↗", right: "→", "sharp-right": "↘", arrive: "⚑",
};

const fmtDist = (m: number) => (m < 1000 ? `${Math.max(5, Math.round(m / 5) * 5)} m` : `${(m / 1000).toFixed(1)} km`);
const fmtTime = (s: number) => {
  const min = Math.max(1, Math.round(s / 60));
  return min < 60 ? `${min} min` : `${Math.floor(min / 60)} h ${min % 60} min`;
};
const pinIcon = (cls: string, text: string) =>
  L.divIcon({ className: "", html: `<div class="pin ${cls}"><span>${text}</span></div>`, iconSize: [30, 30], iconAnchor: [15, 15] });
const meIcon = (h: number) =>
  L.divIcon({
    className: "",
    html: `<div class="me"><div class="me-arrow" style="transform:rotate(${h}deg)"></div><div class="me-dot"></div></div>`,
    iconSize: [40, 40], iconAnchor: [20, 20],
  });

function MapEvents({ onClick, onDrag }: { onClick: (p: LatLng) => void; onDrag: () => void }) {
  useMapEvents({ click: (e) => onClick([e.latlng.lat, e.latlng.lng]), dragstart: onDrag });
  return null;
}
function Follow({ pos, on }: { pos: Fix | null; on: boolean }) {
  const map = useMap();
  useEffect(() => {
    if (on && pos) map.setView([pos.lat, pos.lng], Math.max(map.getZoom(), 17), { animate: true });
  }, [pos, on, map]);
  return null;
}
function FitRoute({ route, active }: { route: Route | null; active: boolean }) {
  const map = useMap();
  useEffect(() => {
    if (route && !active) map.fitBounds(route.path, { padding: [70, 70] });
  }, [route]); // eslint-disable-line react-hooks/exhaustive-deps
  return null;
}

function PlaceField(props: {
  id: string; label: string; value: Place | null; picking: boolean;
  onPick: (p: Place) => void; onMapPick: () => void; onUseMe?: () => void;
}) {
  const { id, label, value, picking, onPick, onMapPick, onUseMe } = props;
  const [q, setQ] = useState("");
  const [results, setResults] = useState<Place[]>([]);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");
  useEffect(() => { setQ(value?.label ?? ""); setResults([]); }, [value]);

  async function search() {
    if (q.trim().length < 3) return;
    setBusy(true); setMsg("");
    try {
      const r = await searchPlaces(q.trim());
      setResults(r);
      if (!r.length) setMsg("No places found. Try a fuller name, or pick the spot on the map.");
    } catch { setMsg("Search failed. Check your connection and try again."); }
    finally { setBusy(false); }
  }

  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <div className="row">
        <input id={id} value={q} placeholder="Search a place or address" onChange={(e) => setQ(e.target.value)}
               onKeyDown={(e) => e.key === "Enter" && search()} />
        <button onClick={search} disabled={busy}>{busy ? "…" : "Search"}</button>
      </div>
      <div className="row tools">
        <button className={picking ? "on" : ""} onClick={onMapPick}>{picking ? "Click the map now" : "Pick on map"}</button>
        {onUseMe && <button onClick={onUseMe}>Use my location</button>}
      </div>
      {msg && <p className="hint">{msg}</p>}
      {results.length > 0 && (
        <ul className="results">
          {results.map((r, i) => (
            <li key={i}><button onClick={() => { onPick(r); setResults([]); }}>{r.label}</button></li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default function App() {
  const [origin, setOrigin] = useState<Place | null>(null);
  const [dest, setDest] = useState<Place | null>(null);
  const [picking, setPicking] = useState<Target | null>(null);
  const [mode, setMode] = useState<Mode>("driving");
  const [route, setRoute] = useState<Route | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [pos, setPos] = useState<Fix | null>(null);
  const [tracking, setTracking] = useState(false);
  const [navActive, setNavActive] = useState(false);
  const [simulate, setSimulate] = useState(false);
  const [follow, setFollow] = useState(false);
  const [voice, setVoice] = useState(true);
  const [arrived, setArrived] = useState(false);
  const [progress, setProgress] = useState<Progress | null>(null);

  const cum = useMemo(() => (route ? cumulative(route.path) : []), [route]);
  const routeRef = useRef<Route | null>(null);
  const cumRef = useRef<number[]>([]);
  routeRef.current = route;
  cumRef.current = cum;

  const lastFix = useRef<LatLng | null>(null);
  const lastHeading = useRef(0);
  const buffer = useRef<{ latitude: number; longitude: number; timestamp: number }[]>([]);
  const offCount = useRef(0);
  const lastReroute = useRef(0);
  const rerouting = useRef(false);
  const spokenNear = useRef(-1);

  const stopNav = useCallback(() => {
    setNavActive(false); setSimulate(false); setProgress(null);
    window.speechSynthesis?.cancel();
  }, []);

  // Fetch a route whenever start, destination or mode changes.
  useEffect(() => {
    stopNav();
    if (!origin || !dest) { setRoute(null); return; }
    let cancelled = false;
    setLoading(true); setError("");
    fetchRoute([origin.lat, origin.lng], [dest.lat, dest.lng], mode)
      .then((r) => { if (!cancelled) setRoute(r); })
      .catch((e: Error) => { if (!cancelled) { setRoute(null); setError(e.message); } })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [origin, dest, mode, stopNav]);

  // Location: one shared handler for real GPS fixes.
  const onFix = useCallback((p: GeolocationPosition) => {
    const { latitude: lat, longitude: lng, accuracy, heading } = p.coords;
    const cur: LatLng = [lat, lng];
    if (heading != null && !Number.isNaN(heading)) lastHeading.current = heading;
    else if (lastFix.current && haversine(lastFix.current, cur) > 3) lastHeading.current = bearing(lastFix.current, cur);
    if (!lastFix.current || haversine(lastFix.current, cur) > 3) lastFix.current = cur;
    buffer.current.push({ latitude: lat, longitude: lng, timestamp: Date.now() });
    setPos({ lat, lng, accuracy, heading: lastHeading.current });
  }, []);

  useEffect(() => {
    if (!tracking || simulate) return;
    if (!navigator.geolocation) { setError("This browser cannot share your location."); return; }
    const id = navigator.geolocation.watchPosition(onFix,
      (e) => setError("Your location is unavailable: " + e.message),
      { enableHighAccuracy: true, maximumAge: 1000, timeout: 20000 });
    return () => navigator.geolocation.clearWatch(id);
  }, [tracking, simulate, onFix]);

  // Batch location updates to the server every 15 s (saves battery and requests).
  useEffect(() => {
    const t = setInterval(() => {
      if (buffer.current.length) { sendLocations("demo-user", buffer.current).catch(() => {}); buffer.current = []; }
    }, 15000);
    return () => clearInterval(t);
  }, []);

  // Trip simulator: replays the route as a moving position, for testing at your desk.
  useEffect(() => {
    if (!simulate) return;
    const r = routeRef.current, c = cumRef.current;
    if (!r || !c.length) return;
    const total = c[c.length - 1];
    const step = r.mode === "driving" ? 14 : r.mode === "cycling" ? 7 : 3.5;   // metres per second of demo time
    let d = 0;
    const t = setInterval(() => {
      d = Math.min(total, d + step);
      const { pos: p, heading } = pointAt(r.path, c, d);
      setPos({ lat: p[0], lng: p[1], accuracy: 5, heading });
      if (d >= total) clearInterval(t);
    }, 1000);
    return () => clearInterval(t);
  }, [simulate]);

  const reroute = useCallback(async (from: LatLng) => {
    if (!dest || rerouting.current) return;
    rerouting.current = true; lastReroute.current = Date.now();
    try { setRoute(await fetchRoute(from, [dest.lat, dest.lng], mode)); }
    catch (e) { setError((e as Error).message); }
    finally { rerouting.current = false; }
  }, [dest, mode]);

  // Navigation progress: where am I on the route, what is next, am I off it?
  useEffect(() => {
    if (!navActive || !route || !pos || cum.length === 0) return;
    const here: LatLng = [pos.lat, pos.lng];
    const p = projectOnPath(route.path, cum, here);
    const total = cum[cum.length - 1];
    const remaining = Math.max(0, total - p.along);
    if (remaining < 25) { setArrived(true); stopNav(); return; }
    let stepIdx = route.steps.findIndex((s) => s.pathIndex > p.seg);
    if (stepIdx < 0) stepIdx = route.steps.length - 1;
    const next = route.steps[stepIdx];
    const distToNext = Math.max(0, cum[next.pathIndex] - p.along);
    setProgress({ seg: p.seg, stepIdx, distToNext, remainingM: remaining, remainingS: (route.durationSeconds * remaining) / total });

    if (voice && distToNext < 60 && spokenNear.current !== stepIdx) {
      spokenNear.current = stepIdx;
      speak(next.instruction);
    }
    if (p.dist > Math.max(40, pos.accuracy * 1.5)) {
      offCount.current += 1;
      if (offCount.current >= 2 && Date.now() - lastReroute.current > 8000) reroute(here);
    } else offCount.current = 0;
  }, [pos, navActive, route, cum]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!voice || !navActive || !progress || !route) return;
    const s = route.steps[progress.stepIdx];
    speak(s.type === "arrive" ? s.instruction : `In ${fmtDist(progress.distToNext)}, ${s.instruction}`);
  }, [progress?.stepIdx]); // eslint-disable-line react-hooks/exhaustive-deps

  function speak(text: string) {
    if (!("speechSynthesis" in window)) return;
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(new SpeechSynthesisUtterance(text));
  }

  function startNav(sim: boolean) {
    if (!route) return;
    setArrived(false); offCount.current = 0; spokenNear.current = -1;
    setTracking(true); setSimulate(sim); setNavActive(true); setFollow(true);
  }

  function setPlace(t: Target, p: Place) { (t === "origin" ? setOrigin : setDest)(p); }

  function dropPin(t: Target, ll: LatLng) {
    const place: Place = { lat: ll[0], lng: ll[1], label: `Pin at ${ll[0].toFixed(5)}, ${ll[1].toFixed(5)}` };
    setPlace(t, place);
    reverseGeocode(ll[0], ll[1]).then((name) => {
      if (!name) return;
      const upd = (cur: Place | null) => (cur && cur.lat === ll[0] && cur.lng === ll[1] ? { ...cur, label: name } : cur);
      (t === "origin" ? setOrigin : setDest)(upd);
    });
  }

  function onMapClick(ll: LatLng) {
    const t = picking ?? (!origin ? "origin" : !dest ? "dest" : null);
    if (!t) return;
    setPicking(null);
    dropPin(t, ll);
  }

  function useMyLocationAsStart() {
    setError("");
    setTracking(true); setFollow(true);
    navigator.geolocation?.getCurrentPosition(
      (p) => { onFix(p); dropPin("origin", [p.coords.latitude, p.coords.longitude]); },
      (e) => setError("Your location is unavailable: " + e.message),
      { enableHighAccuracy: true, timeout: 20000 });
  }

  const remainingPath: LatLng[] = route && progress && pos
    ? [[pos.lat, pos.lng], ...route.path.slice(progress.seg + 1)] : route?.path ?? [];
  const donePath: LatLng[] = route && progress && pos ? [...route.path.slice(0, progress.seg + 1), [pos.lat, pos.lng]] : [];
  const nextStep = route && progress ? route.steps[progress.stepIdx] : null;

  return (
    <div className={"layout" + (picking ? " picking" : "")}>
      <aside className="panel">
        <h1>Directions</h1>
        <PlaceField id="from" label="Start" value={origin} picking={picking === "origin"}
          onPick={(p) => setPlace("origin", p)} onMapPick={() => setPicking(picking === "origin" ? null : "origin")}
          onUseMe={useMyLocationAsStart} />
        <PlaceField id="to" label="Destination" value={dest} picking={picking === "dest"}
          onPick={(p) => setPlace("dest", p)} onMapPick={() => setPicking(picking === "dest" ? null : "dest")} />

        <div className="modes" role="group" aria-label="Travel mode">
          {MODES.map((m) => (
            <button key={m} className={m === mode ? "on" : ""} onClick={() => setMode(m)}>{m}</button>
          ))}
        </div>

        {!origin && !dest && <p className="hint">Search for two places, or click the map to drop your start and destination pins.</p>}
        {loading && <p className="hint">Finding the best {mode} route…</p>}
        {error && <p className="error" role="alert">{error}</p>}
        {arrived && <p className="ok">You have arrived.</p>}

        {route && (
          <section className="result">
            <p className="eta">{fmtTime(route.durationSeconds)}</p>
            <p className="dist">{fmtDist(route.distanceMeters)} by {route.mode}</p>
            {(route.originSnapMeters > 80 || route.destSnapMeters > 80) && (
              <p className="hint">Your pin is a little way from the nearest {route.mode} road, so the route joins the road network there.</p>
            )}
            <div className="row">
              <button className="go" onClick={() => startNav(false)} disabled={navActive}>Start navigation</button>
              <button onClick={() => startNav(true)} disabled={navActive} title="Replays the route as a moving dot">Simulate trip</button>
            </div>
            <ol className="steps">
              {route.steps.map((s, i) => (
                <li key={i} className={navActive && progress?.stepIdx === i ? "now" : ""}>
                  <span className="glyph" aria-hidden>{GLYPH[s.type] ?? "↑"}</span>
                  <span className="txt">{s.instruction}</span>
                  {s.distanceMeters > 0 && <span className="d">{fmtDist(s.distanceMeters)}</span>}
                </li>
              ))}
            </ol>
          </section>
        )}
        <button className="locate" onClick={() => { setTracking(true); setFollow(true); }}>
          {tracking ? "Tracking your location" : "Show my location"}
        </button>
      </aside>

      <div className="mapwrap">
        {navActive && nextStep && progress && (
          <div className="banner" role="status">
            <div className="big" aria-hidden>{GLYPH[nextStep.type] ?? "↑"}</div>
            <div className="msg">
              <strong>{fmtDist(progress.distToNext)}</strong>
              <span>{nextStep.instruction}</span>
              <small>{fmtDist(progress.remainingM)} left · {fmtTime(progress.remainingS)}{simulate ? " · simulated" : ""}</small>
            </div>
            <div className="ctl">
              <button onClick={() => setVoice(!voice)}>{voice ? "Voice on" : "Voice off"}</button>
              {!follow && <button onClick={() => setFollow(true)}>Recenter</button>}
              <button className="end" onClick={stopNav}>End</button>
            </div>
          </div>
        )}
        <MapContainer center={[17.385, 78.4867]} zoom={12} className="map">
          <TileLayer attribution="&copy; OpenStreetMap contributors" url="https://tile.openstreetmap.org/{z}/{x}/{y}.png" />
          <MapEvents onClick={onMapClick} onDrag={() => setFollow(false)} />
          <FitRoute route={route} active={navActive} />
          <Follow pos={pos} on={follow && (navActive || tracking)} />
          {donePath.length > 1 && <Polyline positions={donePath} pathOptions={{ color: "#9aa3b2", weight: 6 }} />}
          {remainingPath.length > 1 && <Polyline positions={remainingPath} pathOptions={{ color: "#0a7d5a", weight: 7, opacity: 0.9 }} />}
          {origin && <Marker position={[origin.lat, origin.lng]} icon={pinIcon("a", "A")} draggable
            eventHandlers={{ dragend: (e) => { const ll = (e.target as L.Marker).getLatLng(); dropPin("origin", [ll.lat, ll.lng]); } }} />}
          {dest && <Marker position={[dest.lat, dest.lng]} icon={pinIcon("b", "B")} draggable
            eventHandlers={{ dragend: (e) => { const ll = (e.target as L.Marker).getLatLng(); dropPin("dest", [ll.lat, ll.lng]); } }} />}
          {pos && pos.accuracy < 500 && <Circle center={[pos.lat, pos.lng]} radius={pos.accuracy} pathOptions={{ color: "#1a73e8", weight: 1, fillOpacity: 0.12 }} />}
          {pos && <Marker position={[pos.lat, pos.lng]} icon={meIcon(pos.heading)} interactive={false} zIndexOffset={1000} />}
        </MapContainer>
      </div>
    </div>
  );
}
