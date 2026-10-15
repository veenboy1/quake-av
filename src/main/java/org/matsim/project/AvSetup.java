package org.matsim.project;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import org.matsim.vehicles.VehiclesFactory;

/**
 * Phase 1 (Option A, private AVs), extended with a separate AV mode:
 * AVs are privately owned cars with a changed
 * {@link VehicleType#setFlowEfficiencyFactor(double) flow-efficiency factor}
 * <strong>and</strong> their own network mode ({@code av}).
 *
 * <p>Every AV person travels on {@code av}-mode legs (rewritten from
 * {@code car} at setup) with an {@code av} vehicle type; conventional agents
 * stay on {@code car}. The split lets routing perceive the network differently
 * per mode (see {@link AvRouting}): AVs replan on observed (post-quake)
 * travel times while conventional drivers route damage-blind on free-flow
 * times. Link modes, QSim main modes and scoring params are wired at runtime
 * so no scenario fixture needs regenerating.
 */
public final class AvSetup {

	private static final Logger log = LogManager.getLogger(AvSetup.class);

	public static final String CAR_MODE = "car";

	/** Separate AV network mode (vision: AVs plan on the live network). */
	public static final String AV_MODE = "av";

	public static final Id<VehicleType> CAR_TYPE_ID = Id.create("car", VehicleType.class);
	public static final Id<VehicleType> AV_TYPE_ID = Id.create("av", VehicleType.class);

	private AvSetup() {
	}

	/**
	 * @param avFlowEfficiencyFactor capacity effect of AVs (&gt;1 = capacity gain,
	 *                               &lt;1 = conservative AVs). Literature typically uses 1.5-2.0.
	 * @return number of persons designated AV
	 */
	public static int setup(Scenario scenario, double avShare, long seed, double avFlowEfficiencyFactor) {
		if (avShare < 0.0 || avShare > 1.0) {
			throw new IllegalArgumentException("avShare must be in [0,1], was " + avShare);
		}
		if (avFlowEfficiencyFactor <= 0.0) {
			throw new IllegalArgumentException("avFlowEfficiencyFactor must be > 0, was " + avFlowEfficiencyFactor);
		}

		Vehicles vehicles = scenario.getVehicles();
		VehiclesFactory factory = vehicles.getFactory();

		VehicleType carType = vehicles.getVehicleTypes().get(CAR_TYPE_ID);
		if (carType == null) {
			carType = factory.createVehicleType(CAR_TYPE_ID);
			carType.setDescription("conventional car");
			carType.setNetworkMode(CAR_MODE);
			carType.setPcuEquivalents(1.0);
			carType.setFlowEfficiencyFactor(1.0);
			vehicles.addVehicleType(carType);
		}

		VehicleType avType = vehicles.getVehicleTypes().get(AV_TYPE_ID);
		if (avType == null) {
			avType = factory.createVehicleType(AV_TYPE_ID);
			avType.setDescription("private autonomous car");
			// AVs drive on their own network mode so routing can treat them
			// differently from conventional traffic (see AvRouting).
			avType.setNetworkMode(AV_MODE);
			avType.setPcuEquivalents(1.0);
			avType.setFlowEfficiencyFactor(avFlowEfficiencyFactor);
			vehicles.addVehicleType(avType);
		} else {
			avType.setFlowEfficiencyFactor(avFlowEfficiencyFactor);
		}

		// The av mode must be allowed on every link (runtime wiring; fixtures stay untouched).
		for (Link link : scenario.getNetwork().getLinks().values()) {
			if (!link.getAllowedModes().contains(AV_MODE)) {
				Set<String> modes = new HashSet<>(link.getAllowedModes());
				modes.add(AV_MODE);
				link.setAllowedModes(modes);
			}
		}

		int avCount = 0;
		for (Person person : scenario.getPopulation().getPersons().values()) {
			boolean av = AvAssignment.isAv(person.getId(), avShare, seed);
			person.getAttributes().putAttribute(AvAssignment.IS_AV_ATTRIBUTE, av);
			if (av) {
				for (Plan plan : person.getPlans()) {
					for (PlanElement element : plan.getPlanElements()) {
						if (element instanceof Leg leg && leg.getMode().equals(CAR_MODE)) {
							leg.setMode(AV_MODE);
						}
					}
				}
				VehicleUtils.insertVehicleTypesIntoPersonAttributes(person,
						Map.of(AV_MODE, AV_TYPE_ID));
			} else {
				VehicleUtils.insertVehicleTypesIntoPersonAttributes(person,
						Map.of(CAR_MODE, CAR_TYPE_ID));
			}

			if (av) avCount++;
		}

		log.info("AV setup: {}/{} persons AV (share={}, seed={}, avFlowEfficiencyFactor={})",
				avCount, scenario.getPopulation().getPersons().size(), avShare, seed, avFlowEfficiencyFactor);
		return avCount;
	}
}
