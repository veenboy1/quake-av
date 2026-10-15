package org.matsim.project;

import java.util.HashMap;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

/**
 * Separate AV-mode routing (vision: AVs plan on the live damaged network,
 * conventional drivers route damage-blind).
 *
 * <p>Mechanism (all blessed MATSim machinery, no custom Dijkstra):
 * <ul>
 *   <li>{@code av} rides MATSim's default network router on observed
 *       time-dependent travel times (bound automatically once {@code av} joins
 *       the routing config's network + analyzed modes).</li>
 *   <li>{@code car} keeps the default router too, but in damage runs its
 *       travel-time input is rebound to a pre-quake snapshot: frozen base
 *       freespeeds captured before change events attach, so closures and
 *       congestion are invisible and conventional routes never adapt.</li>
 * </ul>
 *
 * <p>Without damage, both modes use observed times (identical to baseline
 * behavior); the asymmetry activates with the quake.
 */
public final class AvRouting {

	private AvRouting() {
	}

	/** Frozen pre-quake link travel times (base freespeeds, ignoring change events). */
	public static TravelTime snapshotTravelTime(Network network) {
		Map<Id<Link>, Double> freespeeds = new HashMap<>();
		for (Link link : network.getLinks().values()) {
			freespeeds.put(link.getId(), link.getFreespeed());
		}
		return new TravelTime() {
			@Override
			public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
				Double freespeed = freespeeds.get(link.getId());
				if (freespeed == null || freespeed <= 0.0) {
					freespeed = link.getFreespeed();
				}
				return link.getLength() / freespeed;
			}
		};
	}

	/** Rebind conventional routing to the pre-quake snapshot (damage runs only). */
	public static void installBlindCarTimes(Controler controler, TravelTime preQuakeTravelTime) {
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addTravelTimeBinding(AvSetup.CAR_MODE).toInstance(preQuakeTravelTime);
			}
		});
	}

	/** All-in-one wiring for {@link RunAvEarthquake#prepareControler}. */
	public static void install(Controler controler, boolean damagePresent) {
		if (!damagePresent) {
			return;
		}
		Scenario scenario = controler.getScenario();
		installBlindCarTimes(controler, snapshotTravelTime(scenario.getNetwork()));
	}
}
