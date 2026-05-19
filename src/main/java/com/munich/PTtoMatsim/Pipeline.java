package com.munich.PTtoMatsim;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One-stop driver that runs the five pipeline steps in order:
 * <pre>
 *   1. CreateNetwork            OSM -> multimodal network
 *   2. CreateSchedule           GTFS zip -> unmapped schedule + vehicles
 *   3. RenameSchedulePtModes    bus/tram/subway/rail -> Bus/Tram/Ubahn/Sbahn/RE
 *   4. MapSchedule2Network      snap schedule onto network (pt2matsim PTMapper)
 *   5. CheckMapping             plausibility + unmapped-stop report
 * </pre>
 *
 * <p>Pass one or more step names on the command line to run only a
 * subset, e.g.:</p>
 * <pre>
 *   mvn -q exec:java -Dexec.args="schedule rename map check"
 * </pre>
 * Defaults to running all 5 steps when no args are given.
 */
public final class Pipeline {

    private Pipeline() {}

    public static void main(String[] args) throws IOException {
        Set<String> steps = args.length == 0
                ? new LinkedHashSet<>(Arrays.asList("network", "schedule", "rename", "map", "check"))
                : new LinkedHashSet<>(Arrays.asList(args));

        System.out.println("[Pipeline] steps = " + steps);

        if (steps.contains("network"))  CreateNetwork.run("input/config.xml");
        if (steps.contains("schedule")) CreateSchedule.run(CreateSchedule.DEFAULT_GTFS_ZIP);
        if (steps.contains("rename"))   RenameSchedulePtModes.run(
                RenameSchedulePtModes.DEFAULT_SCHEDULE_IN,
                RenameSchedulePtModes.DEFAULT_VEHICLES_IN,
                RenameSchedulePtModes.DEFAULT_SCHEDULE_OUT,
                RenameSchedulePtModes.DEFAULT_VEHICLES_OUT);
        if (steps.contains("map"))      MapSchedule2Network.run("input/config.xml");
        if (steps.contains("check"))    CheckMapping.run(
                CheckMapping.DEFAULT_SCHEDULE,
                CheckMapping.DEFAULT_NETWORK,
                CheckMapping.DEFAULT_REPORT_DIR);

        System.out.println("[Pipeline] done.");
    }
}
