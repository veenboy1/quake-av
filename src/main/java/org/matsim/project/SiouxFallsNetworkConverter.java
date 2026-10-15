package org.matsim.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;

/**
 * Phase 2 (converter only): TNTP Sioux Falls files to a MATSim network.
 *
 * <p>Reads {@code SiouxFalls_net.tntp} (76 links) and {@code SiouxFalls_node.tntp}
 * (24 lon/lat nodes) from {@code extra-data/SiouxFalls/} (gitignored, local only)
 * and builds a MATSim network written to {@code scenarios/siouxfalls/network.xml}.
 *
 * <p>Unit assumptions (per handoff plan, verified 2026-10-08):
 * <ul>
 *   <li>TNTP {@code length} x {@value #METERS_PER_TNTP_LENGTH_UNIT} = meters.</li>
 *   <li>TNTP {@code free_flow_time} read as minutes, so
 *       {@code freespeed = length_m / (fft_min * 60)} m/s. In this dataset length
 *       equals fft on every link, so all links get ~16.67 m/s (60 km/h).</li>
 *   <li>TNTP {@code capacity} used directly as veh/h.</li>
 *   <li>Lanes = {@code max(1, round(capacity / 2000))}.</li>
 *   <li>Link ids are {@code from_to} (also the Phase 3 damage-mapping key).</li>
 *   <li>Node lon/lat projected equirectangular about the dataset mean
 *       (R = 6371000 m) into meters.</li>
 * </ul>
 */
public final class SiouxFallsNetworkConverter {

	private static final Logger log = LogManager.getLogger(SiouxFallsNetworkConverter.class);

	/** Meters per TNTP length unit (length values documented as arbitrary). */
	public static final double METERS_PER_TNTP_LENGTH_UNIT = 1000.0;

	/** Vehicle capacity of one lane, used to derive lane counts. */
	public static final double CAPACITY_PER_LANE = 2000.0;

	/** Earth radius for the equirectangular projection. */
	public static final double EARTH_RADIUS_M = 6371000.0;

	public static final String CAR_MODE = "car";

	private SiouxFallsNetworkConverter() {
	}

	/** One TNTP network row. */
	public record TntpLink(int from, int to, double capacity, double length, double freeFlowTime) {
	}

	/** Convert the two TNTP files into an in-memory MATSim network. */
	public static Network convert(Path netFile, Path nodeFile) throws IOException {
		Map<Integer, double[]> lonLat = parseNodeFile(nodeFile);
		List<TntpLink> links = parseNetFile(netFile);

		if (lonLat.isEmpty()) {
			throw new IllegalArgumentException("No nodes parsed from " + nodeFile);
		}
		if (links.isEmpty()) {
			throw new IllegalArgumentException("No links parsed from " + netFile);
		}

		double lon0 = lonLat.values().stream().mapToDouble(a -> a[0]).average().orElseThrow();
		double lat0 = lonLat.values().stream().mapToDouble(a -> a[1]).average().orElseThrow();

		Network network = NetworkUtils.createNetwork();

		for (Map.Entry<Integer, double[]> e : lonLat.entrySet()) {
			Coord coord = project(e.getValue()[0], e.getValue()[1], lon0, lat0);
			NetworkUtils.createAndAddNode(network, Id.createNodeId(e.getKey().toString()), coord);
		}

		for (TntpLink link : links) {
			Node from = network.getNodes().get(Id.createNodeId(Integer.toString(link.from())));
			Node to = network.getNodes().get(Id.createNodeId(Integer.toString(link.to())));
			if (from == null || to == null) {
				throw new IllegalArgumentException(
						"Link " + link.from() + "->" + link.to() + " references unknown node");
			}
			if (link.freeFlowTime() <= 0) {
				throw new IllegalArgumentException("Non-positive free-flow time on link "
						+ link.from() + "->" + link.to());
			}
			double length = link.length() * METERS_PER_TNTP_LENGTH_UNIT;
			double freespeed = length / (link.freeFlowTime() * 60.0);
			double lanes = Math.max(1, Math.round(link.capacity() / CAPACITY_PER_LANE));

			Id<Link> id = Id.createLinkId(link.from() + "_" + link.to());
			Link matsimLink = NetworkUtils.createAndAddLink(network, id, from, to,
					length, freespeed, link.capacity(), lanes);
			matsimLink.setAllowedModes(Set.of(CAR_MODE));
			NetworkUtils.setOrigId(matsimLink, link.from() + "->" + link.to());
		}

		new NetworkCleaner().run(network);

		log.info("Converted Sioux Falls: {} nodes, {} links (from {} links parsed)",
				network.getNodes().size(), network.getLinks().size(), links.size());
		return network;
	}

