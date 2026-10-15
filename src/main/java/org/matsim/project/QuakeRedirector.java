package org.matsim.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.mobsim.framework.Mobsim;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.NetworkAgent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.facilities.FacilitiesUtils;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

/**
 * Phase 4: one-shot redirect at {@code t_quake}.
 *
 * <p>At the first sim step with {@code time >= t_quake}, every agent draws one
 * seeded outcome ({@link #outcomeFor}): home (first {@code h} link), hospital
 * (nearest hospital link), stay (truncate plan in place), random (seeded
 * uniform link), or unchanged. In-activity agents leave now; en-route agents
 * get a fresh route from their current link (in-progress legs are re-routed in
 * place, never touched structurally). Everything after the new destination is
 * truncated — agents end their day there. All shares are CLI-tunable (D5/D6).
 */
public class QuakeRedirector implements MobsimBeforeSimStepListener {

	private static final Logger log = LogManager.getLogger(QuakeRedirector.class);

	/** Salt separating the random-destination draw from the outcome draw. */
	private static final long RANDOM_DEST_SALT = 0x9E3779B97F4A7C15L;

	private final Scenario scenario;
	private final Provider<TripRouter> tripRouterProvider;
	private final RedirectConfig config;
	private final RedirectLogger logger;

	private boolean fired = false;
	private double lastSeenTime = -1.0;

	@Inject
	public QuakeRedirector(Scenario scenario, Provider<TripRouter> tripRouterProvider,
			RedirectConfig config, RedirectLogger logger) {
		this.scenario = scenario;
		this.tripRouterProvider = tripRouterProvider;
		this.config = config;
		this.logger = logger;
	}

	/** Seeded outcome for one agent; anything unallocated stays on plan. */
	public static String outcomeFor(Id<Person> personId, long seed,
			double homeShare, double hospitalShare, double stayShare, double otherShare) {
		double r = AvAssignment.drawUniform(personId, seed);
		if (r < homeShare) return "home";
		if (r < homeShare + hospitalShare) return "hospital";
		if (r < homeShare + hospitalShare + stayShare) return "stay";
		if (r < homeShare + hospitalShare + stayShare + otherShare) return "random";
		return "none";
	}

