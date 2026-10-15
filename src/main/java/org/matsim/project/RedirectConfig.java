package org.matsim.project;

import java.util.List;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

/**
 * Phase 4 settings for the {@code t_quake} redirect (see D5/D6).
 *
 * <p>Outcome splits are cumulative thresholds on one seeded uniform draw per
 * agent ({@link QuakeRedirector#outcomeFor}), so re-running with different
 * shares only changes flags. Anything left over stays on its original plan.
 */
public record RedirectConfig(double tQuake, long redirectSeed,
		double homeShare, double hospitalShare, double stayShare, double otherShare,
		List<Id<Link>> hospitalLinks, String outputDir) {

	/** Quake redirect off unless requested via {@code --redirect}. */
	public static final double DEFAULT_HOME_SHARE = 0.60;
	public static final double DEFAULT_HOSPITAL_SHARE = 0.17;
	public static final double DEFAULT_STAY_SHARE = 0.15;
	public static final double DEFAULT_OTHER_SHARE = 0.08;

	public RedirectConfig {
		if (homeShare < 0 || hospitalShare < 0 || stayShare < 0 || otherShare < 0) {
			throw new IllegalArgumentException("Redirect shares must be >= 0");
		}
		if (homeShare + hospitalShare + stayShare + otherShare > 1.0 + 1e-9) {
			throw new IllegalArgumentException("Redirect shares must sum to <= 1");
		}
		if (hospitalLinks == null || hospitalLinks.isEmpty()) {
			throw new IllegalArgumentException("At least one hospital link is required");
		}
		hospitalLinks = List.copyOf(hospitalLinks);
	}
}
