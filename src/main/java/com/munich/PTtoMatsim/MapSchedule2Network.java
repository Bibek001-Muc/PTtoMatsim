package com.munich.PTtoMatsim;

import org.matsim.pt2matsim.run.PublicTransitMapper;

/**
 * Step 4 of the PTtoMatsim pipeline.
 *
 * <p>Reads the multimodal network produced by {@link CreateNetwork} and
 * the mode-renamed schedule from {@link RenameSchedulePtModes}, then runs
 * pt2matsim's {@link PublicTransitMapper} to snap every transit stop to a
 * link and route every transit route through the network. Three artifacts
 * are written (paths configured in <code>input/config.xml</code>):</p>
 *
 * <ul>
 *   <li><code>output/munich_multimodal.xml.gz</code> — the network with
 *       only links actually used by the mapped schedule (+ everything in
 *       <code>modesToKeepOnCleanUp</code>, which is "car" in our case).</li>
 *   <li><code>output/schedule_mapped.xml.gz</code> — the schedule with
 *       link references attached to every stop facility and every
 *       transit-route stop sequence.</li>
 *   <li><code>output/munich_street_only.xml.gz</code> — the car-only
 *       network filtered out of the multimodal one (useful for the
 *       car simulation downstream).</li>
 * </ul>
 *
 * <p>Unlike the original <code>MapSchedule2Network.java</code> in the
 * cloned pt2matsim repo, this driver does NOT mutate the config file on
 * disk. Everything that needs to change between runs is edited in
 * <code>input/config.xml</code> directly.</p>
 */
public final class MapSchedule2Network {

    private MapSchedule2Network() {}

    public static void main(String[] args) {
        String configFile = args.length > 0 ? args[0] : "input/config.xml";
        run(configFile);
    }

    public static void run(String configFile) {
        System.out.println("[MapSchedule2Network] config = " + configFile);
        long t0 = System.currentTimeMillis();
        PublicTransitMapper.run(configFile);
        System.out.println("[MapSchedule2Network] done in "
                + (System.currentTimeMillis() - t0) / 1000 + " s");
    }
}
