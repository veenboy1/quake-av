package org.matsim.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Converter-only tests: synthetic TNTP snippets plus the real Sioux Falls files.
 */
public class SiouxFallsNetworkConverterTest {

	private static void write(Path file, String... lines) throws Exception {
		Files.writeString(file, String.join("\n", lines) + "\n");
	}

	private static Network convertSynthetic(Path dir) throws Exception {
		write(dir.resolve("nodes.tntp"),
				"<NUMBER OF NODES> 3",
				"<END OF METADATA>",
				"Node	X	Y	;",
				"1	-96.0	43.0	;",
				"2	-96.01	43.0	;",
				"3	-96.0	43.01	;");
		write(dir.resolve("net.tntp"),
				"<NUMBER OF LINKS> 4",
				"<END OF METADATA>",
				"~	init_node	term_node	capacity	length	free_flow_time	b	power	speed	toll	link_type	;",
				"1	2	4000	4	4	0.15	4	0	0	1	;",
				"2	1	4000	4	4	0.15	4	0	0	1	;",
				"2	3	5200	2	2	0.15	4	0	0	1	;",
				"3	1	1000	10	10	0.15	4	0	0	1	;");
		return SiouxFallsNetworkConverter.convert(dir.resolve("net.tntp"), dir.resolve("nodes.tntp"));
	}

	@Test
	public final void testSyntheticNodeAndLinkCounts(@TempDir Path dir) throws Exception {
		Network network = convertSynthetic(dir);
		assertEquals(3, network.getNodes().size());
		assertEquals(4, network.getLinks().size());
	}

	@Test
	public final void testLinkAttributes(@TempDir Path dir) throws Exception {
		Network network = convertSynthetic(dir);

		Link link = network.getLinks().get(Id.createLinkId("1_2"));
		assertEquals(4000.0, link.getCapacity(), 1e-9);
		assertEquals(4000.0, link.getLength(), 1e-9);
		// 4000 m / (4 min * 60) = 16.666... m/s
		assertEquals(4000.0 / 240.0, link.getFreespeed(), 1e-9);
		assertEquals(2.0, link.getNumberOfLanes(), 1e-9);
		assertEquals(Set.of("car"), link.getAllowedModes());

		// 5200/2000 = 2.6 -> 3 lanes; 1000/2000 = 0.5 -> min 1 lane
		assertEquals(3.0, network.getLinks().get(Id.createLinkId("2_3")).getNumberOfLanes(), 1e-9);
		assertEquals(1.0, network.getLinks().get(Id.createLinkId("3_1")).getNumberOfLanes(), 1e-9);
	}

	@Test
	public final void testNodesProjectedToMeters(@TempDir Path dir) throws Exception {
		Network network = convertSynthetic(dir);
		// 0.01 deg lon at lat 43 ~ 810 m; 0.01 deg lat ~ 1112 m. Just check meter scale.
		double x1 = network.getNodes().get(Id.createNodeId("1")).getCoord().getX();
		double x2 = network.getNodes().get(Id.createNodeId("2")).getCoord().getX();
		assertTrue(Math.abs(x2 - x1) > 500 && Math.abs(x2 - x1) < 1200,
				"Expected ~810 m separation, was " + Math.abs(x2 - x1));
	}

	@Test
	public final void testWriteAndReread(@TempDir Path dir) throws Exception {
		convertSynthetic(dir);
		String out = dir.resolve("network.xml").toString();
		SiouxFallsNetworkConverter.convertAndWrite(dir.resolve("net.tntp"), dir.resolve("nodes.tntp"), out);
		assertTrue(Files.exists(Path.of(out)));
		Network reread = NetworkUtils.readNetwork(out);
		assertEquals(3, reread.getNodes().size());
		assertEquals(4, reread.getLinks().size());
	}

	@Test
	public final void testRealSiouxFallsFiles() throws Exception {
		Path netFile = Path.of("extra-data/SiouxFalls/SiouxFalls_net.tntp");
		Path nodeFile = Path.of("extra-data/SiouxFalls/SiouxFalls_node.tntp");
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(netFile) && Files.exists(nodeFile),
				"Raw Sioux Falls data is local-only (gitignored); skipping on CI");
		Network network = SiouxFallsNetworkConverter.convert(netFile, nodeFile);
		assertEquals(24, network.getNodes().size(), "Sioux Falls has 24 nodes");
		assertEquals(76, network.getLinks().size(), "Sioux Falls has 76 links");
		// Spot-check uniform freespeed (length == fft everywhere -> 60 km/h)
		for (Link link : network.getLinks().values()) {
			assertEquals(1000.0 / 60.0, link.getFreespeed(), 1e-6);
			assertEquals(Set.of("car"), link.getAllowedModes());
		}
	}
}
