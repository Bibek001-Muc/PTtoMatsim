package com.munich.PTtoMatsim;

import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt2matsim.gtfs.AdditionalTransitLineInfo;
import org.matsim.pt2matsim.tools.ScheduleTools;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;
import org.matsim.vehicles.VehiclesFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Step 3 of the PTtoMatsim pipeline — strict mode renaming.
 *
 * <p>{@link org.matsim.pt2matsim.run.Gtfs2TransitSchedule} writes
 * GTFS-standard transport-mode strings on every {@link TransitRoute}:
 * "bus", "tram", "subway", "rail", "ferry", "funicular", ... . That's not
 * fine-grained enough for our scenario — all four of S-Bahn, Regio (RE),
 * Inter-City and long-distance trains end up tagged "rail", which then
 * collapses them into one MATSim subnetwork.</p>
 *
 * <p>This class rewrites every transit route's mode to one of:</p>
 * <pre>
 *   "Bus"   "Tram"   "Sbahn"   "RE"   "Ubahn"
 * </pre>
 *
 * <p>The mapping uses two signals:</p>
 * <ol>
 *   <li>the existing mode that GtfsConverter wrote ("bus", "tram",
 *       "subway", "rail"), and</li>
 *   <li>the GTFS <code>route_short_name</code> (carried over to the
 *       TransitLine attributes when Gtfs2TransitSchedule is invoked with
 *       <code>additionalLineInfoFile = "schedule"</code>), which lets us
 *       distinguish S-Bahn (<code>^S\\d+$</code>) from RE/RB/IRE inside
 *       the "rail" bucket.</li>
 * </ol>
 *
 * <p>Lines that fall in none of the five buckets (Funicular, Ferry,
 * Gondola, Cable car) are kept but reported. Their mode stays unchanged
 * — the mapper config has no transportModeAssignment for them so they
 * will be routed on artificial links.</p>
 *
 * <p>The companion vehicles file is rewritten in the same pass: vehicle
 * types are remapped to <code>vehicleType_Bus</code>, ..., and every
 * {@link Vehicle} that referenced an old type is reassigned.</p>
 */
public final class RenameSchedulePtModes {

    public static final String MODE_BUS   = "Bus";
    public static final String MODE_TRAM  = "Tram";
    public static final String MODE_SBAHN = "Sbahn";
    public static final String MODE_RE    = "RE";
    public static final String MODE_UBAHN = "Ubahn";

