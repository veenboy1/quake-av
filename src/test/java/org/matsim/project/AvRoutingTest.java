package org.matsim.project;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.testcases.MatsimTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vision proof: AVs plan on the observed (damaged) network while conventional
 * drivers route damage-blind on pre-quake free-flow times.
 *
 * <p>Mixed run (avShare 0.5) with equil through-link 10 effectively closed at
 * 06:01, single iteration (initial routing only): no AV route may touch link 10
 * while conventional routes hold course (baseline: 89/100 agents use it).
 */
public class AvRoutingTest {

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

	private static final double T_QUAKE = 6 * 3600.0 + 60.0;

	private static String writeDamage(Path dir) throws Exception {
		Path file = dir.resolve("damage.csv");
		Files.writeString(file, "link_id,damage_state,freespeed_factor,capacity_factor,closed\n"
				+ "10,severe,,,true\n");
		return file.toString();
	}

	@Test
	public final void testAvAvoidsWhileConventionalHolds(@TempDir Path dir) throws Exception {
		String damage = writeDamage(dir);

		String damageDir = Path.of(utils.getOutputDirectory(), "damage").toString();
		RunAvEarthquake.runWithDamage("scenarios/equil/config-2026.xml", damageDir, 0,
				0.5, 42L, RunAvEarthquake.DEFAULT_AV_FLOW_EFFICIENCY_FACTOR,
				damage, T_QUAKE);

		String baseDir = Path.of(utils.getOutputDirectory(), "base").toString();
		RunAvEarthquake.runWithDamage("scenarios/equil/config-2026.xml", baseDir, 0,
				0.5, 42L, RunAvEarthquake.DEFAULT_AV_FLOW_EFFICIENCY_FACTOR,
				null, T_QUAKE);

		int[] damaged = countUses(damageDir);
		int[] clean = countUses(baseDir);
		// damaged = {avUses, carUses, carTotal}, clean likewise.
		assertEquals(0, damaged[0], "AVs must reroute around the damaged link");
		assertTrue(clean[1] > clean[2] / 2,
				"Sanity: conventional baseline must use link 10, was " + clean[1] + "/" + clean[2]);
		assertEquals(clean[1], damaged[1], 8,
				"Damage must not move conventional drivers (blind routing)");
		assertTrue(Files.exists(Path.of(damageDir, "output_trips.csv.zst")));
	}

	/** @return {avUses10, carUses10, carTotal} from experienced plans. */
	private static int[] countUses(String outputDir) throws Exception {
		var config = org.matsim.core.config.ConfigUtils.loadConfig("scenarios/equil/config-2026.xml");
		config.plans().setInputFile(
				Path.of(outputDir, "output_experienced_plans.xml.zst").toAbsolutePath().toString());
		var scenario = org.matsim.core.scenario.ScenarioUtils.loadScenario(config);

		int avUses = 0;
		int carUses = 0;
		int carTotal = 0;
		for (var person : scenario.getPopulation().getPersons().values()) {
			boolean isAv = person.getSelectedPlan().getPlanElements().stream()
					.anyMatch(e -> e instanceof org.matsim.api.core.v01.population.Leg leg
							&& leg.getMode().equals(AvSetup.AV_MODE));
			boolean uses10 = person.getSelectedPlan().getPlanElements().stream()
					.anyMatch(e -> e instanceof org.matsim.api.core.v01.population.Leg leg
							&& routeUsesLink(leg, "10"));
			if (isAv) {
				if (uses10) {
					avUses++;
				}
			} else {
				carTotal++;
				if (uses10) {
					carUses++;
				}
			}
		}
		return new int[]{avUses, carUses, carTotal};
	}

	private static boolean routeUsesLink(org.matsim.api.core.v01.population.Leg leg, String linkId) {
		if (!(leg.getRoute() instanceof org.matsim.core.population.routes.NetworkRoute route)) {
			return false;
		}
		if (route.getStartLinkId().toString().equals(linkId)
				|| route.getEndLinkId().toString().equals(linkId)) {
			return true;
		}
		return route.getLinkIds().stream().anyMatch(id -> id.toString().equals(linkId));
	}
}
