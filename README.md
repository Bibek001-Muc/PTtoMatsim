# PTtoMatsim

Driver pipeline that turns the harmonized GTFS feed from `../gtfs_merge/`
plus the Oberbayern OSM extract into a MATSim multimodal network,
transit schedule, and vehicles file — using
[pt2matsim](https://github.com/matsim-org/pt2matsim) as a library.

## Layout

```
PTtoMatsim/
├── pom.xml                          # depends on local pt2matsim 24.5-SNAPSHOT + matsim 2024.0
├── input/
│   └── config.xml                   # OsmConverter + PublicTransitMapping config
├── output/                          # all generated artifacts land here
├── logs/
└── src/main/java/com/munich/PTtoMatsim/
    ├── CreateNetwork.java           # 1. OSM -> multimodal MATSim network
    ├── CreateSchedule.java          # 2. GTFS zip -> unmapped schedule + vehicles
    ├── RenameSchedulePtModes.java   # 3. rename modes to Bus/Tram/Sbahn/RE/Ubahn
    ├── MapSchedule2Network.java     # 4. snap schedule onto network (PTMapper)
    ├── CheckMapping.java            # 5. plausibility + unmapped-stop report
    └── Pipeline.java                # orchestrator
```

## Prerequisites

1. Build and install the pt2matsim library into your local Maven repo
   (one-off):

   ```bash
   cd ../pt2matsim
   mvn -DskipTests install
   ```

2. Provide the merged GTFS feed — either:
   - Run the gtfs_merge step so `../gtfs_merge/output/munich_merged.gtfs.zip` exists, **or**
   - Copy the zip directly to `input/munich_merged.gtfs.zip` (simpler on machines without the sibling repo).

3. Provide the Oberbayern OSM extract — either:
   - Place it at `../pt2matsim/input/oberbayern-260511.osm.gz` (default), **or**
   - Copy it to `input/oberbayern-260511.osm.gz` (simpler on machines without the sibling repo).
   The `.osm.gz` form is read directly — no need to gunzip.

## Run the whole pipeline

```bash
mvn -q -DskipTests package
mvn -q exec:java                       # runs all 5 steps
```

Outputs in `output/`:

| File                              | Step                  | Description                         |
| --------------------------------- | --------------------- | ----------------------------------- |
| `munich_network.xml.gz`           | CreateNetwork         | raw multimodal network              |
| `schedule_unmapped.xml.gz`        | CreateSchedule        | unmapped transit schedule           |
| `vehicles_unmapped.xml.gz`        | CreateSchedule        | default vehicles file               |
| `schedule_renamed.xml.gz`         | RenameSchedulePtModes | modes -> Bus/Tram/Sbahn/RE/Ubahn    |
| `vehicles_renamed.xml.gz`         | RenameSchedulePtModes | vehicle types renamed accordingly   |
| `munich_multimodal.xml.gz`        | MapSchedule2Network   | network filtered to mapped links    |
| `munich_street_only.xml.gz`       | MapSchedule2Network   | car-only sub-network                |
| `schedule_mapped.xml.gz`          | MapSchedule2Network   | schedule with link references       |
| `check_plausibility/`             | CheckMapping          | pt2matsim plausibility CSV/SHP      |
| `unmapped_stops.csv`              | CheckMapping          | per-stop snap report (Bus / Tram /...) |

Run an individual step:

```bash
mvn -q exec:java -Dexec.args="network"
mvn -q exec:java -Dexec.args="rename map check"
```

## Mode mapping (strict)

`RenameSchedulePtModes` rewrites GTFS-standard mode strings to the
five-element vocabulary configured in `input/config.xml`:

| Source signal                                    | Output mode |
| ------------------------------------------------ | ----------- |
| GTFS `route_type` = 3 (bus, 700-series)          | `Bus`       |
| GTFS `route_type` = 0 (tram, 900-series)         | `Tram`      |
| GTFS `route_type` = 1 (subway, 401, 402)         | `Ubahn`     |
| GTFS rail (2, 100-117) AND `route_short_name` matches `^S\d+$` | `Sbahn` |
| GTFS rail AND short_name matches `^U\d+$`        | `Ubahn`     |
| GTFS rail AND short_name in {RE,RB,IRE,MEX,...}  | `RE`        |
| GTFS rail AND nothing else fits                  | `RE` (default rail) |

`input/config.xml` declares the corresponding `transportModeAssignment`
sets. For the bus-lane thesis scenario, only buses are physically snapped
to the road network. Rail, tram and subway remain as schedule-based
background PT on artificial links because the OSM rail/tram topology is
not complete enough for route-level operations.

| Schedule mode | Network modes       |
| ------------- | ------------------- |
| `Bus`         | `car, bus`          |
| `Tram`        | artificial only     |
| `Sbahn`       | artificial only     |
| `RE`          | artificial only     |
| `Ubahn`       | artificial only     |

## Why several stops still won't snap

`CheckMapping` writes `output/unmapped_stops.csv`. The most common
reasons for `mapped_to_link=false` rows on the **Bus** mode in this
scenario are:

1. **`highway=pedestrian` / `highway=footway` only.** The Marienplatz /
   Karlsplatz / LMU stops sit in pedestrianised streets. The default
   pt2matsim OSM filter excludes these, so the stop has no link
   candidate within `maxLinkCandidateDistance`. Our `config.xml`
   explicitly adds `highway=pedestrian` as a bus-only way to mitigate
   this — but with low capacity and free-speed, so the routing penalty
   for using them is high.
2. **`highway=service` with `access=destination` / `access=private`.**
   Bus depots and university campuses. pt2matsim writes these as `car`
   links but they may be filtered out during `NetworkCleaner` if they
   form a disconnected cul-de-sac.
3. **`highway=bus_guideway` / `busway=*`.** Bus-only carriageways.
   Included in `config.xml` but only with `allowedTransportModes="bus"`,
   so they cannot snap a car-mode stop.
4. **`access=no` or `motor_vehicle=no` on otherwise drivable ways.**
   pt2matsim does not currently parse `access=*` modifiers — links are
   kept regardless. So this is not a snap failure cause, but it does
   produce links that shouldn't be drivable. Note for downstream.
5. **Disconnected components removed by `NetworkCleaner`.** Anything
   that doesn't reach the largest strongly-connected component of
   each mode is dropped. Small enclaves around airports, depots and
   pedestrian zones are typical victims.
6. **Stops genuinely outside the OSM extract.** The boundary clip in
   step 1 keeps full trips even when they extend outside the polygon —
   so some DB stops live beyond the `oberbayern.osm` cut. They are
   reported with `nearest_link_distance_m` blank.

For **Tram / Sbahn / Ubahn / RE** stops the snap failures usually mean
the corresponding railway / light_rail / subway / tram way is absent
or mistagged in OSM — those are reported with `nearest_link_modes`
containing only `car,bus`, which is the telltale sign of "no rail
geometry near this stop".

## Critique of the original drivers

See the chat transcript for the full list. The three short-version
fixes baked in here are:

1. **No CRS mismatch** between network and schedule (both DHDN_GK4).
2. **GTFS reader gets a folder**, not a `.zip` — `CreateSchedule`
   unzips first.
3. **Config file is not mutated on every run** — `MapSchedule2Network`
   only reads it.
