package org.matsim.project;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast validation (no simulation): the Sioux Falls config resolves and the
 * referenced network/population match the built 1% scenario.
 */
public class SiouxFallsScenarioTest {

	private static final String CONFIG = "scenarios/siouxfalls/config.xml";

	@Test
	public final void testConfigLoadsWithMatchingSampleFactors() {
		Config config = ConfigUtils.loadConfig(CONFIG);
		assertEquals(0.01, config.qsim().getFlowCapFactor(), 1e-9);
		assertEquals(0.01, config.qsim().getStorageCapFactor(), 1e-9);
		assertTrue(config.plans().getInputFile().endsWith("population-1pct.xml"));
		assertTrue(config.network().getInputFile().endsWith("network.xml"));
	}

	@Test
	public final void testScenarioLoads() {
		Config config = ConfigUtils.loadConfig(CONFIG);
		Scenario scenario = ScenarioUtils.loadScenario(config);
		assertEquals(24, scenario.getNetwork().getNodes().size());
		assertEquals(76, scenario.getNetwork().getLinks().size());
		assertEquals(3608, scenario.getPopulation().getPersons().size());
		assertTrue(Path.of("scenarios/siouxfalls/hospitals.csv").toFile().exists());
	}
}
