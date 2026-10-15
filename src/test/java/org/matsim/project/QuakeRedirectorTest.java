package org.matsim.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.testcases.MatsimTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 tests: seeded splits plus equil redirect runs (all-home, all-hospital).
 */
public class QuakeRedirectorTest {

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

	private static final double T_QUAKE = 6 * 3600.0 + 60.0;

	@Test
	public final void testOutcomeDeterministic() {
		Id<Person> id = Id.createPersonId("somebody");
		assertEquals(
				QuakeRedirector.outcomeFor(id, 99L, 0.6, 0.17, 0.15, 0.08),
				QuakeRedirector.outcomeFor(id, 99L, 0.6, 0.17, 0.15, 0.08));
	}

	@Test
	public final void testOutcomeRoughSplits() {
		Set<String> seen = new HashSet<>();
		int home = 0;
		for (int i = 0; i < 2000; i++) {
			String outcome = QuakeRedirector.outcomeFor(Id.createPersonId("p" + i),
					9182L, 0.5, 0.15, 0.1, 0.05);
			seen.add(outcome);
			if (outcome.equals("home")) {
				home++;
			}
		}
		assertTrue(seen.containsAll(Set.of("home", "hospital", "stay", "random", "none")));
		assertTrue(home > 850 && home < 1150, "Expected ~1000 home, was " + home);
	}

	@Test
	public final void testConfigValidation() {
		var hospitals = List.of(Id.createLinkId("1"));
		assertThrows(IllegalArgumentException.class, () -> new RedirectConfig(
				30600, 1L, 0.6, 0.17, 0.15, 0.08 + 0.01, hospitals, "/tmp"));
		assertThrows(IllegalArgumentException.class, () -> new RedirectConfig(
				30600, 1L, 0.6, 0.17, 0.15, 0.08, List.of(), "/tmp"));
	}

	private static String writeHospitals(Path dir, String linkId) throws Exception {
		Path file = dir.resolve("hospitals.csv");
		Files.writeString(file, "hospital_id,node,link,x,y\nhospital0,1," + linkId + ",0,0\n");
		return file.toString();
	}

	private static void runRedirect(String outputDir, String hospitals, double home,
			double hospital, double stay, double other) {
		RunAvEarthquake.runWithRedirect("scenarios/equil/config-2026.xml", outputDir, 1,
				0.0, 0L, RunAvEarthquake.DEFAULT_AV_FLOW_EFFICIENCY_FACTOR,
				null, T_QUAKE, true, 9182L, home, hospital, stay, other, hospitals);
	}

	@Test
	public final void testAllHomeRedirect(@TempDir Path dir) throws Exception {
		String outputDir = utils.getOutputDirectory();
		runRedirect(outputDir, writeHospitals(dir, "1"), 1.0, 0.0, 0.0, 0.0);

		// 1-iteration run = iterations 0+1; the log isolates the last iteration.
		List<String> log = Files.readAllLines(Path.of(outputDir, "redirect-log.csv"));
		assertEquals(101, log.size(), "header + 100 agents of last iteration");
		assertTrue(log.get(0).startsWith("iteration,agent_id,"));
		assertTrue(log.stream().skip(1).allMatch(line ->
				line.split(",")[0].equals("1") && line.split(",")[3].equals("home")));

		assertEquals(0, countStuck(Path.of(outputDir, "output_events.xml.zst").toString()));
		assertEquals(200, countArrivals(Path.of(outputDir, "output_events.xml.zst").toString()),
				"100 agents x 1 redirected leg x 2 arrival events per leg (baseline rate)");
		assertTrue(Files.exists(Path.of(outputDir, "output_trips.csv.zst")));
	}

	@Test
	public final void testAllHospitalRedirect(@TempDir Path dir) throws Exception {
		String outputDir = utils.getOutputDirectory();
		runRedirect(outputDir, writeHospitals(dir, "1"), 0.0, 1.0, 0.0, 0.0);

		List<String> log = Files.readAllLines(Path.of(outputDir, "redirect-log.csv"));
		assertEquals(101, log.size(), "header + 100 agents of last iteration");
		assertTrue(log.stream().skip(1).allMatch(line ->
				line.split(",")[3].equals("hospital") && line.split(",")[5].equals("1")));

		assertEquals(0, countStuck(Path.of(outputDir, "output_events.xml.zst").toString()));
		assertEquals(200, countArrivals(Path.of(outputDir, "output_events.xml.zst").toString()),
				"100 agents x 1 redirected leg x 2 arrival events per leg (baseline rate)");
	}

	@Test
	public final void testAllStayRedirect(@TempDir Path dir) throws Exception {
		String outputDir = utils.getOutputDirectory();
		runRedirect(outputDir, writeHospitals(dir, "1"), 0.0, 0.0, 1.0, 0.0);

		List<String> log = Files.readAllLines(Path.of(outputDir, "redirect-log.csv"));
		assertEquals(101, log.size(), "header + 100 agents of last iteration");
		assertTrue(log.stream().skip(1).allMatch(line -> line.split(",")[3].equals("stay")));

		assertEquals(0, countStuck(Path.of(outputDir, "output_events.xml.zst").toString()));

		// Every agent must end the day in an activity (none stranded mid-leg).
		var config = org.matsim.core.config.ConfigUtils.loadConfig("scenarios/equil/config-2026.xml");
		config.plans().setInputFile(Path.of(outputDir, "output_experienced_plans.xml.zst").toAbsolutePath().toString());
		var scenario = org.matsim.core.scenario.ScenarioUtils.loadScenario(config);
		for (var person : scenario.getPopulation().getPersons().values()) {
			var elements = person.getSelectedPlan().getPlanElements();
			assertTrue(elements.get(elements.size() - 1)
					instanceof org.matsim.api.core.v01.population.Activity,
					"agent " + person.getId() + " stranded outside an activity");
		}
	}

	private static long countStuck(String eventsFile) {
		long[] count = {0};
		var manager = org.matsim.core.events.EventsUtils.createEventsManager();
		manager.addHandler((org.matsim.api.core.v01.events.handler.PersonStuckEventHandler) event -> count[0]++);
		new org.matsim.core.events.MatsimEventsReader(manager).readFile(eventsFile);
		return count[0];
	}

	private static long countArrivals(String eventsFile) {
		long[] count = {0};
		var manager = org.matsim.core.events.EventsUtils.createEventsManager();
		manager.addHandler((org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler) event -> count[0]++);
		new org.matsim.core.events.MatsimEventsReader(manager).readFile(eventsFile);
		return count[0];
	}
}
