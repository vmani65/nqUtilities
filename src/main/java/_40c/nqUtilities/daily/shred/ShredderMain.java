package _40c.nqUtilities.daily.shred;

import _40c.nqUtilities.daily.analysis.Histogram;
import _40c.nqUtilities.daily.analysis.PriceMoveAnalyzer;
import _40c.nqUtilities.daily.analysis.PriceMoveProfile;
import _40c.nqUtilities.daily.data.TickRepository;
import _40c.nqUtilities.daily.model.DayTicks;
import _40c.nqUtilities.daily.model.TradingDay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Builds the live behavioural model, shreds ~5000 real 1-minute bars into ticks, then validates
 * that the shredded stream reproduces the live model across the <em>full</em> set of behaviours —
 * not just averages.
 *
 * <pre>
 *   java _40c.nqUtilities.daily.shred.ShredderMain [dbPath] [targetBars]
 * </pre>
 *
 * <p>Closed-loop test: the bars are aggregated from real NIFTY-1 session ticks, so a faithful
 * shredder must regenerate the same tick statistics those bars came from.
 */
public final class ShredderMain {

    private static final Logger log = LoggerFactory.getLogger(ShredderMain.class);

    private static final String DEFAULT_DB_PATH = "C:/novaquant/data/sqlite/nifty_ticks.db";
    private static final int    MAX_DAYS_TO_SCAN = 18;

    public static void main(String[] args) throws Exception {
        String dbPath     = args.length > 0 ? args[0] : DEFAULT_DB_PATH;
        int    targetBars = args.length > 1 ? Integer.parseInt(args[1]) : 5000;

        var repo     = new TickRepository(dbPath);
        var analyzer = new PriceMoveAnalyzer();

        // ---- select the most recent days and load only their regular session ----
        List<TradingDay> all = repo.discoverTradingDays();
        List<TradingDay> recent = all.subList(Math.max(0, all.size() - MAX_DAYS_TO_SCAN), all.size());
        log.info("Loading session ticks for the {} most recent days...", recent.size());

        List<DayTicks> loaded;
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            loaded = recent.stream()
                    .map(d -> exec.submit(() -> repo.loadWindow(d,
                            d.startMsInclusive() + PriceMoveAnalyzer.SESSION_START_SEC * 1000L,
                            d.startMsInclusive() + PriceMoveAnalyzer.SESSION_END_SEC   * 1000L)))
                    .toList()
                    .stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
                    .filter(dt -> dt.size() > 0)
                    .sorted(Comparator.comparing(DayTicks::date).reversed())
                    .toList();
        }

        // ---- pick newest-first until we have ~targetBars, build bars + the live model ----
        var selectedDays = new ArrayList<DayTicks>();
        var allBars      = new ArrayList<List<CandleBar>>();
        int barCount = 0;
        for (DayTicks dt : loaded) {
            List<CandleBar> bars = BarBuilder.fromDay(dt);
            if (bars.isEmpty()) continue;
            selectedDays.add(dt);
            allBars.add(bars);
            barCount += bars.size();
            if (barCount >= targetBars) break;
        }

        var liveProfile = new PriceMoveProfile("LIVE", 0);
        for (DayTicks dt : selectedDays) liveProfile.merge(analyzer.analyze(dt));

        log.info("Built live model from {} days ({} distinct changes); shredding {} bars.",
                selectedDays.size(), String.format("%,d", liveProfile.totalChanges()), barCount);

        // ---- shred every bar; analyze the shredded ticks back into a profile ----
        var model    = TickModel.from(liveProfile);
        var shredder = new TickShredder(model);

