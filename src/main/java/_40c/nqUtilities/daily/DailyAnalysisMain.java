package _40c.nqUtilities.daily;

import _40c.nqUtilities.daily.analysis.PriceMoveAnalyzer;
import _40c.nqUtilities.daily.analysis.PriceMoveProfile;
import _40c.nqUtilities.daily.data.TickRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the per-day NIFTY-1 tick-behaviour analysis.
 *
 * <pre>
 *   java _40c.nqUtilities.daily.DailyAnalysisMain [dbPath]
 * </pre>
 *
 * <p>Input  : the live nqTicker tick database ({@code dbPath}, defaulting below).<br>
 * Output : one intelligence profile per trading day (printed as a one-liner), plus a single
 * merged global profile across the whole capture window — the model a future shredder will
 * sample from. This is the "test-case" stage: characterise real behaviour; no synthesis yet.
 */
public final class DailyAnalysisMain {

    private static final Logger log = LoggerFactory.getLogger(DailyAnalysisMain.class);

    /** Live database written by nqTicker's SqliteTickWriter. */
    private static final String DEFAULT_DB_PATH = "C:/novaquant/data/sqlite/nifty_ticks.db";

    public static void main(String[] args) {
        String dbPath = args.length > 0 ? args[0] : DEFAULT_DB_PATH;

        var repository = new TickRepository(dbPath);
        var analyzer   = new PriceMoveAnalyzer();
        var runner     = new DailyAnalysisRunner<>(repository, analyzer);

        long t0 = System.nanoTime();
        var outcomes = runner.run();
        long wallMs = (System.nanoTime() - t0) / 1_000_000;

        log.info("=== Per-day profiles (regular session 09:15-15:30 IST) ===");
        var global = new PriceMoveProfile("ALL DAYS", 0);
        int ok = 0;
        for (var o : outcomes) {
            if (o.ok()) {
                ok++;
                log.info("  {}", o.result().summary());
                global.merge(o.result());
            } else {
                log.warn("  {}   FAILED: {}", o.date(), o.failure());
            }
        }

        log.info("=== GLOBAL MODEL (merged across all days) ===\n{}", global.report());

        log.info("=== Done: {}/{} days ok, wall {}ms ===", ok, outcomes.size(), wallMs);
    }

    private DailyAnalysisMain() {}
}
