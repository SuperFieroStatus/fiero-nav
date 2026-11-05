# Fiero Nav — keypad-first driving navigation for the TCL Flip 2

Turn-by-turn driving navigation built for a D-pad, not a map you pan. Same phone, constraints and build setup
as [FlipSpot](https://github.com/SuperFieroStatus/flipspot) and [Fiero Launcher](https://github.com/SuperFieroStatus/fiero-launcher): TCL Flip 2 (4058G, Android 11,
240×320, 1 GB RAM, no touch, **no Google Play services**).

## Why our own app

- **Navigation for Google Maps Go** (`com.google.android.apps.navlite` 10.74.3) is installed but refuses to run:
  "Update Google Play services" (there are none on this phone). It only accepts
  `https://www.google.com/maps/dir//…` links; `google.navigation:` resolves to OsmAnd. So no Google hand-off.
- OsmAnd / Organic Maps are map-first, touch-first; the D-pad pans the map.
- Priorities from the user: **driving**, navigation is ~80% of map use, **online is fine** (unlimited data),
  offline rarely needed.

## Hard constraints

- Every screen fully keypad-driven: lists with a highlight bar, soft keys, no map panning as the main view.
- Kotlin, plain Views, **no runtime dependencies** (HttpURLConnection + org.json). JUnit test-only.
- minSdk/targetSdk 30, compileSdk 37, release build with R8 (~100 KB), signed with the debug key.

## Screens and keys

**Where to?** (MainActivity): ▶ Resume (while guiding) · Search · Home · Work · ★ saved · recent.
- OK opens; left soft = Options (Set as Home / Work, Save, Remove); right soft = Guide (while guiding).
- **Typing any digit starts a search in the same window** (multi-tap: 2–9 letters, 0 space, 1 punctuation,
  Back deletes, then leaves search). OK searches; OK again opens the highlighted result. Right soft clears.

**Route preview** (DestinationActivity): `24 min · 16 mi · 10:36 AM`, footer "via …"; last row "Open in OsmAnd"
(offline fallback via `osmand.api://navigate?…&profile=car&force=true`). OK / right soft = Start.

**Guidance** (GuideActivity): ETA bar (arrival time · time left · distance left), big turn arrow, distance to the
turn, instruction, "Then: …" when the next turn follows within 250 m, current road.
- OK repeats the instruction aloud; `*` voice on/off; Down = upcoming turns list (Up/Back returns);
  left soft = Options (voice, upcoming turns, reroute now, stop); right soft = Stop (confirm).
- Back leaves the screen; guidance continues (foreground notification shows the next turn, voice keeps going).
- Digits and `#` are swallowed on every screen (see findings: unhandled digits open the dialer).

## Architecture

```
Places      – Photon search (photon.komoot.io), PlaceStore (Home/Work/saved/recent in prefs)
Routing     – OSRM public server (router.project-osrm.org): driving routes, steps, polyline6 geometry
Instructions– OSRM maneuver -> English text + arrow kind (pure, unit-tested)
Nav         – session: progress along the route, prompts, off-route detection + reroute, arrival, simulator
NavService  – foreground service (type location): keeps GPS + voice alive with the flip closed
Voice       – TextToSpeech (Pico), ducks music with AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
Here        – keeps a fresh GPS fix while the planning screens are open (search bias, route start)
Geo / Units – haversine, projection onto segments, polyline decode; US units (ft / mi)
```

**Progress:** each fix is projected onto the route polyline in a window from the last segment (−2…+80), giving
distance along the route. Off-route when > max(45 m, 1.5× accuracy) for 3 fixes → reroute from the current
position with the GPS heading as an OSRM `bearings` hint (8 s minimum between reroutes).

**Prompts:** per maneuver, an early heads-up ("In half a mile, …": 1600 m above ~50 mph, 800 m above ~29 mph,
else 300 m) and a final prompt at max(60 m, 7 s of travel), with "then …" for a close follow-up. After a turn,
if the next one is > 3 km away: "Continue on X for N miles". Name-only changes ("new name"/"continue"
straight) are skipped. Arrival within 25 m (or route end); the session ends a minute later.

**No traffic:** OSRM has no live traffic, so times are free-flow estimates.

## Dev loop

```
.\scripts\deploy.ps1 -Release            # build, install, grant location, launch, logcat (tag FieroNav)
gradlew testDebugUnitTest                # 14 JVM tests (AGP 9 creates unit-test tasks for debug only)
# Simulated drive (no GPS needed); optional --ef sim_speed 120 --ez sim_detour true
adb shell am start -n dev.mike.fieronav/.GuideActivity --es name Test --ef lat 30.2747 --ef lon -97.7404 --ez simulate true
# Search without typing
adb shell am start -n dev.mike.fieronav/.MainActivity --es query "coffee"
```

