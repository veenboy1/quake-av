package org.matsim.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 "Done when": equil runs with AV agents and output shows AV vehicles
 * in use. Verifies via the written {@code output_allVehicles} file.
 */
public class RunAvEarthquakeAvTest {

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

	private static Vehicles readAllVehicles(String outputDir) {
		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		new MatsimVehicleReader(vehicles).readFile(outputDir + "/output_allVehicles.xml.zst");
		return vehicles;
	}

	@Test
	public final void testFullAvRunUsesAvVehicles() {
		String outputDir = utils.getOutputDirectory();

		RunAvEarthquake.runWithAv("scenarios/equil/config-2026.xml", outputDir, 1, 1.0, 4711L, 2.0);

		assertTrue(Files.exists(Paths.get(outputDir, "output_events.xml.zst")));
		Vehicles vehicles = readAllVehicles(outputDir);
		assertEquals(100, vehicles.getVehicles().size());
		for (Vehicle vehicle : vehicles.getVehicles().values()) {
			assertEquals(AvSetup.AV_TYPE_ID, vehicle.getType().getId(),
					"Vehicle " + vehicle.getId() + " should be type av at 100% share");
		}
	}

	@Test
	public final void testMixedAvRun() {
		String outputDir = utils.getOutputDirectory();

		RunAvEarthquake.runWithAv("scenarios/equil/config-2026.xml", outputDir, 1, 0.25, 4711L, 2.0);

		assertTrue(Files.exists(Paths.get(outputDir, "output_events.xml.zst")));
		Vehicles vehicles = readAllVehicles(outputDir);
		long avCount = vehicles.getVehicles().values().stream()
				.filter(v -> v.getType().getId().equals(AvSetup.AV_TYPE_ID))
				.count();
		long carCount = vehicles.getVehicles().values().stream()
				.filter(v -> v.getType().getId().equals(AvSetup.CAR_TYPE_ID))
				.count();
		assertTrue(avCount > 0 && carCount > 0,
				"Expected a mix of av and car vehicles, was av=" + avCount + " car=" + carCount);
		assertEquals(100, avCount + carCount);
	}
}