        var shreddedProfile = new PriceMoveProfile("SHREDDED", 0);
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<PriceMoveProfile>>();
            for (List<CandleBar> bars : allBars) {
                if (bars.isEmpty()) continue;
                long seed = bars.get(0).date().toEpochDay() * 1_000_003L + bars.size();
                futures.add(exec.submit(() -> shredAndAnalyze(bars, shredder, analyzer, new SplittableRandom(seed))));
            }
            for (var f : futures) shreddedProfile.merge(f.get());
        }

        // ---- report ----
        log.info("========== LIVE model ==========\n{}", liveProfile.report());
        log.info("========== SHREDDED model ==========\n{}", shreddedProfile.report());

        Validation.compare(liveProfile, shreddedProfile);
    }

    /** Shred one day's bars into a tick stream and run the analyzer over it. */
    private static PriceMoveProfile shredAndAnalyze(List<CandleBar> bars, TickShredder shredder,
                                                    PriceMoveAnalyzer analyzer, SplittableRandom rng) {
        var events = new ArrayList<TickShredder.ShredEvents>(bars.size());
        int total = 0;
        for (CandleBar bar : bars) {
            var ev = shredder.shred(bar, rng);
            events.add(ev);
            total += ev.size();
        }
        long[]   ts  = new long[total];
        double[] ltp = new double[total];
        long[]   vol = new long[total];
        long[]   oi  = new long[total];
        int idx = 0;
        long cum = 0;
        for (var ev : events) {
            for (int i = 0; i < ev.size(); i++) {
                ts[idx]  = ev.tsMs()[i];
                ltp[idx] = ev.ltp()[i];
                cum     += ev.traded()[i];
                vol[idx] = cum;
                oi[idx]  = 0;
                idx++;
            }
        }
        return analyzer.analyze(new DayTicks(bars.get(0).date(), ts, ltp, vol, oi, total));
    }

    private ShredderMain() {}

    // ---------------------------------------------------------------------------------------------
    /** Side-by-side comparison of the live vs shredded behaviours, with pass/fail flags. */
    private static final class Validation {

        private final StringBuilder sb = new StringBuilder();

        static void compare(PriceMoveProfile live, PriceMoveProfile shred) {
            var v = new Validation();
            v.build(live, shred);
            log.info("========== VALIDATION: live vs shredded ==========\n{}", v.sb);
        }

        private void build(PriceMoveProfile live, PriceMoveProfile shred) {
            sb.append("%-34s %12s %12s %10s   %s%n".formatted("metric", "live", "shredded", "delta", ""));

            Histogram lc = live.changesPerMinHist(), sc = shred.changesPerMinHist();
            row("changes/min  median",  lc.percentile(50), sc.percentile(50), 15);
            row("changes/min  p90",     lc.percentile(90), sc.percentile(90), 15);
            row("changes/min  p99",     lc.percentile(99), sc.percentile(99), 20);
            row("changes/min  avg",     live.changesPerMinAvgValue(), shred.changesPerMinAvgValue(), 15);

            Histogram lb = live.absRetBpsHist(), sb = shred.absRetBpsHist();
            row("|move| bps  median",   lb.percentile(50), sb.percentile(50), 15);
            row("|move| bps  p90",      lb.percentile(90), sb.percentile(90), 15);
            row("|move| bps  p99",      lb.percentile(99), sb.percentile(99), 20);
            row("|move| bps  mean",     lb.mean(),         sb.mean(),         15);
            row("|move| bps  sd",       lb.stdDev(),       sb.stdDev(),       20);

            Histogram lp = live.absMovePtsHist(), sp = shred.absMovePtsHist();
            row("|move| pts  median",   lp.percentile(50), sp.percentile(50), 15);
            row("|move| pts  p99",      lp.percentile(99), sp.percentile(99), 20);

            rowAbs("signed return bps mean", live.signedRetMeanBps(), shred.signedRetMeanBps(), 0.3);
            rowPP("up %",                    live.upPctValue(),  shred.upPctValue(),  5);
            rowPP("reversal %",              live.reversalRate(), shred.reversalRate(), 5);

            Histogram lg = live.gapMsHist(), sg = shred.gapMsHist();
            row("gap ms  median",       lg.percentile(50), sg.percentile(50), 25);
            row("gap ms  p90",          lg.percentile(90), sg.percentile(90), 30);

            row("volume/min  mean",     live.volPerMinMean(),    shred.volPerMinMean(),    15);
            row("volume/change  mean",  live.volPerChangeMean(), shred.volPerChangeMean(), 25);

            Histogram ll = live.distinctLevelsPerMinHist(), sl = shred.distinctLevelsPerMinHist();
            row("distinct levels/min median", ll.percentile(50), sl.percentile(50), 20);
            row("distinct levels/min mean",   ll.mean(),         sl.mean(),         20);
            rowPP("revisit ratio %",    live.revisitRatioMean() * 100,   shred.revisitRatioMean() * 100,   8);
            rowPP("path efficiency %",  live.pathEfficiencyMean() * 100, shred.pathEfficiencyMean() * 100, 10);

            String[] bk = { "OPEN", "MORNING", "MIDDAY", "CLOSE" };
            for (int b = 0; b < 4; b++)
                row("changes/min  " + bk[b], live.bucketChangesPerMinAvg(b), shred.bucketChangesPerMinAvg(b), 15);
            for (int b = 0; b < 4; b++)
                row("|move| bps  " + bk[b], live.bucketAbsRetBpsMean(b), shred.bucketAbsRetBpsMean(b), 15);
        }

        /** Relative-tolerance row. */
        private void row(String name, double live, double shred, double tolPct) {
            double d = live == 0 ? (shred == 0 ? 0 : 100) : (shred - live) / live * 100.0;
            flagRow(name, live, shred, "%+9.1f%%".formatted(d), Math.abs(d) <= tolPct);
        }

        /** Absolute-tolerance row (for near-zero quantities). */
        private void rowAbs(String name, double live, double shred, double tolAbs) {
            flagRow(name, live, shred, "%+9.3f ".formatted(shred - live), Math.abs(shred - live) <= tolAbs);
        }

        /** Percentage-point-tolerance row (for rates already in %). */
        private void rowPP(String name, double live, double shred, double tolPP) {
            flagRow(name, live, shred, "%+8.1fpp".formatted(shred - live), Math.abs(shred - live) <= tolPP);
        }

        private void flagRow(String name, double live, double shred, String delta, boolean ok) {
            sb.append("%-34s %12.2f %12.2f %10s   %s%n".formatted(name, live, shred, delta, ok ? "ok" : "OFF"));
        }
    }
}
