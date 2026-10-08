package org.matsim.project;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Phase 0 entry point for the AV + earthquake work, extended in Phase 1 with
 * private-AV support (see {@link AvSetup}).
 *
 * <p>Loads a config, builds a {@link Scenario}, creates a {@link Controler} and runs it.
 * All later phases hook in here (network change events, within-day redirect).
 */
public class RunAvEarthquake {

	private static final Logger log = LogManager.getLogger(RunAvEarthquake.class);

	static final String DEFAULT_CONFIG = "scenarios/equil/config-2026.xml";

	/** Default AV flow-efficiency factor: capacity gain in the 1.5-2.0 literature range. */
	static final double DEFAULT_AV_FLOW_EFFICIENCY_FACTOR = 2.0;

	static double avShare = 0.0;
	static long avSeed = 0L;
	static double avFlowEfficiencyFactor = DEFAULT_AV_FLOW_EFFICIENCY_FACTOR;

	public static void main(String[] args) {
		String configFile = DEFAULT_CONFIG;
		String outputDir = null;
		Integer lastIteration = null;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--config" -> {
					if (i + 1 < args.length) configFile = args[++i];
				}
				case "--output" -> {
					if (i + 1 < args.length) outputDir = args[++i];
				}
				case "--lastIteration" -> {
					if (i + 1 < args.length) lastIteration = Integer.parseInt(args[++i]);
				}
				case "--avShare" -> {
					if (i + 1 < args.length) avShare = Double.parseDouble(args[++i]);
				}
				case "--avSeed" -> {
					if (i + 1 < args.length) avSeed = Long.parseLong(args[++i]);
				}
				case "--avFlowEfficiency" -> {
					if (i + 1 < args.length) avFlowEfficiencyFactor = Double.parseDouble(args[++i]);
				}
				case "--help", "-h" -> {
					System.out.println("Usage: RunAvEarthquake [--config <file>] [--output <dir>] [--lastIteration <n>]"
							+ " [--avShare <0-1>] [--avSeed <long>] [--avFlowEfficiency <double>]");
					return;
				}
				default -> log.warn("Ignoring unknown arg: {}", args[i]);
			}
		}

		runWithAv(configFile, outputDir, lastIteration, avShare, avSeed, avFlowEfficiencyFactor);
	}

	/**
	 * Baseline run without AVs (Phase 0 behavior).
	 */
	public static void run(String configFile, String outputDir, Integer lastIteration) {
		runWithAv(configFile, outputDir, lastIteration, 0.0, 0L, DEFAULT_AV_FLOW_EFFICIENCY_FACTOR);
	}

	/**
	 * Run with private AVs at the given penetration rate (nested seeded assignment).
	 */
	public static void runWithAv(String configFile, String outputDir, Integer lastIteration,
			double avShareValue, long avSeedValue, double avFlowEfficiencyValue) {
		avShare = avShareValue;
		avSeed = avSeedValue;
		avFlowEfficiencyFactor = avFlowEfficiencyValue;

		Config config = ConfigUtils.loadConfig(configFile);

		if (outputDir != null) {
			config.controller().setOutputDirectory(outputDir);
		}
		if (lastIteration != null) {
			config.controller().setLastIteration(lastIteration);
		}
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);

		prepareConfig(config);

		Scenario scenario = ScenarioUtils.loadScenario(config);

		prepareScenario(scenario);

		Controler controler = new Controler(scenario);

		prepareControler(controler);

		controler.run();
	}

	static void prepareConfig(Config config) {
		if (avShare > 0.0) {
			config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
		}
	}

	static void prepareScenario(Scenario scenario) {
		if (avShare > 0.0) {
			AvSetup.setup(scenario, avShare, avSeed, avFlowEfficiencyFactor);
		}
	}

	/** Hook for later phases (e.g. quake listener). No-op for now. */
	protected static void prepareControler(Controler controler) {
	}
}
