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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
    public static final String WARNING_SUMMARY    = "plausibility_warning_summary.csv";

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
        Path warningSummary = writePlausibilityWarningSummary(Paths.get(reportDir), schedule);
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
            w.write("stop_id;stop_name;modes;x;y;mapped_to_link;"
                    + "nearest_link_id;nearest_link_modes;nearest_link_distance_m\n");
            for (Map.Entry<TransitStopFacility, Set<String>> e : stopModes.entrySet()) {
                TransitStopFacility f = e.getKey();
                boolean mapped = f.getLinkId() != null;
                String modes = String.join("|", new TreeSet<>(e.getValue()));
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
                w.write(String.format(Locale.ROOT,
                        "%s;%s;%s;%.2f;%.2f;%s;%s;%s;%s%n",
                        csv(f.getId().toString()),
                        csv(f.getName()),
                        csv(modes),
                        f.getCoord().getX(),
                        f.getCoord().getY(),
                        mapped,
                        csv(nearestId),
                        csv(nearestModes),
                        Double.isNaN(nearestDist) ? "" :
                                String.format(Locale.ROOT, "%.1f", nearestDist)));
            }
        }

        // ---- (3) summary table -----------------------------------------
        int totalStops = stopModes.size();
        int totalUnmapped = (int) stopModes.keySet().stream()
                .filter(f -> f.getLinkId() == null).count();
        System.out.println();
        System.out.println("[CheckMapping] === stop snap summary ===");
        System.out.printf(Locale.ROOT, "[CheckMapping] %-8s %-8s %-8s%n",
                "mode", "stops", "unmapped");
        for (Map.Entry<String, int[]> e : byMode.entrySet()) {
            int[] c = e.getValue();
            System.out.printf(Locale.ROOT,
                    "[CheckMapping] %-8s %-8d %-8d (%4.1f%%)%n",
                    e.getKey(), c[0], c[1], 100.0 * c[1] / c[0]);
        }
        System.out.printf(Locale.ROOT, "[CheckMapping] TOTAL    %-8d %-8d (%4.1f%%)%n",
                totalStops, totalUnmapped, 100.0 * totalUnmapped / Math.max(totalStops, 1));
        System.out.println("[CheckMapping] full report  : " + UNMAPPED_REPORT);
        if (warningSummary != null) {
            System.out.println("[CheckMapping] warning csv  : " + warningSummary);
        }
        System.out.println("[CheckMapping] plausibility : " + reportDir);
    }

    private static Path writePlausibilityWarningSummary(Path reportDir,
                                                        TransitSchedule schedule)
            throws IOException {
        Path warnings = reportDir.resolve("allPlausibilityWarnings.csv");
        if (!Files.exists(warnings)) {
            System.err.println("[CheckMapping] warning CSV not found: " + warnings);
            return null;
        }

        Map<String, String> routeModes = new HashMap<>();
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                routeModes.put(route.getId().toString(), route.getTransportMode());
            }
        }

        Map<String, Integer> byType = new TreeMap<>();
        Map<String, Integer> byModeType = new TreeMap<>();
        Map<String, Integer> byLineType = new TreeMap<>();
        Map<String, Integer> byModeLineType = new TreeMap<>();

        List<String> lines = Files.readAllLines(warnings);
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",", 10);
            if (parts.length < 4) {
                continue;
            }
            String warningType = parts[1];
            String transitLine = parts[2];
            String transitRoute = parts[3];
            String mode = routeModes.getOrDefault(transitRoute, "UNKNOWN");

            inc(byType, warningType);
            inc(byModeType, mode, warningType);
            inc(byLineType, transitLine, warningType);
            inc(byModeLineType, mode, transitLine, warningType);
        }

        Path out = reportDir.resolve(WARNING_SUMMARY);
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write("summary;warning_type;mode;transit_line;count\n");
            writeSummaryRows(w, "warning_type", byType, 1);
            writeSummaryRows(w, "mode_warning_type", byModeType, 2);
            writeSummaryRows(w, "line_warning_type", byLineType, 2);
            writeSummaryRows(w, "mode_line_warning_type", byModeLineType, 3);
        }

        System.out.println();
        System.out.println("[CheckMapping] === plausibility warning summary ===");
        printTopCounts("warning type", byType, 8);
        printTopCounts("mode + warning type", byModeType, 8);
        printTopCounts("line + warning type", byLineType, 12);
        return out;
    }

    private static void inc(Map<String, Integer> map, String... parts) {
        String key = String.join("\t", parts);
        map.put(key, map.getOrDefault(key, 0) + 1);
    }

    private static void writeSummaryRows(BufferedWriter w, String summary,
                                         Map<String, Integer> counts,
                                         int keyParts) throws IOException {
        for (Map.Entry<String, Integer> e : sortedCounts(counts)) {
            String[] parts = e.getKey().split("\t", -1);
            String warningType;
            String mode = "";
            String transitLine = "";
            if (keyParts == 1) {
                warningType = parts[0];
            } else if (keyParts == 2 && summary.startsWith("mode")) {
                mode = parts[0];
                warningType = parts[1];
            } else if (keyParts == 2) {
                transitLine = parts[0];
                warningType = parts[1];
            } else {
                mode = parts[0];
                transitLine = parts[1];
                warningType = parts[2];
            }
            w.write(String.format(Locale.ROOT, "%s;%s;%s;%s;%d%n",
                    summary,
                    csv(warningType),
                    csv(mode),
                    csv(transitLine),
                    e.getValue()));
        }
    }

    private static void printTopCounts(String label, Map<String, Integer> counts,
                                       int limit) {
        System.out.println("[CheckMapping] " + label + ":");
        int n = 0;
        for (Map.Entry<String, Integer> e : sortedCounts(counts)) {
            if (n++ >= limit) break;
            System.out.printf(Locale.ROOT, "[CheckMapping]   %-48s %d%n",
                    e.getKey().replace('\t', '/'), e.getValue());
        }
    }

    private static List<Map.Entry<String, Integer>> sortedCounts(
            Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        return entries;
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
        return (t.contains(";") || t.contains(",") || t.contains("\""))
                ? '"' + t.replace("\"", "\"\"") + '"' : t;
    }
}