    // route_short_name patterns. Case insensitive. S-Bahn ids in MVV /
    // gtfs.de use plain "S1"..."S8". U-Bahn use "U1"..."U6". RE / RB / IRE
    // are German regional brands.
    private static final Pattern SBAHN_RX = Pattern.compile("^S\\d+[A-Z]?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UBAHN_RX = Pattern.compile("^U\\d+[A-Z]?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_RX = Pattern.compile(
            "^(RE|RB|IRE|MEX|ALX|BRB|BOB|M|RJ|EC|IC|ICE)\\b.*",
            Pattern.CASE_INSENSITIVE);

    public static final String DEFAULT_SCHEDULE_IN  = "output/schedule_unmapped.xml.gz";
    public static final String DEFAULT_VEHICLES_IN  = "output/vehicles_unmapped.xml.gz";
    public static final String DEFAULT_SCHEDULE_OUT = "output/schedule_renamed.xml.gz";
    public static final String DEFAULT_VEHICLES_OUT = "output/vehicles_renamed.xml.gz";

    private RenameSchedulePtModes() {}

    public static void main(String[] args) {
        run(DEFAULT_SCHEDULE_IN, DEFAULT_VEHICLES_IN,
            DEFAULT_SCHEDULE_OUT, DEFAULT_VEHICLES_OUT);
    }

    public static void run(String inSchedule, String inVehicles,
                           String outSchedule, String outVehicles) {

        System.out.println("[RenameSchedulePtModes] reading schedule  " + inSchedule);
        TransitSchedule schedule = ScheduleTools.readTransitSchedule(inSchedule);
        Vehicles vehicles = ScheduleTools.readVehicles(inVehicles);

        Map<String, Integer> hist = new HashMap<>();
        Set<String> kept = new LinkedHashSet<>();
        Set<String> unknownExamples = new LinkedHashSet<>();
        int totalLines = 0, totalRoutes = 0;

        TransitScheduleFactory sf = schedule.getFactory();

        // -------- rewrite each TransitRoute's mode --------------------
        // Vehicle -> new mode (so we can later rewrite vehicle types)
        Map<Id<Vehicle>, String> vehicleNewMode = new HashMap<>();

        for (TransitLine line : schedule.getTransitLines().values()) {
            totalLines++;
            String shortName = (String) line.getAttributes().getAttribute(
                    AdditionalTransitLineInfo.INFO_COLUMN_SHORTNAME);
            if (shortName == null) shortName = "";

            for (TransitRoute route : new HashSet<>(line.getRoutes().values())) {
                totalRoutes++;
                String oldMode = route.getTransportMode();
                String newMode = classify(oldMode, shortName);
                hist.merge(newMode, 1, Integer::sum);

                if (!isCanonical(newMode)) {
                    if (unknownExamples.size() < 5)
                        unknownExamples.add(oldMode + " / shortName=" + shortName);
                    continue; // leave route untouched
                }
                kept.add(newMode);

                // Mutating TransportMode on a TransitRoute is not in the
                // public API; rebuild the route with the same id/stops/
                // departures but the new mode.
                TransitRoute rebuilt = sf.createTransitRoute(
                        route.getId(),
                        route.getRoute(),
                        route.getStops(),
                        newMode);
                route.getDepartures().values().forEach(d -> {
                    rebuilt.addDeparture(d);
                    if (d.getVehicleId() != null) {
                        vehicleNewMode.put(d.getVehicleId(), newMode);
                    }
                });
                rebuilt.setDescription(route.getDescription());
                // (route-level Attributes are intentionally NOT copied:
                // Gtfs2TransitSchedule does not set any, and copying them
                // would depend on Attributes#getAsMap which is only
                // present in newer MATSim versions.)
                line.removeRoute(route);
                line.addRoute(rebuilt);
            }
        }

        // -------- rewrite vehicle types -------------------------------
        rewriteVehicles(vehicles, vehicleNewMode);

        // -------- write outputs ---------------------------------------
        ScheduleTools.writeTransitSchedule(schedule, outSchedule);
        ScheduleTools.writeVehicles(vehicles, outVehicles);

        // -------- log --------------------------------------------------
        System.out.println("[RenameSchedulePtModes] lines=" + totalLines
                + ", routes=" + totalRoutes + ", routes per mode: " + hist);
        if (!unknownExamples.isEmpty()) {
            System.out.println("[RenameSchedulePtModes] non-canonical (kept as-is): "
                    + unknownExamples);
        }
        System.out.println("[RenameSchedulePtModes] wrote schedule  " + outSchedule);
        System.out.println("[RenameSchedulePtModes] wrote vehicles  " + outVehicles);
    }

    /**
     * Decide which of {Bus, Tram, Sbahn, RE, Ubahn} (or the original mode
     * if no rule fits) this route belongs to.
     */
    static String classify(String oldMode, String shortName) {
        if (oldMode == null) return MODE_BUS;
        switch (oldMode) {
            case "bus":   return MODE_BUS;
            case "tram":  return MODE_TRAM;
            case "subway": return MODE_UBAHN;
            case "rail":
                if (shortName != null) {
                    if (SBAHN_RX.matcher(shortName.trim()).matches()) return MODE_SBAHN;
                    if (UBAHN_RX.matcher(shortName.trim()).matches()) return MODE_UBAHN;
                    if (RE_RX.matcher(shortName.trim()).matches())   return MODE_RE;
                }
                // Default rail bucket = RE (regional). Long-distance ICE/IC
                // also land here, which is fine for our scenario: their
                // routing is handled by the same transportModeAssignment.
                return MODE_RE;
            default:
                return oldMode; // ferry / funicular / cable car / gondola / artificial
        }
    }

    private static boolean isCanonical(String m) {
        return MODE_BUS.equals(m) || MODE_TRAM.equals(m)
                || MODE_SBAHN.equals(m) || MODE_RE.equals(m) || MODE_UBAHN.equals(m);
    }

    /**
     * For each old VehicleType used in the schedule, ensure a renamed
     * VehicleType_$Mode exists and reassign every Vehicle referencing the
     * old type. Old types remain in the file but are unused.
     */
    private static void rewriteVehicles(Vehicles vehicles,
                                        Map<Id<Vehicle>, String> vehicleNewMode) {
        VehiclesFactory vf = vehicles.getFactory();
        Map<String, VehicleType> byMode = new HashMap<>();

        // Cache existing VehicleTypes so we don't recreate them.
        for (VehicleType vt : vehicles.getVehicleTypes().values()) {
            byMode.putIfAbsent(vt.getNetworkMode(), vt);
        }

        for (Map.Entry<Id<Vehicle>, String> e : vehicleNewMode.entrySet()) {
            Vehicle v = vehicles.getVehicles().get(e.getKey());
            if (v == null) continue;
            String mode = e.getValue();
            VehicleType vt = byMode.computeIfAbsent(mode, m -> {
                VehicleType nv = vf.createVehicleType(
                        Id.create("vehicleType_" + m, VehicleType.class));
                nv.setNetworkMode(m);
                nv.getCapacity().setSeats(50);
                nv.getCapacity().setStandingRoom(50);
                nv.setLength(15.0);
                vehicles.addVehicleType(nv);
                return nv;
            });
            // Replace vehicle so its type points at the renamed VehicleType.
            Vehicle nv = vf.createVehicle(v.getId(), vt);
            vehicles.removeVehicle(v.getId());
            vehicles.addVehicle(nv);
        }
    }
}
