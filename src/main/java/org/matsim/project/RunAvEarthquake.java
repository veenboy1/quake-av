package org.matsim.project;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Phase 0 entry point for the AV + earthquake work.
 *
 * <p>Loads a config, builds a {@link Scenario}, creates a {@link Controler} and runs it.
 * All later phases hook in here (AV assignment, network change events, within-day redirect).
 * Keeps a minimal CLI so Phase 5 sweeps can parameterize output dir and iterations.
 */
public class RunAvEarthquake {

	private static final Logger log = LogManager.getLogger(RunAvEarthquake.class);

	static final String DEFAULT_CONFIG = "scenarios/equil/config-2026.xml";

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
				case "--help", "-h" -> {
					System.out.println("Usage: RunAvEarthquake [--config <file>] [--output <dir>] [--lastIteration <n>]");
					return;
				}
				default -> log.warn("Ignoring unknown arg: {}", args[i]);
			}
		}

		run(configFile, outputDir, lastIteration);
	}

	/**
	 * @param configFile    path to a MATSim config file
	 * @param outputDir     overrides {@code controller.outputDirectory} when non-null
	 * @param lastIteration overrides {@code controller.lastIteration} when non-null
	 */
	public static void run(String configFile, String outputDir, Integer lastIteration) {
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

	/** Hook for later phases (e.g. AV config, time-variant network). No-op in Phase 0. */
	protected static void prepareConfig(Config config) {
	}

	/** Hook for later phases (e.g. AV assignment, facilities). No-op in Phase 0. */
	protected static void prepareScenario(Scenario scenario) {
	}

	/** Hook for later phases (e.g. DRT modules, quake listener). No-op in Phase 0. */
	protected static void prepareControler(Controler controler) {
	}
}
