package com.munich.PTtoMatsim;

import org.matsim.pt2matsim.config.OsmConverterConfigGroup;
import org.matsim.pt2matsim.run.Osm2MultimodalNetwork;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

        // Fall back to input/<filename> if the configured OSM path doesn't exist
        Path osmPath = Paths.get(config.getOsmFile()).toAbsolutePath().normalize();
        if (!Files.exists(osmPath)) {
            Path local = Paths.get("input")
                    .resolve(osmPath.getFileName()).toAbsolutePath().normalize();
            if (Files.exists(local)) {
                config.setOsmFile(local.toString());
                osmPath = local;
            } else {
                throw new IllegalArgumentException(
                        "OSM file not found at either:\n"
                        + "  " + osmPath + "\n"
                        + "  " + local + "\n"
                        + "Place the OSM extract at input/" + osmPath.getFileName()
                        + " or update osmFile in input/config.xml");
            }
        }

        // Empty geometry path → AccessDeniedException on Windows (tries to write to a directory).
        if (config.getOutputDetailedLinkGeometryFile() == null
                || config.getOutputDetailedLinkGeometryFile().isBlank()) {
            config.setOutputDetailedLinkGeometryFile("output/munich_link_geometry.csv");
        }

        System.out.println("[CreateNetwork] OSM input        : " + osmPath);
        System.out.println("[CreateNetwork] output network   : " + config.getOutputNetworkFile());
        System.out.println("[CreateNetwork] CRS              : " + config.getOutputCoordinateSystem());

        long t0 = System.currentTimeMillis();
        Osm2MultimodalNetwork.run(config);
        System.out.println("[CreateNetwork] done in "
                + (System.currentTimeMillis() - t0) / 1000 + " s");
    }
}
