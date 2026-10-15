package org.matsim.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.io.PopulationWriter;

/**
 * Phase 2 (population): sample a home-work-home population from the TNTP OD matrix.
 *
 * <p>The sample rate is a runtime parameter (not hardcoded) so runtime can be
 * scaled to the machine: 0.1% (~360 agents, laptop smoke test), 1% (~3,600,
 * laptop default), 5-10% (~18-36k, server), 100% (360,600, mini-server/cluster
 * test). Sampling is <strong>nested</strong>: per-trip draws reuse
 * {@link AvAssignment#drawUniform}, so every agent at 1% is also present at 5%
 * with the same seed. Set the config's {@code flowCapacityFactor} and
 * {@code storageCapacityFactor} to the same sample rate.
 *
 * <p>Hospitals (default nodes {@value #DEFAULT_HOSPITAL_NODES}, the max-spread
 * triple covering NW / SW / E) are written to a small CSV
 * (hospital_id, node, link, x, y) consumed by the Phase 4 redirector.
 */
public final class SiouxFallsPopulationGenerator {

	private static final Logger log = LogManager.getLogger(SiouxFallsPopulationGenerator.class);

	/** Default sample rate: 1% of the 360,600-trip OD matrix (~3.6k agents). */
	public static final double DEFAULT_SAMPLE = 0.01;

	public static final long DEFAULT_SEED = 4711L;

	/** Max-min-spread triple (pairwise >= ~9.6 km): NW / SW / E coverage. */
	public static final String DEFAULT_HOSPITAL_NODES = "1,13,18";

	public static final double DEFAULT_DEPARTURE_START = 6 * 3600.0;

	public static final double DEFAULT_DEPARTURE_END = 9 * 3600.0;

	/** Salt separating the departure draw from the inclusion draw. */
	private static final long DEPARTURE_SALT = 0x5DEECE66DL;

	/** Mean work duration before the return-home leg. */
	public static final double WORK_DURATION = 8 * 3600.0;

	private SiouxFallsPopulationGenerator() {
	}

	/** One OD demand entry (trips per day). */
	public record OdEntry(int origin, int destination, double trips) {
	}

