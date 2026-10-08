package org.matsim.project;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast unit tests for the nested seeded AV assignment (no simulation).
 */
public class AvAssignmentTest {

	private static Set<Id<Person>> avSet(double share, long seed, int n) {
		Set<Id<Person>> set = new HashSet<>();
		for (int i = 0; i < n; i++) {
			Id<Person> id = Id.createPersonId("person" + i);
			if (AvAssignment.isAv(id, share, seed)) set.add(id);
		}
		return set;
	}

	@Test
	public final void testBoundaries() {
		Id<Person> id = Id.createPersonId("somebody");
		for (long seed : new long[]{0L, 42L, -7L}) {
			assertEquals(false, AvAssignment.isAv(id, 0.0, seed));
			assertEquals(true, AvAssignment.isAv(id, 1.0, seed));
		}
	}

	@Test
	public final void testDeterministic() {
		for (int i = 0; i < 200; i++) {
			Id<Person> id = Id.createPersonId("p" + i);
			assertEquals(AvAssignment.isAv(id, 0.3, 4711L), AvAssignment.isAv(id, 0.3, 4711L));
			assertEquals(AvAssignment.drawUniform(id, 99L), AvAssignment.drawUniform(id, 99L));
		}
	}

	@Test
	public final void testNestedAcrossShares() {
		long seed = 4711L;
		Set<Id<Person>> low = avSet(0.10, seed, 2000);
		Set<Id<Person>> mid = avSet(0.25, seed, 2000);
		Set<Id<Person>> high = avSet(0.75, seed, 2000);

		assertTrue(mid.containsAll(low), "AV set at 10% must be contained in AV set at 25%");
		assertTrue(high.containsAll(mid), "AV set at 25% must be contained in AV set at 75%");
	}

	@Test
	public final void testRoughlyRightFraction() {
		Set<Id<Person>> half = avSet(0.5, 12345L, 2000);
		assertTrue(half.size() > 800 && half.size() < 1200,
				"Expected ~1000 AV at 50% of 2000, was " + half.size());
	}
}
