package com.munich.PTtoMatsim;

import org.matsim.pt2matsim.config.OsmConverterConfigGroup;
import org.matsim.pt2matsim.run.Osm2MultimodalNetwork;

/**
 * Step 1 of the PTtoMatsim pipeline.
 *
 * <p>Reads the OSM extract referenced by <code>OsmConverter.osmFile</code> in
 * <code>input/config.xml</code> and writes a multimodal MATSim network
 * (<code>output/munich_network.xml.gz</code>) in the configured CRS
 * (DHDN_GK4 here).</p>
 *
 * <p>The actual conversion (StAX parse, way filtering, link creation,
 * NetworkCleaner per mode) is delegated to pt2matsim's
 * {@link Osm2MultimodalNetwork#run(OsmConverterConfigGroup)} — we just wrap
 * the entry point so the four steps can be composed by
 * {@link Pipeline} or invoked individually from the IDE.</p>
 */
public final class CreateNetwork {

    private CreateNetwork() {}

    public static void main(String[] args) {
        String configFile = args.length > 0 ? args[0] : "input/config.xml";
        run(configFile);
    }

    public static void run(String configFile) {
        System.out.println("[CreateNetwork] config = " + configFile);
        OsmConverterConfigGroup config = OsmConverterConfigGroup.loadConfig(configFile);
        System.out.println("[CreateNetwork] OSM input        : " + config.getOsmFile());
        System.out.println("[CreateNetwork] output network   : " + config.getOutputNetworkFile());
        System.out.println("[CreateNetwork] CRS              : " + config.getOutputCoordinateSystem());

        long t0 = System.currentTimeMillis();
        Osm2MultimodalNetwork.run(config);
        System.out.println("[CreateNetwork] done in "
                + (System.currentTimeMillis() - t0) / 1000 + " s");
    }
}