	/** Convert and write the MATSim network XML to {@code outputFile}. */
	public static void convertAndWrite(Path netFile, Path nodeFile, String outputFile) throws IOException {
		Network network = convert(netFile, nodeFile);
		Path out = Path.of(outputFile);
		if (out.getParent() != null) {
			Files.createDirectories(out.getParent());
		}
		NetworkUtils.writeNetwork(network, outputFile);
		log.info("Wrote Sioux Falls network to {}", outputFile);
	}

	/** Equirectangular projection of lon/lat about (lon0, lat0) into meters. */
	public static Coord project(double lon, double lat, double lon0, double lat0) {
		double x = EARTH_RADIUS_M * Math.toRadians(lon - lon0) * Math.cos(Math.toRadians(lat0));
		double y = EARTH_RADIUS_M * Math.toRadians(lat - lat0);
		return new Coord(x, y);
	}

	static Map<Integer, double[]> parseNodeFile(Path nodeFile) throws IOException {
		Map<Integer, double[]> lonLat = new LinkedHashMap<>();
		for (String raw : Files.readAllLines(nodeFile)) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("<") || line.startsWith("~")
					|| line.startsWith("Node")) {
				continue;
			}
			String[] parts = line.replace(";", " ").split("\\s+");
			if (parts.length < 3 || !Character.isDigit(parts[0].charAt(0))) {
				continue;
			}
			int id = Integer.parseInt(parts[0]);
			double lon = Double.parseDouble(parts[1]);
			double lat = Double.parseDouble(parts[2]);
			lonLat.put(id, new double[]{lon, lat});
		}
		return lonLat;
	}

	static List<TntpLink> parseNetFile(Path netFile) throws IOException {
		List<TntpLink> links = new ArrayList<>();
		for (String raw : Files.readAllLines(netFile)) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("<") || line.startsWith("~")) {
				continue;
			}
			String[] parts = line.replace(";", " ").split("\\s+");
			if (parts.length < 5 || !Character.isDigit(parts[0].charAt(0))) {
				continue;
			}
			int from = Integer.parseInt(parts[0]);
			int to = Integer.parseInt(parts[1]);
			double capacity = Double.parseDouble(parts[2]);
			double length = Double.parseDouble(parts[3]);
			double fft = Double.parseDouble(parts[4]);
			links.add(new TntpLink(from, to, capacity, length, fft));
		}
		return links;
	}

	public static void main(String[] args) throws IOException {
		Path netFile = Path.of("extra-data/SiouxFalls/SiouxFalls_net.tntp");
		Path nodeFile = Path.of("extra-data/SiouxFalls/SiouxFalls_node.tntp");
		String output = "scenarios/siouxfalls/network.xml";

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--net" -> {
					if (i + 1 < args.length) netFile = Path.of(args[++i]);
				}
				case "--nodes" -> {
					if (i + 1 < args.length) nodeFile = Path.of(args[++i]);
				}
				case "--output" -> {
					if (i + 1 < args.length) output = args[++i];
				}
				default -> log.warn("Ignoring unknown arg: {}", args[i]);
			}
		}

		convertAndWrite(netFile, nodeFile, output);
	}
}
