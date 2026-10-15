package org.matsim.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.testcases.MatsimTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 smoke test: the sweep driver completes a mini grid on equil
 * (2 shares x 1 seed x {quake, noquake}, 1 iteration) with per-cell outputs.
 */
public class SweepDriverTest {

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	public final void testMiniGrid() throws Exception {
		Path workdir = Path.of(utils.getOutputDirectory());
		Path damage = workdir.resolve("damage.csv");
		Files.writeString(damage, "link_id,damage_state,freespeed_factor,capacity_factor,closed\n"
				+ "10,severe,,,true\n");
		Path hospitals = workdir.resolve("hospitals.csv");
		Files.writeString(hospitals, "hospital_id,node,link,x,y\nhospital0,1,1,0,0\n");
		String outdir = workdir.resolve("sweep").toString();

		List<String> command = List.of("bash", "scripts/run_sweep.sh",
				"--config", "scenarios/equil/config-2026.xml",
				"--shares", "0 100",
				"--seeds", "7",
				"--variants", "quake noquake",
				"--iterations", "1",
				"--jobs", "2",
				"--damage", damage.toString(),
				"--tQuake", "21660",
				"--hospitals", hospitals.toString(),
				"--outdir", outdir);
		Process process = new ProcessBuilder(command)
				.directory(new java.io.File("."))
				.redirectErrorStream(true)
				.redirectOutput(workdir.resolve("sweep-driver.log").toFile())
				.start();
		boolean finished = process.waitFor(20, java.util.concurrent.TimeUnit.MINUTES);
		assertTrue(finished, "sweep driver timed out");
		assertEquals(0, process.exitValue(), "sweep driver failed; see sweep-driver.log");

		for (String share : new String[]{"0", "100"}) {
			for (String variant : new String[]{"quake", "noquake"}) {
				Path cell = Path.of(outdir, "av" + share + "_seed7_" + variant);
				assertTrue(Files.exists(cell.resolve("output_events.xml.zst")), "missing events in " + cell);
				assertTrue(Files.exists(cell.resolve("output_trips.csv.zst")), "missing trips in " + cell);
			}
		}
		Path status = Path.of(outdir, "cell-status.txt");
		assertEquals(4, Files.readAllLines(status).stream().filter(l -> l.startsWith("OK")).count());

		// Quake cells carry per-iteration redirect logs (last iteration: 100 rows).
		for (String share : new String[]{"0", "100"}) {
			Path csv = Path.of(outdir, "av" + share + "_seed7_quake", "redirect-log.csv");
			assertEquals(101, Files.readAllLines(csv).size(), "redirect log rows in " + csv);
		}
	}
}
