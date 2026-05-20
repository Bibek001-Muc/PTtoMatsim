package com.munich.PTtoMatsim;

import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt2matsim.gtfs.GtfsConverter;
import org.matsim.pt2matsim.run.Gtfs2TransitSchedule;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Step 2 of the PTtoMatsim pipeline.
 *
 * <p>Converts the harmonized GTFS feed produced by the gtfs_merge step into
 * an <em>unmapped</em> MATSim transit schedule plus a default vehicles file.
 *
 * <p>pt2matsim's {@link Gtfs2TransitSchedule} expects a <em>folder</em> of
 * unpacked GTFS .txt files (its Javadoc explicitly says zips are not
 * supported), so this class transparently unzips
 * <code>../gtfs_merge/output/munich_merged.gtfs.zip</code> into
 * <code>output/gtfs_unpacked/</code> on first run.</p>
 *
 * <p>Sample-day param is <code>ALL_SERVICE_IDS</code> — every service in the
 * representative-week feed is included, matching the answer you gave at
 * project setup time.</p>
 *
 * <p>Coordinate system is DHDN_GK4 to match {@link CreateNetwork}. Both
 * sides MUST agree — otherwise stops will land hundreds of metres off the
 * network and never snap.</p>
 */
public final class CreateSchedule {

    public static final String DEFAULT_GTFS_ZIP =
            "../gtfs_merge/output/munich_merged.gtfs.zip";
    /** Fallback: place the zip here if the gtfs_merge sibling dir is absent. */
    public static final String LOCAL_GTFS_ZIP =
            "input/munich_merged.gtfs.zip";
    public static final String DEFAULT_UNPACKED_DIR = "output/gtfs_unpacked";
    public static final String DEFAULT_SCHEDULE_OUT = "output/schedule_unmapped.xml.gz";
    public static final String DEFAULT_VEHICLES_OUT = "output/vehicles_unmapped.xml.gz";

    private CreateSchedule() {}

    public static void main(String[] args) throws IOException {
        String zip = args.length > 0 ? args[0] : DEFAULT_GTFS_ZIP;
        run(zip);
    }

    public static void run(String gtfsZip) throws IOException {
        Path zipPath = Paths.get(gtfsZip).toAbsolutePath().normalize();
        if (!Files.exists(zipPath)) {
            // fallback: look for the zip placed directly in input/
            Path local = Paths.get(LOCAL_GTFS_ZIP).toAbsolutePath().normalize();
            if (Files.exists(local)) {
                zipPath = local;
            } else {
                throw new IllegalArgumentException(
                        "GTFS zip not found at either:\n"
                                + "  " + zipPath + "\n"
                                + "  " + local + "\n"
                                + "Either run the gtfs_merge step first, or copy the "
                                + "merged GTFS zip to input/munich_merged.gtfs.zip");
            }
        }

        Path unpacked = Paths.get(DEFAULT_UNPACKED_DIR).toAbsolutePath().normalize();
        unzipIfStale(zipPath, unpacked);
        patchBlankAgencyIds(unpacked);

        Path scheduleOut = Paths.get(DEFAULT_SCHEDULE_OUT).toAbsolutePath().normalize();
        Path vehiclesOut = Paths.get(DEFAULT_VEHICLES_OUT).toAbsolutePath().normalize();
        Files.createDirectories(scheduleOut.getParent());

        System.out.println("[CreateSchedule] GTFS folder    : " + unpacked);
        System.out.println("[CreateSchedule] sample day     : ALL_SERVICE_IDS");
        System.out.println("[CreateSchedule] target CRS     : " + TransformationFactory.DHDN_GK4);
        System.out.println("[CreateSchedule] schedule out   : " + scheduleOut);
        System.out.println("[CreateSchedule] vehicles out   : " + vehiclesOut);

        long t0 = System.currentTimeMillis();
        // Sample day = "all" -> every service in the merged feed survives.
        // Vehicle file emitted automatically. Info CSV not requested.
        Gtfs2TransitSchedule.run(
                unpacked.toString(),
                GtfsConverter.ALL_SERVICE_IDS,
                TransformationFactory.DHDN_GK4,
                scheduleOut.toString(),
                vehiclesOut.toString(),
                "schedule"  // attach AdditionalTransitLineInfo as attributes
                            // on each TransitLine so RenameSchedulePtModes
                            // can read route_short_name to distinguish
                            // S-Bahn / RE / RB.
        );
        System.out.println("[CreateSchedule] done in "
                + (System.currentTimeMillis() - t0) / 1000 + " s");
    }

    /**
     * Unzip {@code zip} into {@code dest} if {@code dest} does not exist or
     * if {@code zip} is newer than {@code dest}. Idempotent — re-running the
     * pipeline a second time is fast.
     */
    private static void unzipIfStale(Path zip, Path dest) throws IOException {
        Path marker = dest.resolve(".source-zip");
        String expectedMarker = zip.toAbsolutePath().normalize() + "\n"
                + Files.size(zip) + "\n"
                + Files.getLastModifiedTime(zip).toMillis() + "\n";
        boolean fresh = Files.isDirectory(dest)
                && Files.exists(dest.resolve("stops.txt"))
                && Files.exists(marker)
                && Files.readString(marker, StandardCharsets.UTF_8)
                        .equals(expectedMarker);
        if (fresh) {
            System.out.println("[CreateSchedule] reusing unpacked feed at " + dest);
            return;
        }
        Files.createDirectories(dest);
        System.out.println("[CreateSchedule] unzipping " + zip + " -> " + dest);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) {
                    throw new IOException("zip-slip detected: " + e.getName());
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zf.getInputStream(e);
                     BufferedOutputStream bo = new BufferedOutputStream(
                             new FileOutputStream(out.toFile()))) {
                    in.transferTo(bo);
                }
            }
        }
        Files.writeString(marker, expectedMarker, StandardCharsets.UTF_8);
    }

    /**
     * pt2matsim rejects routes whose agency_id field is blank, even though the
     * GTFS spec allows omitting it when there is only one agency. The MVG subset
     * of the merged feed has this issue for 131 routes (route_id prefix "mvg_").
     * This method fills those blanks with "mvv_1" in-place so the parser succeeds.
     */
    private static void patchBlankAgencyIds(Path unpackedDir) throws IOException {
        Path routesTxt = unpackedDir.resolve("routes.txt");
        if (!Files.exists(routesTxt)) return;

        List<String> lines = Files.readAllLines(routesTxt, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return;

        String header = lines.get(0);
        List<String> cols = Arrays.asList(header.split(",", -1));
        int agencyCol = cols.indexOf("agency_id");
        int routeCol  = cols.indexOf("route_id");
        if (agencyCol < 0 || routeCol < 0) return;

        int fixed = 0;
        List<String> patched = new java.util.ArrayList<>(lines.size());
        patched.add(header);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String[] fields = line.split(",", -1);
            if (fields.length > agencyCol && fields[agencyCol].isEmpty()
                    && fields[routeCol].startsWith("mvg_")) {
                fields[agencyCol] = "mvv_1";
                line = String.join(",", fields);
                fixed++;
            }
            patched.add(line);
        }

        if (fixed > 0) {
            Files.write(routesTxt, patched, StandardCharsets.UTF_8);
            System.out.println("[CreateSchedule] patched " + fixed
                    + " blank agency_id entries in routes.txt -> mvv_1");
        }
    }
}
