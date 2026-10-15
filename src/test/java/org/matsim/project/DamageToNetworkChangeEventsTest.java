package org.matsim.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkChangeEventsParser;
import org.matsim.testcases.MatsimTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 tests: damage CSV parsing, event values, XML roundtrip, and an equil
 * integration run with a closed link (closure semantics: completes, no gridlock).
 */
public class DamageToNetworkChangeEventsTest {

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

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
		return network;
	}

	private static Path writeDamage(Path dir, String... rows) throws Exception {
		Path file = dir.resolve("damage.csv");
		List<String> lines = new ArrayList<>();
		lines.add("link_id,damage_state,freespeed_factor,capacity_factor,closed");
		lines.addAll(List.of(rows));
		Files.write(file, lines);
		return file;
	}

	@Test
	public final void testParseDegradedAndClosed(@TempDir Path dir) throws Exception {
		Path file = writeDamage(dir,
				"1_2,moderate,0.5,0.5,false",
				"2_1,severe,,,true");
		List<DamageToNetworkChangeEvents.DamageRow> rows =
				DamageToNetworkChangeEvents.parseDamageFile(file);
		assertEquals(2, rows.size());
		assertEquals(0.5, rows.get(0).freespeedFactor(), 1e-9);
		assertTrue(rows.get(1).closed());
	}

	@Test
	public final void testUnknownLinkThrows(@TempDir Path dir) throws Exception {
		Path file = writeDamage(dir, "9_9,moderate,0.5,0.5,false");
		List<DamageToNetworkChangeEvents.DamageRow> rows =
				DamageToNetworkChangeEvents.parseDamageFile(file);
		assertThrows(IllegalArgumentException.class,
				() -> DamageToNetworkChangeEvents.toEvents(tinyNetwork(), rows, 30600.0));
	}

	@Test
	public final void testEventValues(@TempDir Path dir) throws Exception {
		Path file = writeDamage(dir,
				"1_2,moderate,0.5,0.25,false",
				"2_1,severe,,,true");
		List<DamageToNetworkChangeEvents.DamageRow> rows =
				DamageToNetworkChangeEvents.parseDamageFile(file);
		List<NetworkChangeEvent> events =
				DamageToNetworkChangeEvents.toEvents(tinyNetwork(), rows, 30600.0);

		assertEquals(2, events.size());
		assertEquals(30600.0, events.get(0).getStartTime(), 1e-9);

		var degraded = events.get(0);
		assertEquals(NetworkChangeEvent.ChangeType.FACTOR, degraded.getFreespeedChange().getType());
		assertEquals(0.5, degraded.getFreespeedChange().getValue(), 1e-9);
		assertEquals(0.25, degraded.getFlowCapacityChange().getValue(), 1e-9);

		var closed = events.get(1);
		assertEquals(NetworkChangeEvent.ChangeType.FACTOR, closed.getFreespeedChange().getType());
		assertEquals(DamageToNetworkChangeEvents.CLOSED_FREESPEED_FACTOR,
				closed.getFreespeedChange().getValue(), 1e-9);
		assertEquals(DamageToNetworkChangeEvents.CLOSED_CAPACITY_FACTOR,
				closed.getFlowCapacityChange().getValue(), 1e-9);
	}

	@Test
	public final void testXmlRoundtrip(@TempDir Path dir) throws Exception {
		Network network = tinyNetwork();
		Path file = writeDamage(dir, "1_2,moderate,0.5,0.5,false");
		List<NetworkChangeEvent> events = DamageToNetworkChangeEvents.toEvents(network,
				DamageToNetworkChangeEvents.parseDamageFile(file), 30600.0);

		String xml = dir.resolve("events.xml").toString();
		DamageToNetworkChangeEvents.writeXml(events, xml);
		assertTrue(Files.exists(Path.of(xml)));

		List<NetworkChangeEvent> reread = new ArrayList<>();
		new NetworkChangeEventsParser(network, reread).readFile(xml);
		assertEquals(1, reread.size());
		assertEquals(30600.0, reread.get(0).getStartTime(), 1e-9);
		assertEquals(0.5, reread.get(0).getFreespeedChange().getValue(), 1e-9);
	}

	@Test
	public final void testDamageRunSetsTimeVariantNetwork() {
		String prevDamage = RunAvEarthquake.damageFile;
		try {
			RunAvEarthquake.damageFile = "some-damage.csv";
			var config = org.matsim.core.config.ConfigUtils.createConfig();
			RunAvEarthquake.prepareConfig(config);
			assertTrue(config.network().isTimeVariantNetwork());
			assertTrue(!config.qsim().isRemoveStuckVehicles(),
					"damage runs keep default stuck handling (mass conservation)");
		} finally {
			RunAvEarthquake.damageFile = prevDamage;
		}
	}

	@Test
	public final void testEquilDamageReroutesThroughLink(@TempDir Path dir) throws Exception {
		// Equil baseline: through-links 19/10 carry 89 morning trips each; link 1 is a
		// destination link (closing it is vacuous — arrivals happen on entry).
		// Effectively closing through-link 10 at 06:01 must reroute agents around it
		// (initial routing sees the change events): link-10 usage drops 89 -> 0,
		// every agent still arrives (mass conservation), nothing gets stuck.
		Path damage = writeDamage(dir, "10,severe,,,true");
		double tQuake = 6 * 3600.0 + 60.0;

		// NOTE: damaged run first — each run wipes its own output dir, so the
		// baseline must go into a subdirectory created afterwards.
		String outputDir = utils.getOutputDirectory();
		RunAvEarthquake.runWithDamage("scenarios/equil/config-2026.xml", outputDir, 1,
				0.0, 0L, RunAvEarthquake.DEFAULT_AV_FLOW_EFFICIENCY_FACTOR,
				damage.toString(), tQuake);

		String baseDir = java.nio.file.Path.of(outputDir, "base").toString();
		RunAvEarthquake.runWithDamage("scenarios/equil/config-2026.xml", baseDir, 1,
				0.0, 0L, RunAvEarthquake.DEFAULT_AV_FLOW_EFFICIENCY_FACTOR, null,
				DamageToNetworkChangeEvents.DEFAULT_T_QUAKE);

		Path events = Path.of(outputDir, "output_events.xml.zst");
		assertTrue(Files.exists(events), "output_events.xml.zst missing in " + outputDir);
		assertTrue(Files.exists(Path.of(outputDir, "output_trips.csv.zst")),
				"output_trips.csv.zst missing in " + outputDir);

		assertEquals(89, countLinkEntries(
				Path.of(baseDir, "output_events.xml.zst").toString(), "10"),
				"baseline link-10 usage changed; update damaged-run expectation");
		assertEquals(0, countLinkEntries(events.toString(), "10"),
				"damaged link 10 must carry no traffic");
		assertEquals(countArrivals(Path.of(baseDir, "output_events.xml.zst").toString()),
				countArrivals(events.toString()),
				"damaged run must complete as many legs as baseline (mass conservation)");
		assertEquals(0, countStuck(events.toString()),
				"no agents may be trapped");
	}

	private static long countLinkEntries(String eventsFile, String linkId) {
		long[] count = {0};
		var manager = org.matsim.core.events.EventsUtils.createEventsManager();
		manager.addHandler((org.matsim.api.core.v01.events.handler.LinkEnterEventHandler) event -> {
			if (event.getLinkId().toString().equals(linkId)) {
				count[0]++;
			}
		});
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

	private static long countStuck(String eventsFile) {
		long[] count = {0};
		var manager = org.matsim.core.events.EventsUtils.createEventsManager();
		manager.addHandler((org.matsim.api.core.v01.events.handler.PersonStuckEventHandler) event -> count[0]++);
		new org.matsim.core.events.MatsimEventsReader(manager).readFile(eventsFile);
		return count[0];
	}
}
