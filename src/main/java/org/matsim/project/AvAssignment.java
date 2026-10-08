package org.matsim.project;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

/**
 * Seeded designation of which agents are autonomous.
 *
 * <p>Each person gets a deterministic uniform draw {@code u_i} derived from
 * {@code hash(personId, seed)}; the person is AV iff {@code u_i < avShare}.
 * Because the draw does not depend on {@code avShare}, AV sets are
 * <strong>nested</strong> across penetration rates with the same seed
 * (everyone AV at 10% is also AV at 25%), which reduces variance when
 * comparing runs in the Phase 5 sweep.
 *
 * <p>Determinism across JVMs: the draw uses {@link String#hashCode()} and an
 * inline SplitMix64 finalizer, both fixed integer arithmetic.
 */
public final class AvAssignment {

	/** Person attribute holding the AV flag ({@link Boolean}). */
	public static final String IS_AV_ATTRIBUTE = "isAV";

	private AvAssignment() {
	}

	/** Deterministic uniform draw in [0,1) for a person under a seed (SplitMix64). */
	public static double drawUniform(Id<Person> personId, long seed) {
		long z = 0x9E3779B97F4A7C15L ^ seed ^ (personId.toString().hashCode() * 0xBF58476D1CE4E5B9L);
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		z = z ^ (z >>> 31);
		return (z >>> 11) * 0x1p-53;
	}

	/** True iff the person is AV at the given penetration rate. */
	public static boolean isAv(Id<Person> personId, double avShare, long seed) {
		if (avShare <= 0.0) return false;
		if (avShare >= 1.0) return true;
		return drawUniform(personId, seed) < avShare;
	}
}
