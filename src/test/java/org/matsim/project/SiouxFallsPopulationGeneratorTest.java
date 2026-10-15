package org.matsim.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.network.NetworkUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the OD sampler: parsing, nested rates, departure window, hospitals.
 */
public class SiouxFallsPopulationGeneratorTest {

	private static Network tinyNetwork() {
		Network network = NetworkUtils.createNetwork();
		NetworkUtils.createAndAddNode(network, Id.createNodeId("1"), new Coord(0, 0));
		NetworkUtils.createAndAddNode(network, Id.createNodeId("2"), new Coord(1000, 0));
		NetworkUtils.createAndAddLink(network, Id.createLinkId("1_2"),
				network.getNodes().get(Id.createNodeId("1")),
				network.getNodes().get(Id.createNodeId("2")), 1000, 10, 2000, 1);
		NetworkUtils.createAndAddLink(network, Id.createLinkId("2_1"),
				network.getNodes().get(Id.createNodeId("2")),
				network.getNodes().get(Id.createNodeId("1")), 1000, 10, 2000, 1);
		for (var link : network.getLinks().values()) {
			link.setAllowedModes(Set.of("car"));
		}
		return network;
	}

	private static void writeOd(Path file) throws Exception {
		Files.writeString(file, """
				<NUMBER OF ZONES> 2
				<TOTAL OD FLOW> 300.0
				<END OF METADATA>
				Origin \t 1
				    1 :      0.0;     2 :    200.0;
				Origin \t 2
				    1 :    100.0;     2 :      0.0;
				""");
	}

	@Test
	public final void testParseSkipsIntraZonal(@TempDir Path dir) throws Exception {
		Path od = dir.resolve("trips.tntp");
		writeOd(od);
		List<SiouxFallsPopulationGenerator.OdEntry> entries =
				SiouxFallsPopulationGenerator.parseTripsFile(od);
		assertEquals(2, entries.size());
		assertEquals(300.0, entries.stream().mapToDouble(SiouxFallsPopulationGenerator.OdEntry::trips).sum(), 1e-9);
	}

	@Test
	public final void testSampleRateControlsCount() throws Exception {
		Network network = tinyNetwork();
		Path od = Files.createTempFile("trips", ".tntp");
		writeOd(od);
		List<SiouxFallsPopulationGenerator.OdEntry> entries =
				SiouxFallsPopulationGenerator.parseTripsFile(od);
		Population small = SiouxFallsPopulationGenerator.samplePopulation(network, entries,
				0.1, 4711L, 6 * 3600.0, 9 * 3600.0);
		Population large = SiouxFallsPopulationGenerator.samplePopulation(network, entries,
				0.5, 4711L, 6 * 3600.0, 9 * 3600.0);
		assertTrue(small.getPersons().size() < large.getPersons().size());
		assertTrue(large.getPersons().size() > 100 && large.getPersons().size() < 200);
	}

	@Test
	public final void testNestedAcrossRates() throws Exception {
		Network network = tinyNetwork();
		Path od = Files.createTempFile("trips", ".tntp");
		writeOd(od);
		List<SiouxFallsPopulationGenerator.OdEntry> entries =
				SiouxFallsPopulationGenerator.parseTripsFile(od);
		Set<Id<Person>> low = SiouxFallsPopulationGenerator.samplePopulation(network, entries,
				0.1, 99L, 6 * 3600.0, 9 * 3600.0).getPersons().keySet();
		Set<Id<Person>> high = SiouxFallsPopulationGenerator.samplePopulation(network, entries,
				0.5, 99L, 6 * 3600.0, 9 * 3600.0).getPersons().keySet();
		assertTrue(high.containsAll(low), "1% set must be nested in 5% set with same seed");
	}

	@Test
	public final void testDeparturesInWindow(@TempDir Path dir) throws Exception {
		Network network = tinyNetwork();
		Path od = dir.resolve("trips.tntp");
		writeOd(od);
		List<SiouxFallsPopulationGenerator.OdEntry> entries =
				SiouxFallsPopulationGenerator.parseTripsFile(od);
		Population population = SiouxFallsPopulationGenerator.samplePopulation(network, entries,
				1.0, 7L, 6 * 3600.0, 9 * 3600.0);
		assertEquals(300, population.getPersons().size());
		for (Person person : population.getPersons().values()) {
			double endTime = person.getSelectedPlan().getPlanElements().stream()
					.filter(e -> e instanceof org.matsim.api.core.v01.population.Activity)
					.map(e -> (org.matsim.api.core.v01.population.Activity) e)
					.findFirst().orElseThrow().getEndTime().seconds();
			assertTrue(endTime >= 6 * 3600.0 && endTime <= 9 * 3600.0, "departure outside 6-9am: " + endTime);
		}
	}

	@Test
	public final void testHospitalsCsv(@TempDir Path dir) throws Exception {
		Network network = tinyNetwork();
		Path out = dir.resolve("hospitals.csv");
		SiouxFallsPopulationGenerator.writeHospitals(network, List.of(1, 2), out);
		List<String> lines = Files.readAllLines(out);
		assertEquals(3, lines.size());
		assertEquals("hospital_id,node,link,x,y", lines.get(0));
		assertTrue(lines.get(1).startsWith("hospital0,1,"));
	}

	@Test
	public final void testRealOdTotal() throws Exception {
		Path od = Path.of("extra-data/SiouxFalls/SiouxFalls_trips.tntp");
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(od), "Raw OD is local-only; skipping on CI");
		List<SiouxFallsPopulationGenerator.OdEntry> entries =
				SiouxFallsPopulationGenerator.parseTripsFile(od);
		double total = entries.stream().mapToDouble(SiouxFallsPopulationGenerator.OdEntry::trips).sum();
		// 360,600 minus 24 intra-zonal zeros (all zero, skipped) minus absent pairs
		assertTrue(Math.abs(total - 360600.0) < 1e-6, "Expected 360,600 trips, was " + total);
		// 576 cells - 48 explicit zeros (24 intra-zonal + 24 absent off-diagonal) = 528 nonzero pairs.
		// (Handoff plan said "528 pairs, 24 absent implicit zeros"; the raw file
		// actually lists all 576 cells with 48 explicit zeros.)
		assertEquals(528, entries.size(), "576 cells - 48 explicit zeros");
	}
}