**Testing with key events:** never send OK blind. If Fiero Nav isn't the focused window, OK lands elsewhere
(see the dialer finding). Check `dumpsys window | grep mCurrentFocus` first.

## Milestones

**M0 — search, preview, own guidance (done 2026-11-05).** Tested on the phone: Where to? → search → preview →
simulated drive with prompts, upcoming turns, detour → "Rerouting" → new route → "You have arrived";
guidance keeps speaking with the screen off; Stop ends the service.

**v0.2.0 — public release (2026-11-05).** About row with OpenStreetMap / Photon / OSRM credits (ODbL needs
visible attribution); simulated drives can start anywhere (`--ef from_lat/from_lon`, used for the README
screenshots); each trip starts with voice on (a muted trip no longer mutes the next one). MIT license, README,
APK on GitHub Releases.

**v0.3.0 (2026-11-05).** Live traffic via TomTom Routing API v1 `calculateRoute` (`traffic=true`,
`instructionsType=text`, `sectionType=speedLimit`, `maxAlternatives=2`, `vehicleHeading` on reroutes) when a key
is set; OSRM otherwise or on any TomTom failure. TomTom maneuver codes map onto OSRM's (type, modifier) so
`Instructions` words both; unknown codes use TomTom's own `message` and are still announced. Speed (GPS) and
speed limit (TomTom `SPEED_LIMIT` sections, km/h rounded to the nearest 5 mph) on the guidance screen.
Prompts are held during calls (`LISTEN_CALL_STATE`, no permission at targetSdk 30, plus `AudioManager.mode`
for VoIP); the current instruction is repeated 1.5 s after the call ends. The key lives in app prefs, set via
`--es tomtom_key` (deploy.ps1 sends `TOMTOM_API_KEY` from local.properties), so release APKs carry no key.

**Next (ideas, in order of value):**
1. A real drive: GPS jitter at low speed, tunnels, prompt timing on highways, battery use.
2. Fiero Launcher: show the next turn in the home screen's Now Playing line while guiding.
3. Route overview map (secondary): a simple tile view, D-pad pan, zoom on 1/3.
4. Speed limits without TomTom: the public OSRM server rejects `annotations=maxspeed` ("InvalidQuery").

## Phone findings

### M0 (2026-11-05)

- **Unhandled digit keys open the dialer.** The phone turns a digit no app consumes into a DIAL intent
  (`tel:x`). A crash on the first key of a search (fixed) dropped focus back to Fiero Launcher, which dialed
  the rest; a scripted OK then hit Call (no SIM: "Cellular network not available"). Every Fiero Nav screen
  consumes digits and `#`.
- GPS works without GMS: `LocationManager.GPS_PROVIDER`, ~15 satellites outdoors, fixes within seconds when
  warm. `pm grant … ACCESS_FINE_LOCATION` from adb works on the release build.
- TTS: only **Pico** (`com.svox.pico`) is installed; `tts_default_synth` is unset but the default engine works.
  Prompts continue with the screen off (foreground service, type location).
- Photon's lat/lon bias alone ranked German "H-E-B"-like names first; searching a ±2.5° box around the user
  first fixes it. Same-named results (chains) are re-sorted nearest first, with the distance in the row.
- OSRM returns one route for many trips (`alternatives=true` is best effort). Its ramp/exit data gives
  `exits` ("235 A") and `destinations` ("I 35 North: Waco") — used for "Take exit 235 A … toward …".
- OsmAnd 5.4.10 (installed 2026-11-05, with a regional map downloaded) handles `osmand.api://` and `google.navigation:`.
  It kept GPS on in the background after use (battery): force-stop it when done.
- `Log.d` lines don't show in logcat from these release builds (`Log.i` does); KeyRouter's per-key debug log
  is therefore invisible in release.

### v0.3.0 (2026-11-05)

- OSRM demo server: `annotations=speed` works, `annotations=maxspeed` returns `InvalidQuery`, so speed limits
  come only from TomTom.
- TomTom with a bad key answers 401 `{"code":"Unauthorized","message":…}`; the fallback to OSRM was tested
  on the phone that way.
- With a real key (simulated SF drive): TomTom picked a different route (Bay St) at 21 min vs OSRM's 13 min
  free-flow, and the guidance screen showed the 30 mph limit sign with the simulator's 38 mph in orange.
- Call handling is untested on a real call (the phone has no SIM yet).
- GuideActivity is `singleTop`: a second debug start while guidance is open arrives in `onNewIntent`
  (previously ignored, the old drive kept going).
