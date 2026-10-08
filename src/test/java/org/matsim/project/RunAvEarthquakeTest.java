package org.matsim.project;

import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.testcases.MatsimTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 smoke test: the custom {@link RunAvEarthquake} run class reproduces
 * the equil baseline for 1 iteration.
 */
public class RunAvEarthquakeTest {

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	public final void testEquilOneIteration() {
		String outputDir = utils.getOutputDirectory();

		RunAvEarthquake.run("scenarios/equil/config-2026.xml", outputDir, 1);

		assertTrue(Files.exists(Paths.get(outputDir, "output_events.xml.zst")),
				"output_events.xml.zst missing in " + outputDir);
		assertTrue(Files.exists(Paths.get(outputDir, "output_plans.xml.zst")),
				"output_plans.xml.zst missing in " + outputDir);
		assertTrue(Files.exists(Paths.get(outputDir, "output_trips.csv.zst")),
				"output_trips.csv.zst missing in " + outputDir);
	}
}
