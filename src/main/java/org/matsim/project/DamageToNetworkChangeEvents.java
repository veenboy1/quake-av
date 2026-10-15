package org.matsim.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkChangeEventsWriter;

/**
 * Phase 3: per-link earthquake damage CSV (see {@code docs/damage-format.md})
 * to MATSim {@link NetworkChangeEvent}s at {@code t_quake}.
 *
 * <p>Degraded links get {@code FACTOR} freespeed/capacity changes; closed links
 * get an effective-closure trickle ({@link #CLOSED_FREESPEED_FACTOR} /
 * {@link #CLOSED_CAPACITY_FACTOR}) rather than absolute zero — zero-capacity
 * mid-run closures hang the QSim irrecoverably (see
 * {@code docs/damage-format.md}). One event per CSV row.
 */
public final class DamageToNetworkChangeEvents {

	private static final Logger log = LogManager.getLogger(DamageToNetworkChangeEvents.class);

	/** Default quake time: 08:30, inside the 6-9am departure window. */
	public static final double DEFAULT_T_QUAKE = 8.5 * 3600.0;

	/**
	 * Effective-closure factors applied to {@code closed=true} rows. True
	 * zero-capacity closures hang the QSim irrecoverably (see
	 * {@code docs/damage-format.md}): fixed-route agents queue behind the closure
	 * and node-waiters are never stuck-removed, so the run never finishes — at any
	 * scale, under every stuck configuration tried. A 2%-capacity / 30%-speed
	 * trickle keeps every agent moving (no traps) while pricing the link out of
	 * future routes. Revisit true closures with Phase 4 within-day rerouting.
	 */
	public static final double CLOSED_CAPACITY_FACTOR = 0.02;

	public static final double CLOSED_FREESPEED_FACTOR = 0.3;

	private DamageToNetworkChangeEvents() {
	}

	/** One parsed damage CSV row. */
	public record DamageRow(String linkId, String damageState, double freespeedFactor,
			double capacityFactor, boolean closed) {
	}

	/** Parse a damage CSV file (header {@code link_id,damage_state,freespeed_factor,capacity_factor,closed}). */
	public static List<DamageRow> parseDamageFile(Path damageFile) throws IOException {
		List<String> lines = Files.readAllLines(damageFile);
		if (lines.isEmpty()) {
			throw new IllegalArgumentException("Empty damage file: " + damageFile);
		}
		List<DamageRow> rows = new ArrayList<>();
		for (int i = 1; i < lines.size(); i++) {
			String line = lines.get(i).trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] parts = line.split(",", -1);
			if (parts.length < 5) {
				throw new IllegalArgumentException("Damage line " + (i + 1) + " needs 5 columns: " + line);
			}
			String linkId = parts[0].trim();
			String state = parts[1].trim();
			boolean closed = Boolean.parseBoolean(parts[4].trim());
			double freespeedFactor = parts[2].trim().isEmpty() ? Double.NaN : Double.parseDouble(parts[2].trim());
			double capacityFactor = parts[3].trim().isEmpty() ? Double.NaN : Double.parseDouble(parts[3].trim());
			if (linkId.isEmpty()) {
				throw new IllegalArgumentException("Damage line " + (i + 1) + " has empty link_id");
			}
			if (!closed && !(freespeedFactor > 0.0 && freespeedFactor <= 1.0
					&& capacityFactor > 0.0 && capacityFactor <= 1.0)) {
				throw new IllegalArgumentException(
						"Damage line " + (i + 1) + " needs factors in (0,1] when not closed: " + line);
			}
			rows.add(new DamageRow(linkId, state, freespeedFactor, capacityFactor, closed));
		}
		if (rows.isEmpty()) {
			throw new IllegalArgumentException("No damage rows in " + damageFile);
		}
		return rows;
	}

	/**
	 * Build one {@link NetworkChangeEvent} per row, resolving link IDs against
	 * the network (unknown IDs throw).
	 */
	public static List<NetworkChangeEvent> toEvents(Network network, List<DamageRow> rows, double tQuake) {
		List<NetworkChangeEvent> events = new ArrayList<>();
		for (DamageRow row : rows) {
			Link link = network.getLinks().get(Id.createLinkId(row.linkId()));
			if (link == null) {
				throw new IllegalArgumentException("Damage row references unknown link: " + row.linkId());
			}
			NetworkChangeEvent event = new NetworkChangeEvent(tQuake);
			event.addLink(link);
			if (row.closed()) {
				event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(
						NetworkChangeEvent.ChangeType.FACTOR, CLOSED_FREESPEED_FACTOR));
				event.setFlowCapacityChange(new NetworkChangeEvent.ChangeValue(
						NetworkChangeEvent.ChangeType.FACTOR, CLOSED_CAPACITY_FACTOR));
			} else {
				event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(
						NetworkChangeEvent.ChangeType.FACTOR, row.freespeedFactor()));
				event.setFlowCapacityChange(new NetworkChangeEvent.ChangeValue(
						NetworkChangeEvent.ChangeType.FACTOR, row.capacityFactor()));
			}
			events.add(event);
		}
		log.info("Built {} network change events at t={}s", events.size(), tQuake);
		return events;
	}

	/** Attach events to a loaded network (in-memory path used by {@link RunAvEarthquake}). */
	public static void attachToNetwork(Network network, Collection<NetworkChangeEvent> events) {
		for (NetworkChangeEvent event : events) {
			NetworkUtils.addNetworkChangeEvent(network, event);
		}
	}

	/** Write events to a change-events XML file (config-file path). */
	public static void writeXml(Collection<NetworkChangeEvent> events, String outputFile) {
		new NetworkChangeEventsWriter().write(outputFile, events);
		log.info("Wrote {} network change events to {}", events.size(), outputFile);
	}

	public static void main(String[] args) throws IOException {
		Path damageFile = null;
		Path networkFile = Path.of("scenarios/siouxfalls/network.xml");
		String output = "scenarios/siouxfalls/change-events.xml";
		double tQuake = DEFAULT_T_QUAKE;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--damage" -> {
					if (i + 1 < args.length) damageFile = Path.of(args[++i]);
				}
				case "--network" -> {
					if (i + 1 < args.length) networkFile = Path.of(args[++i]);
				}
				case "--output" -> {
					if (i + 1 < args.length) output = args[++i];
				}
				case "--tQuake" -> {
					if (i + 1 < args.length) tQuake = Double.parseDouble(args[++i]);
				}
				default -> log.warn("Ignoring unknown arg: {}", args[i]);
			}
		}
		if (damageFile == null) {
			throw new IllegalArgumentException("Usage: --damage <csv> [--network <xml>] [--output <xml>] [--tQuake <s>]");
		}

		Network network = NetworkUtils.readNetwork(networkFile.toString());
		List<NetworkChangeEvent> events = toEvents(network, parseDamageFile(damageFile), tQuake);
		Path out = Path.of(output);
		if (out.getParent() != null) {
			Files.createDirectories(out.getParent());
		}
		writeXml(events, output);
	}
}
