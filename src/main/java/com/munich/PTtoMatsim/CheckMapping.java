package com.munich.PTtoMatsim;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt2matsim.run.CheckMappedSchedulePlausibility;
import org.matsim.pt2matsim.tools.ScheduleTools;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Step 5 of the PTtoMatsim pipeline — validation + diagnostics.
 *
 * <p>This class does two things:</p>
 *
 * <ol>
 *   <li>Runs pt2matsim's official
 *       {@link CheckMappedSchedulePlausibility}, which writes a folder of
 *       CSV/SHP reports under <code>output/check_plausibility/</code>:
 *       loops in routes, abrupt freespeed changes, long transit-stop
 *       distances, etc.</li>
 *   <li>Adds our own "unmapped stop" report
 *       (<code>output/unmapped_stops.csv</code>): for every transit stop
 *       facility, whether it ended up with a valid <code>linkRefId</code>
 *       and, if not, the closest car/bus link in the network and its
 *       beeline distance — so we can see whether the stop was simply
 *       too far from any drivable link or whether a closer link existed
 *       but in the wrong mode subnetwork.</li>
 * </ol>
 */
public final class CheckMapping {

    public static final String DEFAULT_SCHEDULE   = "output/schedule_mapped.xml.gz";
    public static final String DEFAULT_NETWORK    = "output/munich_multimodal.xml.gz";
    public static final String DEFAULT_REPORT_DIR = "output/check_plausibility";
    public static final String UNMAPPED_REPORT    = "output/unmapped_stops.csv";

    private CheckMapping() {}

    public static void main(String[] args) throws IOException {
        run(DEFAULT_SCHEDULE, DEFAULT_NETWORK, DEFAULT_REPORT_DIR);
    }

    public static void run(String scheduleFile, String networkFile,
                           String reportDir) throws IOException {
        System.out.println("[CheckMapping] schedule = " + scheduleFile);
        System.out.println("[CheckMapping] network  = " + networkFile);
        Files.createDirectories(Paths.get(reportDir));

        // ---- (1) pt2matsim's built-in plausibility ----------------------
        try {
            CheckMappedSchedulePlausibility.run(scheduleFile, networkFile,
                    "DHDN_GK4", reportDir);
        } catch (Throwable e) {
            System.err.println("[CheckMapping] CheckMappedSchedulePlausibility failed: "
                    + e.getMessage());
        }

        // ---- (2) our own unmapped-stop diagnostic -----------------------
        TransitSchedule schedule = ScheduleTools.readTransitSchedule(scheduleFile);
        Network net = readNetwork(networkFile);

        // For each stop facility used at least once in a transit route,
        // collect the modes it is reached by. We'll diagnose by mode.
        Map<TransitStopFacility, Set<String>> stopModes = new HashMap<>();
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                String m = route.getTransportMode();
                for (TransitRouteStop trs : route.getStops()) {
                    stopModes.computeIfAbsent(trs.getStopFacility(),
                            k -> new HashSet<>()).add(m);
                }
            }
        }

        // Per-mode counters used for the final dashboard.
        Map<String, int[]> byMode = new TreeMap<>(); // mode -> [total, unmapped]

        try (BufferedWriter w = Files.newBufferedWriter(Paths.get(UNMAPPED_REPORT))) {
            w.write("stop_id,stop_name,mode,x,y,mapped_to_link,"
                    + "nearest_link_id,nearest_link_modes,nearest_link_distance_m\n");
            for (Map.Entry<TransitStopFacility, Set<String>> e : stopModes.entrySet()) {
                TransitStopFacility f = e.getKey();
                boolean mapped = f.getLinkId() != null;
                for (String mode : e.getValue()) {
                    int[] cnt = byMode.computeIfAbsent(mode, k -> new int[]{0, 0});
                    cnt[0]++;
                    if (!mapped) cnt[1]++;
                }
                String nearestId = "";
                String nearestModes = "";
                double nearestDist = Double.NaN;
                if (!mapped) {
                    Link nearest = NetworkUtils.getNearestLink(net, f.getCoord());
                    if (nearest != null) {
                        nearestId    = nearest.getId().toString();
                        nearestModes = String.join("|", nearest.getAllowedModes());
                        nearestDist  = beeline(f.getCoord(), nearest);
                    }
                }
                w.write(String.format(
                        "%s,%s,%s,%.2f,%.2f,%s,%s,%s,%s%n",
                        f.getId(),
                        csv(f.getName()),
                        String.join("|", e.getValue()),
                        f.getCoord().getX(),
                        f.getCoord().getY(),
                        mapped,
                        nearestId,
                        nearestModes,
                        Double.isNaN(nearestDist) ? "" :
                                String.format("%.1f", nearestDist)));
            }
        }

        // ---- (3) summary table -----------------------------------------
        int totalStops = stopModes.size();
        int totalUnmapped = (int) stopModes.keySet().stream()
                .filter(f -> f.getLinkId() == null).count();
        System.out.println();
        System.out.println("[CheckMapping] === stop snap summary ===");
        System.out.printf ("[CheckMapping] %-8s %-8s %-8s%n", "mode", "stops", "unmapped");
        for (Map.Entry<String, int[]> e : byMode.entrySet()) {
            int[] c = e.getValue();
            System.out.printf("[CheckMapping] %-8s %-8d %-8d (%4.1f%%)%n",
                    e.getKey(), c[0], c[1], 100.0 * c[1] / c[0]);
        }
        System.out.printf("[CheckMapping] TOTAL    %-8d %-8d (%4.1f%%)%n",
                totalStops, totalUnmapped, 100.0 * totalUnmapped / Math.max(totalStops, 1));
        System.out.println("[CheckMapping] full report  : " + UNMAPPED_REPORT);
        System.out.println("[CheckMapping] plausibility : " + reportDir);
    }

    private static Network readNetwork(String file) {
        var scn = ScenarioUtils.createScenario(
                org.matsim.core.config.ConfigUtils.createConfig());
        new MatsimNetworkReader(scn.getNetwork()).readFile(file);
        return scn.getNetwork();
    }

    private static double beeline(Coord c, Link l) {
        double mx = (l.getFromNode().getCoord().getX() + l.getToNode().getCoord().getX()) / 2.0;
        double my = (l.getFromNode().getCoord().getY() + l.getToNode().getCoord().getY()) / 2.0;
        double dx = c.getX() - mx;
        double dy = c.getY() - my;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static String csv(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.contains(",") ? '"' + t.replace("\"", "\"\"") + '"' : t;
    }
}
