package org.matsim.project;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario-level tests for {@link AvSetup} (no simulation): vehicle types,
 * per-person mode-to-type mapping and AV attributes. (Vehicle instances are
 * created later by MATSim's PrepareForSim from the mapping.)
 */
public class AvSetupTest {

	private static Scenario loadEquil() {
		Config config = ConfigUtils.loadConfig("scenarios/equil/config-2026.xml");
		return ScenarioUtils.loadScenario(config);
	}

	private static Id<VehicleType> mappedType(Scenario scenario, Person person, String mode) {
		return VehicleUtils.getVehicleTypes(person).get(mode);
	}

	private static boolean allLegsHaveMode(Person person, String mode) {
		return person.getSelectedPlan().getPlanElements().stream()
				.filter(e -> e instanceof org.matsim.api.core.v01.population.Leg)
				.map(e -> (org.matsim.api.core.v01.population.Leg) e)
				.allMatch(leg -> leg.getMode().equals(mode));
	}

	@Test
	public final void testAllConventionalAtZeroShare() {
		Scenario scenario = loadEquil();
		int avCount = AvSetup.setup(scenario, 0.0, 42L, 2.0);

		assertEquals(0, avCount);
		for (Person person : scenario.getPopulation().getPersons().values()) {
			assertEquals(Boolean.FALSE, person.getAttributes().getAttribute(AvAssignment.IS_AV_ATTRIBUTE));
			assertEquals(AvSetup.CAR_TYPE_ID, mappedType(scenario, person, AvSetup.CAR_MODE));
			assertTrue(allLegsHaveMode(person, AvSetup.CAR_MODE));
		}
	}

	@Test
	public final void testAllAvAtFullShare() {
		Scenario scenario = loadEquil();
		int avCount = AvSetup.setup(scenario, 1.0, 42L, 2.0);

		assertEquals(100, avCount);
		VehicleType avType = scenario.getVehicles().getVehicleTypes().get(AvSetup.AV_TYPE_ID);
		assertEquals(2.0, avType.getFlowEfficiencyFactor(), 1e-9);
		assertEquals(1.0, avType.getPcuEquivalents(), 1e-9);
		assertEquals(AvSetup.AV_MODE, avType.getNetworkMode());
		for (Person person : scenario.getPopulation().getPersons().values()) {
			assertEquals(Boolean.TRUE, person.getAttributes().getAttribute(AvAssignment.IS_AV_ATTRIBUTE));
			assertEquals(AvSetup.AV_TYPE_ID, mappedType(scenario, person, AvSetup.AV_MODE));
			assertTrue(allLegsHaveMode(person, AvSetup.AV_MODE),
					"AV legs must use the av mode for " + person.getId());
		}
		for (var link : scenario.getNetwork().getLinks().values()) {
			assertTrue(link.getAllowedModes().contains(AvSetup.AV_MODE));
		}
	}

	@Test
	public final void testMixedShareMatchesAssignment() {
		Scenario scenario = loadEquil();
		int avCount = AvSetup.setup(scenario, 0.25, 4711L, 1.5);

		int expected = 0;
		for (Person person : scenario.getPopulation().getPersons().values()) {
			boolean expectedAv = AvAssignment.isAv(person.getId(), 0.25, 4711L);
			if (expectedAv) expected++;
			assertEquals(expectedAv,
					(Boolean) person.getAttributes().getAttribute(AvAssignment.IS_AV_ATTRIBUTE),
					"isAV mismatch for " + person.getId());
			assertEquals(expectedAv ? AvSetup.AV_TYPE_ID : AvSetup.CAR_TYPE_ID,
					mappedType(scenario, person, expectedAv ? AvSetup.AV_MODE : AvSetup.CAR_MODE),
					"type mapping mismatch for " + person.getId());
			assertTrue(allLegsHaveMode(person, expectedAv ? AvSetup.AV_MODE : AvSetup.CAR_MODE),
					"leg mode mismatch for " + person.getId());
		}
		assertEquals(expected, avCount);
		assertTrue(avCount > 0 && avCount < 100, "Expected a real mix, was " + avCount);
		assertEquals(1.5,
				scenario.getVehicles().getVehicleTypes().get(AvSetup.AV_TYPE_ID).getFlowEfficiencyFactor(), 1e-9);
	}

	@Test
	public final void testBothTypesRegistered() {
		Scenario scenario = loadEquil();
		AvSetup.setup(scenario, 0.5, 7L, 2.0);
		assertTrue(scenario.getVehicles().getVehicleTypes().containsKey(AvSetup.CAR_TYPE_ID));
		assertTrue(scenario.getVehicles().getVehicleTypes().containsKey(AvSetup.AV_TYPE_ID));
	}
}
