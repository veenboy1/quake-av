package org.matsim.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.mobsim.framework.events.MobsimBeforeCleanupEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeCleanupListener;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationStartsListener;

import jakarta.inject.Inject;

/**
 * Phase 4: per-redirect CSV log plus outcome counters.
 *
 * <p>Written to {@code <outputDir>/redirect-log.csv} at mobsim cleanup, so every
 * iteration (and every sweep cell) carries its own log.
 */
public class RedirectLogger implements MobsimBeforeCleanupListener, IterationStartsListener {

	private static final Logger log = LogManager.getLogger(RedirectLogger.class);

	public record Row(int iteration, String agentId, double time, String outcome, String origDestLink,
			String newDestLink, String mode, boolean isAv) {
	}

	private final String outputDir;
	private final List<Row> rows = new ArrayList<>();
	private int currentIteration = -1;

	@Inject
	public RedirectLogger(RedirectConfig config) {
		this.outputDir = config.outputDir();
	}

	public synchronized void log(String agentId, double time, String outcome, String origDestLink,
			String newDestLink, String mode, boolean isAv) {
		rows.add(new Row(currentIteration, agentId, time, outcome, origDestLink, newDestLink, mode, isAv));
	}

	/** New mobsim iteration starts at t=0; the redirector clears per iteration. */
	public synchronized void clear() {
		rows.clear();
	}

	public synchronized int size() {
		return rows.size();
	}

	@Override
	public synchronized void notifyIterationStarts(IterationStartsEvent event) {
		currentIteration = event.getIteration();
		rows.clear();
	}

	@Override
	public void notifyMobsimBeforeCleanup(MobsimBeforeCleanupEvent event) {
		StringBuilder sb = new StringBuilder("iteration,agent_id,time,outcome,orig_dest_link,new_dest_link,mode,is_av\n");
		synchronized (this) {
			for (Row row : rows) {
				sb.append(row.iteration()).append(",").append(row.agentId()).append(",").append(row.time()).append(",")
						.append(row.outcome()).append(",").append(row.origDestLink()).append(",")
						.append(row.newDestLink()).append(",").append(row.mode()).append(",")
						.append(row.isAv()).append("\n");
			}
		}
		try {
			Path out = Path.of(outputDir, "redirect-log.csv");
			Files.createDirectories(out.getParent());
			Files.writeString(out, sb.toString());
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write redirect log to " + outputDir, e);
		}
		log.info("Wrote {} redirect rows to {}/redirect-log.csv", rows.size(), outputDir);
	}
}
