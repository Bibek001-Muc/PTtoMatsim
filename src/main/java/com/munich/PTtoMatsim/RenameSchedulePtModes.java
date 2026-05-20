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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Step 2b of the PTtoMatsim pipeline — normalises schedule modes and
 * rewrites vehicle types to match.
 *
 * <p>pt2matsim's {@link org.matsim.pt2matsim.run.Gtfs2TransitSchedule}
 * already converts GTFS route_type numbers to standard lowercase strings:</p>
 * <pre>
 *   "bus"    (route_type 3 / 700-799)
 *   "tram"   (route_type 0 / 900)
 *   "subway" (route_type 1 / 400-409  → U-Bahn in Munich)
 *   "rail"   (route_type 2 / 100-199  → S-Bahn + RE/RB in Munich)
 * </pre>
 *
 * <p>Those strings are used as-is for bus, tram and subway. The "rail"
 * bucket is split further into:</p>
 * <ul>
 *   <li><code>"sbahn"</code> — routes whose GTFS
 *       <code>route_short_name</code> matches <code>^S\d+</code>
 *       (S1…S8 in Munich)</li>
 *   <li><code>"rail"</code> — all other rail (RE, RB, IC, ICE …)</li>
 * </ul>
 *
 * <p>Modes outside these five values (ferry, funicular, …) are left
 * untouched and reported; they will be routed on artificial links.</p>
 *
 * <p>The companion vehicles file is rewritten in the same pass so that
 * vehicle types are named <code>vehicleType_bus</code>, etc. and every
 * vehicle references the correct type.</p>
 */
public final class RenameSchedulePtModes {

    public static final String MODE_BUS    = "bus";
    public static final String MODE_TRAM   = "tram";
    public static final String MODE_SUBWAY = "subway";
    public static final String MODE_SBAHN  = "sbahn";
    public static final String MODE_RAIL   = "rail";

    // S-Bahn short names: S1, S2, S20, S3 … (MVV pattern)
    private static final Pattern SBAHN_RX = Pattern.compile("^S\\d+[A-Z]?$",
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
            String shortName = firstNonBlank(
                    (String) line.getAttributes().getAttribute(
                            AdditionalTransitLineInfo.INFO_COLUMN_SHORTNAME),
                    line.getName(),
                    (String) line.getAttributes().getAttribute(
                            AdditionalTransitLineInfo.INFO_COLUMN_LONGNAME),
                    line.getId().toString());

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
     * Map the pt2matsim-assigned mode string to one of the five canonical
     * modes, using the GTFS route_short_name only to split "rail" into
     * "sbahn" vs "rail".
     */
    static String classify(String oldMode, String shortName) {
        if (oldMode == null) return MODE_BUS;
        switch (oldMode) {
            case "bus":    return MODE_BUS;
            case "tram":   return MODE_TRAM;
            case "subway": return MODE_SUBWAY;
            case "rail":
                if (shortName != null
                        && SBAHN_RX.matcher(shortName.trim()).matches()) {
                    return MODE_SBAHN;
                }
                return MODE_RAIL;
            default:
                return oldMode; // ferry / funicular / gondola / artificial
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean isCanonical(String m) {
        return MODE_BUS.equals(m) || MODE_TRAM.equals(m) || MODE_SUBWAY.equals(m)
                || MODE_SBAHN.equals(m) || MODE_RAIL.equals(m);
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
