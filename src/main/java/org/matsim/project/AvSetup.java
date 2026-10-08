package org.matsim.project;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import org.matsim.vehicles.VehiclesFactory;

/**
 * Phase 1 (Option A, private AVs): AVs are privately owned cars whose network
 * effect is a changed {@link VehicleType#setFlowEfficiencyFactor(double)
 * flow-efficiency factor}.
 *
 * <p>Creates two vehicle types ({@code car}, {@code av}) and tags every person
 * with the {@link AvAssignment#IS_AV_ATTRIBUTE} attribute plus a
 * {@code car}-mode to vehicle-type mapping. The actual vehicle instances are
 * created by MATSim's {@code PrepareForSim} from that mapping (this is the
 * mechanism its {@code modeVehicleTypesFromVehiclesData} source consumes).
 * The QSim must use that source (set in {@link RunAvEarthquake#prepareConfig})
 * so these per-person vehicle types are used.
 */
public final class AvSetup {

	private static final Logger log = LogManager.getLogger(AvSetup.class);

	public static final String CAR_MODE = "car";

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
			// AVs drive on the car network; MATSim requires an explicit network mode
			// for every non-"car" vehicle type.
			avType.setNetworkMode(CAR_MODE);
			avType.setPcuEquivalents(1.0);
			avType.setFlowEfficiencyFactor(avFlowEfficiencyFactor);
			vehicles.addVehicleType(avType);
		} else {
			avType.setFlowEfficiencyFactor(avFlowEfficiencyFactor);
		}

		int avCount = 0;
		for (Person person : scenario.getPopulation().getPersons().values()) {
			boolean av = AvAssignment.isAv(person.getId(), avShare, seed);
			person.getAttributes().putAttribute(AvAssignment.IS_AV_ATTRIBUTE, av);
			VehicleUtils.insertVehicleTypesIntoPersonAttributes(person,
					Map.of(CAR_MODE, av ? AV_TYPE_ID : CAR_TYPE_ID));

			if (av) avCount++;
		}

		log.info("AV setup: {}/{} persons AV (share={}, seed={}, avFlowEfficiencyFactor={})",
				avCount, scenario.getPopulation().getPersons().size(), avShare, seed, avFlowEfficiencyFactor);
		return avCount;
	}
}