	@Override
	public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent event) {
		double time = event.getSimulationTime();
		if (time < lastSeenTime) {
			// New mobsim iteration restarts at t=0 (listener may be reused).
			fired = false;
		}
		lastSeenTime = time;
		if (fired || time < config.tQuake()) {
			return;
		}
		fired = true;
		if (!(event.getQueueSimulation() instanceof QSim qsim)) {
			log.warn("QuakeRedirector needs a QSim; skipping redirect");
			return;
		}
		Network network = scenario.getNetwork();
		List<Id<Link>> sortedLinks = new ArrayList<>(network.getLinks().keySet());
		sortedLinks.sort(Comparator.comparing(Id::toString));

		TripRouter router = tripRouterProvider.get();
		double now = event.getSimulationTime();

		int[] counts = new int[5];
		for (MobsimAgent agent : qsim.getAgents().values()) {
			if (agent.getState() != MobsimAgent.State.ACTIVITY
					&& agent.getState() != MobsimAgent.State.LEG) {
				continue;
			}
			String outcome = outcomeFor(agent.getId(), config.redirectSeed(),
					config.homeShare(), config.hospitalShare(), config.stayShare(), config.otherShare());
			if (outcome.equals("none")) {
				continue;
			}
			try {
				if (redirect(agent, outcome, now, qsim, router, network, sortedLinks)) {
					counts[java.util.List.of("home", "hospital", "stay", "random").indexOf(outcome)]++;
				}
			} catch (Exception e) {
				log.warn("Skipping redirect of agent {} ({}): {}",
						agent.getId(), outcome, e.getMessage());
			}
		}
		log.info("Quake redirect at t={}s: home={} hospital={} stay={} random={}",
				now, counts[0], counts[1], counts[2], counts[3]);
	}

	private boolean redirect(MobsimAgent agent, String outcome, double now, Mobsim mobsim,
			TripRouter router, Network network, List<Id<Link>> sortedLinks) {
		Plan plan = WithinDayAgentUtils.getModifiablePlan(agent);
		Person person = scenario.getPopulation().getPersons().get(agent.getId());
		Integer idx = WithinDayAgentUtils.getCurrentPlanElementIndex(agent);
		if (plan == null || person == null || idx == null) {
			return false;
		}
		boolean inActivity = agent.getState() == MobsimAgent.State.ACTIVITY;
		List<PlanElement> elements = plan.getPlanElements();

		Id<Link> currentLink = currentLinkOf(agent, elements.get(idx), network);
		if (currentLink == null) {
			return false;
		}
		String actType;
		Id<Link> destLink;
		switch (outcome) {
			case "home" -> {
				actType = "h";
				destLink = homeLinkOf(plan, currentLink);
			}
			case "hospital" -> {
				actType = "hospital";
				destLink = nearestHospital(currentLink, network);
			}
			case "stay" -> {
				actType = "stay";
				destLink = currentLink;
			}
			case "random" -> {
				actType = "h";
				double r = AvAssignment.drawUniform(agent.getId(), config.redirectSeed() ^ RANDOM_DEST_SALT);
				destLink = sortedLinks.get((int) (r * sortedLinks.size()));
			}
			default -> {
				return false;
			}
		}

		int j = nextActivityIndex(elements, idx);
		String origDest = j >= 0 && elements.get(j) instanceof Activity act ? act.getLinkId().toString() : "-";

		Object avAttr = person.getAttributes().getAttribute(AvAssignment.IS_AV_ATTRIBUTE);
		boolean isAv = Boolean.TRUE.equals(avAttr);
		// AVs replan on observed (post-quake) times, conventional drivers on free-flow.
		String routingMode = isAv ? AvSetup.AV_MODE : AvSetup.CAR_MODE;

		Activity newAct = scenario.getPopulation().getFactory().createActivityFromLinkId(actType, destLink);
		newAct.setCoord(network.getLinks().get(destLink).getCoord());

		if (j < 0) {
			// In the final activity: end it now and append the new trip.
			if (!inActivity) {
				return false;
			}
			Activity current = (Activity) elements.get(idx);
			current.setEndTime(now);
			elements.add(scenario.getPopulation().getFactory().createLeg(modeOf(elements, idx)));
			elements.add(newAct);
			WithinDayAgentUtils.rescheduleActivityEnd(agent, mobsim);
			WithinDayAgentUtils.resetCaches(agent);
		} else if (inActivity) {
			List<? extends PlanElement> newLegs = router.calcRoute(routingMode,
					FacilitiesUtils.wrapLink(network.getLinks().get(currentLink)),
					FacilitiesUtils.wrapLink(network.getLinks().get(destLink)),
					now, person, person.getAttributes());
			elements.subList(idx + 1, j).clear();
			elements.addAll(idx + 1, newLegs);
			elements.set(idx + 1 + newLegs.size(), newAct);
			elements.subList(idx + 2 + newLegs.size(), elements.size()).clear();
			Activity current = (Activity) elements.get(idx);
			current.setEndTime(now);
			WithinDayAgentUtils.rescheduleActivityEnd(agent, mobsim);
			WithinDayAgentUtils.resetCaches(agent);
		} else {
			// En route. Trips have walk access/egress legs around the car leg, so the
			// current leg is often a teleport, not the car leg:
			// (a) current leg is a NetworkRoute car leg: splice a fresh path from the
			// current link into it (blessed EditTrips pattern; wholesale replacement
			// misaligns the driver's link index and silently loses agents).
			// (b) current leg is a teleport access leg: leave it alone (teleports
			// can't be rerouted — EditTrips documents the same limitation) and route
			// from its end link instead.
			if (!(elements.get(idx) instanceof Leg currentLeg)) {
				log.debug("Skipping redirect of agent {}: current element is {}", agent.getId(),
						elements.get(idx).getClass().getSimpleName());
				return false;
			}
			if (currentLeg.getRoute() instanceof org.matsim.core.population.routes.NetworkRoute oldRoute) {
				List<? extends PlanElement> newTripElements = router.calcRoute(routingMode,
						FacilitiesUtils.wrapLink(network.getLinks().get(currentLink)),
						FacilitiesUtils.wrapLink(network.getLinks().get(destLink)),
						now, person, person.getAttributes());
				int carIdx = firstNetworkRouteLeg(newTripElements);
				if (carIdx < 0) {
					log.debug("Skipping redirect of agent {}: no car leg in new trip", agent.getId());
					return false;
				}
				org.matsim.core.population.routes.NetworkRoute newRoute =
						(org.matsim.core.population.routes.NetworkRoute) ((Leg) newTripElements.get(carIdx)).getRoute();
				Integer routeIndex = WithinDayAgentUtils.getCurrentRouteLinkIdIndex(agent);
				if (routeIndex == null) {
					log.debug("Skipping redirect of agent {}: route index null", agent.getId());
					return false;
				}
				spliceNewPathIntoOldRoute(routeIndex, newRoute.getEndLinkId(), oldRoute,
						newRoute.getLinkIds(), currentLink, network);
				elements.subList(idx + 1, j).clear();
				List<? extends PlanElement> tail = newTripElements.subList(carIdx + 1, newTripElements.size());
				elements.addAll(idx + 1, tail);
				int newJ = idx + 1 + tail.size();
				elements.set(newJ, newAct);
				elements.subList(newJ + 1, elements.size()).clear();
				WithinDayAgentUtils.resetCaches(agent);
			} else if (currentLeg.getRoute() != null) {
				Id<Link> teleportEnd = currentLeg.getRoute().getEndLinkId();
				List<? extends PlanElement> newTripElements = router.calcRoute(routingMode,
						FacilitiesUtils.wrapLink(network.getLinks().get(teleportEnd)),
						FacilitiesUtils.wrapLink(network.getLinks().get(destLink)),
						now, person, person.getAttributes());
				elements.subList(idx + 1, j).clear();
				elements.addAll(idx + 1, newTripElements);
				int newJ = idx + 1 + newTripElements.size();
				elements.set(newJ, newAct);
				elements.subList(newJ + 1, elements.size()).clear();
				WithinDayAgentUtils.resetCaches(agent);
			} else {
				log.debug("Skipping redirect of agent {}: unrouted current leg", agent.getId());
				return false;
			}
		}

		Object isAvAttr = person.getAttributes().getAttribute(AvAssignment.IS_AV_ATTRIBUTE);
		logger.log(agent.getId().toString(), now, outcome,
				origDest, destLink.toString(), modeOf(elements, idx), Boolean.TRUE.equals(isAvAttr));
		return true;
	}

	private static String modeOf(List<PlanElement> elements, int idx) {
		for (int k = idx; k < elements.size(); k++) {
			if (elements.get(k) instanceof Leg leg) {
				return leg.getMode();
			}
		}
		for (int k = idx; k >= 0; k--) {
			if (elements.get(k) instanceof Leg leg) {
				return leg.getMode();
			}
		}
		return "car";
	}

	private static Id<Link> currentLinkOf(MobsimAgent agent, PlanElement current, Network network) {
		if (agent instanceof NetworkAgent networkAgent && networkAgent.getCurrentLinkId() != null) {
			return networkAgent.getCurrentLinkId();
		}
		if (current instanceof Activity act && act.getLinkId() != null) {
			return act.getLinkId();
		}
		return null;
	}

	private static Id<Link> homeLinkOf(Plan plan, Id<Link> fallback) {
		for (PlanElement element : plan.getPlanElements()) {
			if (element instanceof Activity act && act.getType().equals("h") && act.getLinkId() != null) {
				return act.getLinkId();
			}
		}
		return fallback;
	}

	private Id<Link> nearestHospital(Id<Link> from, Network network) {
		Coord origin = network.getLinks().get(from).getCoord();
		Id<Link> best = null;
		double bestDist = Double.POSITIVE_INFINITY;
		for (Id<Link> hospital : config.hospitalLinks()) {
			double d = NetworkUtils.getEuclideanDistance(origin, network.getLinks().get(hospital).getCoord());
			if (d < bestDist) {
				bestDist = d;
				best = hospital;
			}
		}
		return best;
	}

	/** Index of the first car (NetworkRoute) leg in routed trip elements, or -1. */
	private static int firstNetworkRouteLeg(List<? extends PlanElement> tripElements) {
		for (int k = 0; k < tripElements.size(); k++) {
			if (tripElements.get(k) instanceof Leg leg
					&& leg.getRoute() instanceof org.matsim.core.population.routes.NetworkRoute) {
				return k;
			}
		}
		return -1;
	}

	private static int nextActivityIndex(List<PlanElement> elements, int idx) {		for (int k = idx + 1; k < elements.size(); k++) {
			if (elements.get(k) instanceof Activity) {
				return k;
			}
		}
		return -1;
	}

	/**
	 * Splice a fresh path into the in-progress route, keeping the traveled prefix.
	 * Replicates the package-private {@code EditRoutes.spliceNewPathIntoOldRoute}
	 * (same index arithmetic and join handling); wholesale route replacement
	 * misaligns the driver's link index and silently loses agents.
	 */
	private static void spliceNewPathIntoOldRoute(int currentLinkIndex, Id<Link> toLinkId,
			org.matsim.core.population.routes.NetworkRoute oldRoute, List<Id<Link>> newLinkIds,
			Id<Link> currentLinkId, Network network) {
		List<Id<Link>> oldLinkIds = oldRoute.getLinkIds();
		List<Id<Link>> resultingLinkIds = new ArrayList<>();
		if (currentLinkIndex > 0) {
			resultingLinkIds.addAll(oldLinkIds.subList(0, Math.min(currentLinkIndex - 1, oldLinkIds.size())));
		}
		if (!oldRoute.getStartLinkId().equals(currentLinkId)) {
			resultingLinkIds.add(currentLinkId);
		}
		if (!resultingLinkIds.isEmpty() && !newLinkIds.isEmpty()
				&& resultingLinkIds.get(resultingLinkIds.size() - 1).equals(newLinkIds.get(newLinkIds.size() - 1))) {
			resultingLinkIds.remove(resultingLinkIds.size() - 1);
		}
		resultingLinkIds.addAll(newLinkIds);
		oldRoute.setLinkIds(oldRoute.getStartLinkId(), resultingLinkIds, toLinkId);
	}
}
