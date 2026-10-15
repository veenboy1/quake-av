package org.matsim.project;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.List;

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
	static String damageFile = null;
	static double tQuake = DamageToNetworkChangeEvents.DEFAULT_T_QUAKE;
	static boolean redirect = false;
	static long redirectSeed = 9182L;
	static double homeShare = RedirectConfig.DEFAULT_HOME_SHARE;
	static double hospitalShare = RedirectConfig.DEFAULT_HOSPITAL_SHARE;
	static double stayShare = RedirectConfig.DEFAULT_STAY_SHARE;
	static double otherShare = RedirectConfig.DEFAULT_OTHER_SHARE;
	static String hospitalsFile = "scenarios/siouxfalls/hospitals.csv";

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
				case "--damageFile" -> {
					if (i + 1 < args.length) damageFile = args[++i];
				}
				case "--tQuake" -> {
					if (i + 1 < args.length) tQuake = Double.parseDouble(args[++i]);
				}
				case "--redirect" -> redirect = true;
				case "--redirectSeed" -> {
					if (i + 1 < args.length) redirectSeed = Long.parseLong(args[++i]);
				}
				case "--homeShare" -> {
					if (i + 1 < args.length) homeShare = Double.parseDouble(args[++i]);
				}
				case "--hospitalShare" -> {
					if (i + 1 < args.length) hospitalShare = Double.parseDouble(args[++i]);
				}
				case "--stayShare" -> {
					if (i + 1 < args.length) stayShare = Double.parseDouble(args[++i]);
				}
				case "--otherShare" -> {
					if (i + 1 < args.length) otherShare = Double.parseDouble(args[++i]);
				}
				case "--hospitals" -> {
					if (i + 1 < args.length) hospitalsFile = args[++i];
				}
				case "--help", "-h" -> {
					System.out.println("Usage: RunAvEarthquake [--config <file>] [--output <dir>] [--lastIteration <n>]"
							+ " [--avShare <0-1>] [--avSeed <long>] [--avFlowEfficiency <double>]"
							+ " [--damageFile <csv>] [--tQuake <seconds>]"
							+ " [--redirect] [--redirectSeed <long>]"
							+ " [--homeShare <f>] [--hospitalShare <f>] [--stayShare <f>] [--otherShare <f>]"
							+ " [--hospitals <csv>]");
					return;
				}
				default -> log.warn("Ignoring unknown arg: {}", args[i]);
			}
		}

		runWithRedirect(configFile, outputDir, lastIteration, avShare, avSeed, avFlowEfficiencyFactor,
				damageFile, tQuake, redirect, redirectSeed, homeShare, hospitalShare, stayShare,
				otherShare, hospitalsFile);
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
		runWithDamage(configFile, outputDir, lastIteration, avShareValue, avSeedValue,
				avFlowEfficiencyValue, null, DamageToNetworkChangeEvents.DEFAULT_T_QUAKE);
	}

	/**
	 * Run with private AVs plus earthquake damage at {@code t_quake} (Phase 3).
	 * A null {@code damageFileValue} means no quake (baseline).
	 */
	public static void runWithDamage(String configFile, String outputDir, Integer lastIteration,
			double avShareValue, long avSeedValue, double avFlowEfficiencyValue,
			String damageFileValue, double tQuakeValue) {
		// Explicit no-redirect: lower-level APIs never inherit ambient redirect state
		// (static leakage between runs/tests); only main() forwards CLI-parsed flags.
		runWithRedirect(configFile, outputDir, lastIteration, avShareValue, avSeedValue,
				avFlowEfficiencyValue, damageFileValue, tQuakeValue, false,
				9182L, RedirectConfig.DEFAULT_HOME_SHARE, RedirectConfig.DEFAULT_HOSPITAL_SHARE,
				RedirectConfig.DEFAULT_STAY_SHARE, RedirectConfig.DEFAULT_OTHER_SHARE,
				"scenarios/siouxfalls/hospitals.csv");
	}

	/**
	 * Full run: AVs + damage + t_quake destination redirect (Phase 4).
	 * Redirect is off unless {@code redirectValue} is true.
	 */
	public static void runWithRedirect(String configFile, String outputDir, Integer lastIteration,
			double avShareValue, long avSeedValue, double avFlowEfficiencyValue,
			String damageFileValue, double tQuakeValue, boolean redirectValue, long redirectSeedValue,
			double homeShareValue, double hospitalShareValue, double stayShareValue,
			double otherShareValue, String hospitalsFileValue) {
		avShare = avShareValue;
		avSeed = avSeedValue;
		avFlowEfficiencyFactor = avFlowEfficiencyValue;
		damageFile = damageFileValue;
		tQuake = tQuakeValue;
		redirect = redirectValue;
		redirectSeed = redirectSeedValue;
		homeShare = homeShareValue;
		hospitalShare = hospitalShareValue;
		stayShare = stayShareValue;
		otherShare = otherShareValue;
		hospitalsFile = hospitalsFileValue;

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
			if (!config.qsim().getMainModes().contains(AvSetup.AV_MODE)) {
				var mainModes = new java.util.ArrayList<>(config.qsim().getMainModes());
				mainModes.add(AvSetup.AV_MODE);
				config.qsim().setMainModes(mainModes);
			}
			// The av mode needs MATSim's default (observed-time) network router.
			if (!config.routing().getNetworkModes().contains(AvSetup.AV_MODE)) {
				var networkModes = new java.util.ArrayList<>(config.routing().getNetworkModes());
				networkModes.add(AvSetup.AV_MODE);
				config.routing().setNetworkModes(networkModes);
			}
			// ... with its own observed travel-time calculator.
			if (!config.travelTimeCalculator().getAnalyzedModes().contains(AvSetup.AV_MODE)) {
				var analyzedModes = new java.util.HashSet<>(config.travelTimeCalculator().getAnalyzedModes());
				analyzedModes.add(AvSetup.AV_MODE);
				config.travelTimeCalculator().setAnalyzedModes(analyzedModes);
			}
			// Leg scoring must know the av mode (as with hospital/stay activities).
			if (config.scoring().getModes().get(AvSetup.AV_MODE) == null) {
				var carParams = config.scoring().getModes().get(AvSetup.CAR_MODE);
				var avParams = new org.matsim.core.config.groups.ScoringConfigGroup.ModeParams(AvSetup.AV_MODE);
				if (carParams != null) {
					avParams.setMarginalUtilityOfTraveling(carParams.getMarginalUtilityOfTraveling());
					avParams.setMarginalUtilityOfDistance(carParams.getMarginalUtilityOfDistance());
					avParams.setMonetaryDistanceRate(carParams.getMonetaryDistanceRate());
					avParams.setConstant(carParams.getConstant());
				} else {
					avParams.setMarginalUtilityOfTraveling(-6.0);
				}
				config.scoring().addModeParams(avParams);
			}
		}
		if (damageFile != null) {
			// Change events require a time-variant network (else "Link is not
			// timeVariant" at attach time); events themselves attach in-memory.
			// Stuck handling stays at MATSim defaults: effective-closure damage
			// never traps vehicles, so defaults preserve mass conservation.
			config.network().setTimeVariantNetwork(true);
		}
		if (redirect) {
			// The redirector introduces hospital/stay activities; scoring must know
			// them on every config (equil included), or scoring crashes post-mobsim.
			ensureActivityParams(config, "hospital", 3600.0);
			ensureActivityParams(config, "stay", 3600.0);
		}
	}

	private static void ensureActivityParams(Config config, String type, double typicalDuration) {
		if (config.scoring().getActivityParams(type) == null) {
			var params = new org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams(type);
			params.setTypicalDuration(typicalDuration);
			config.scoring().addActivityParams(params);
		}
	}

	static void prepareScenario(Scenario scenario) {
		if (avShare > 0.0) {
			AvSetup.setup(scenario, avShare, avSeed, avFlowEfficiencyFactor);
		}
		if (damageFile != null) {
			try {
				var rows = DamageToNetworkChangeEvents.parseDamageFile(java.nio.file.Path.of(damageFile));
				var events = DamageToNetworkChangeEvents.toEvents(scenario.getNetwork(), rows, tQuake);
				DamageToNetworkChangeEvents.attachToNetwork(scenario.getNetwork(), events);
				log.info("Attached {} damage events at t={}s from {}", events.size(), tQuake, damageFile);
			} catch (java.io.IOException e) {
				throw new RuntimeException("Could not read damage file " + damageFile, e);
			}
		}
	}

	/** Wires the Phase 4 redirect listener when {@code --redirect} is set. */
	protected static void prepareControler(Controler controler) {
		if (avShare > 0.0) {
			AvRouting.install(controler, damageFile != null);
		}
		if (!redirect) {
			return;
		}
		List<Id<Link>> hospitalLinks = parseHospitalLinks(
				controler.getScenario().getNetwork(), java.nio.file.Path.of(hospitalsFile));
		RedirectConfig redirectConfig = new RedirectConfig(tQuake, redirectSeed,
				homeShare, hospitalShare, stayShare, otherShare, hospitalLinks,
				controler.getConfig().controller().getOutputDirectory());
		controler.addOverridingModule(new org.matsim.core.controler.AbstractModule() {
			@Override
			public void install() {
				bind(RedirectConfig.class).toInstance(redirectConfig);
				bind(RedirectLogger.class).asEagerSingleton();
				addMobsimListenerBinding().to(QuakeRedirector.class);
				addMobsimListenerBinding().to(RedirectLogger.class);
				addControllerListenerBinding().to(RedirectLogger.class);
			}
		});
		log.info("Quake redirect on at t={}s (seed={}, home={} hospital={} stay={} other={}, {} hospitals)",
				tQuake, redirectSeed, homeShare, hospitalShare, stayShare, otherShare, hospitalLinks.size());
	}

	/** Read hospital link ids from a hospitals.csv (hospital_id,node,link,x,y); unknown links fail fast. */
	static List<Id<Link>> parseHospitalLinks(Network network, java.nio.file.Path hospitalsFile) {
		try {
			List<String> lines = java.nio.file.Files.readAllLines(hospitalsFile);
			List<Id<Link>> links = new java.util.ArrayList<>();
			for (int i = 1; i < lines.size(); i++) {
				String line = lines.get(i).trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				Id<Link> id = Id.createLinkId(line.split(",", -1)[2].trim());
				if (!network.getLinks().containsKey(id)) {
					throw new IllegalArgumentException("Hospital link unknown to network: " + id);
				}
				links.add(id);
			}
			if (links.isEmpty()) {
				throw new IllegalArgumentException("No hospitals in " + hospitalsFile);
			}
			return links;
		} catch (java.io.IOException e) {
			throw new RuntimeException("Could not read hospitals file " + hospitalsFile, e);
		}
	}
}