	/** Parse a TNTP trips file into OD entries (skips zero-demand and intra-zonal). */
	static List<OdEntry> parseTripsFile(Path tripsFile) throws IOException {
		List<OdEntry> entries = new ArrayList<>();
		int origin = -1;
		for (String raw : Files.readAllLines(tripsFile)) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("<") || line.startsWith("~")) {
				continue;
			}
			if (line.startsWith("Origin")) {
				String[] parts = line.split("\\s+");
				origin = Integer.parseInt(parts[parts.length - 1]);
				continue;
			}
			if (origin < 0) {
				continue;
			}
			// Demand lines look like "1 : 0.0; 2 : 100.0; ..."
			String[] tokens = line.split("\\s+");
			for (int i = 0; i + 2 < tokens.length; i += 3) {
				try {
					int dest = Integer.parseInt(tokens[i]);
					double value = Double.parseDouble(tokens[i + 2].replace(";", ""));
					if (value > 0 && dest != origin) {
						entries.add(new OdEntry(origin, dest, value));
					}
				} catch (NumberFormatException e) {
					log.warn("Skipping unparsable OD token in line: {}", line);
					break;
				}
			}
		}
		return entries;
	}

	/**
	 * Sample persons nested across rates: trip {@code i} of pair (o,d) is included
	 * iff {@code drawUniform("o-d-i", seed) < sample}.
	 */
	static Population samplePopulation(Network network, List<OdEntry> odEntries,
			double sample, long seed, double departureStart, double departureEnd) {
		if (sample <= 0.0 || sample > 1.0) {
			throw new IllegalArgumentException("sample must be in (0,1], was " + sample);
		}
		Population population = org.matsim.core.population.PopulationUtils.createPopulation(
				ConfigUtils.createConfig(), network);
		PopulationFactory factory = population.getFactory();

		Map<Integer, Link> homeLinks = new LinkedHashMap<>();
		for (OdEntry entry : odEntries) {
			int trips = (int) Math.round(entry.trips());
			for (int i = 0; i < trips; i++) {
				Id<Person> tripId = Id.createPersonId(
						entry.origin() + "-" + entry.destination() + "-" + i);
				if (AvAssignment.drawUniform(tripId, seed) >= sample) {
					continue;
				}
				Id<Person> personId = Id.createPersonId(
						"sf-" + entry.origin() + "-" + entry.destination() + "-" + i);
				Person person = factory.createPerson(personId);

				double departure = departureStart + AvAssignment.drawUniform(personId,
						seed ^ DEPARTURE_SALT) * (departureEnd - departureStart);

				Link homeLink = homeLinks.computeIfAbsent(entry.origin(),
						node -> nearestLink(network, node));
				Link workLink = homeLinks.computeIfAbsent(entry.destination(),
						node -> nearestLink(network, node));

				Plan plan = factory.createPlan();

				Activity home = factory.createActivityFromLinkId("h", homeLink.getId());
				home.setCoord(homeLink.getCoord());
				home.setEndTime(departure);
				plan.addActivity(home);

				Leg outbound = factory.createLeg("car");
				plan.addLeg(outbound);

				Activity work = factory.createActivityFromLinkId("w", workLink.getId());
				work.setCoord(workLink.getCoord());
				work.setMaximumDuration(WORK_DURATION);
				plan.addActivity(work);

				Leg inbound = factory.createLeg("car");
				plan.addLeg(inbound);

				Activity home2 = factory.createActivityFromLinkId("h", homeLink.getId());
				home2.setCoord(homeLink.getCoord());
				plan.addActivity(home2);

				person.addPlan(plan);
				population.addPerson(person);
			}
		}
		log.info("Sampled {}/{} OD trips at rate {} (seed {})",
				population.getPersons().size(), odEntries.stream().mapToDouble(OdEntry::trips).sum(),
				sample, seed);
		return population;
	}

	private static Link nearestLink(Network network, int nodeId) {
		var node = network.getNodes().get(Id.createNodeId(Integer.toString(nodeId)));
		if (node == null) {
			throw new IllegalArgumentException("Unknown node " + nodeId);
		}
		Coord coord = node.getCoord();
		Link link = NetworkUtils.getNearestLink(network, coord);
		if (link == null) {
			throw new IllegalStateException("No link near node " + nodeId);
		}
		return link;
	}

	/** Write hospitals.csv (hospital_id, node, link, x, y) for the Phase 4 redirector. */
	static void writeHospitals(Network network, List<Integer> hospitalNodes, Path output) throws IOException {
		StringBuilder sb = new StringBuilder("hospital_id,node,link,x,y\n");
		for (int n = 0; n < hospitalNodes.size(); n++) {
			int nodeId = hospitalNodes.get(n);
			Link link = nearestLink(network, nodeId);
			Coord coord = network.getNodes().get(Id.createNodeId(Integer.toString(nodeId))).getCoord();
			sb.append("hospital").append(n).append(",").append(nodeId).append(",")
					.append(link.getId()).append(",").append(coord.getX()).append(",")
					.append(coord.getY()).append("\n");
		}
		if (output.getParent() != null) {
			Files.createDirectories(output.getParent());
		}
		Files.writeString(output, sb.toString());
	}

	static List<Integer> parseHospitalNodes(String csv) {
		List<Integer> nodes = new ArrayList<>();
		for (String part : csv.split(",")) {
			nodes.add(Integer.parseInt(part.trim()));
		}
		return nodes;
	}

	public static void main(String[] args) throws IOException {
		Path odFile = Path.of("extra-data/SiouxFalls/SiouxFalls_trips.tntp");
		Path networkFile = Path.of("scenarios/siouxfalls/network.xml");
		String plansOutput = "scenarios/siouxfalls/population-1pct.xml";
		String hospitalsOutput = "scenarios/siouxfalls/hospitals.csv";
		double sample = DEFAULT_SAMPLE;
		long seed = DEFAULT_SEED;
		String hospitalNodes = DEFAULT_HOSPITAL_NODES;
		double departureStart = DEFAULT_DEPARTURE_START;
		double departureEnd = DEFAULT_DEPARTURE_END;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--od" -> {
					if (i + 1 < args.length) odFile = Path.of(args[++i]);
				}
				case "--network" -> {
					if (i + 1 < args.length) networkFile = Path.of(args[++i]);
				}
				case "--plans" -> {
					if (i + 1 < args.length) plansOutput = args[++i];
				}
				case "--hospitals" -> {
					if (i + 1 < args.length) hospitalsOutput = args[++i];
				}
				case "--sample" -> {
					if (i + 1 < args.length) sample = Double.parseDouble(args[++i]);
				}
				case "--seed" -> {
					if (i + 1 < args.length) seed = Long.parseLong(args[++i]);
				}
				case "--hospitalNodes" -> {
					if (i + 1 < args.length) hospitalNodes = args[++i];
				}
				default -> log.warn("Ignoring unknown arg: {}", args[i]);
			}
		}

		Network network = NetworkUtils.readNetwork(networkFile.toString());
		List<OdEntry> odEntries = parseTripsFile(odFile);
		Population population = samplePopulation(network, odEntries, sample, seed,
				departureStart, departureEnd);

		Path plansPath = Path.of(plansOutput);
		if (plansPath.getParent() != null) {
			Files.createDirectories(plansPath.getParent());
		}
		new PopulationWriter(population, network).write(plansOutput);
		writeHospitals(network, parseHospitalNodes(hospitalNodes), Path.of(hospitalsOutput));
		log.info("Wrote {} persons to {} and hospitals to {}", population.getPersons().size(),
				plansOutput, hospitalsOutput);
	}
}
