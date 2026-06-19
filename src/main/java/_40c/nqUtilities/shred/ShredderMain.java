package _40c.nqUtilities.shred;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The application: <strong>read 1-minute bars from a source database, shred them into tick-level
 * data, verify the shredded ticks reproduce the live behaviour metrics, and save them to a new
 * database</strong>.
 *
 * <pre>
 *   java _40c.nqUtilities.shred.ShredderMain [configFile]
 * </pre>
 *
 * <p>Pipeline: read ({@link TickStore}) → model ({@link PriceMoveAnalyzer} → {@link TickModel}) →
 * bars ({@link BarBuilder}) → shred ({@link TickShredder}) → verify ({@link Validation}) → save
 * ({@link TickStore#write}). Because the bars are aggregated from real session ticks, this is a
 * closed-loop test: a faithful shredder must regenerate the same statistics the bars came from.
 */
public final class ShredderMain {

    private static final Logger log = LoggerFactory.getLogger(ShredderMain.class);

    public static void main(String[] args) {
        ShredConfig cfg = ShredConfig.load(args.length > 0 ? args[0] : null);
        log.info("Config: source={} output={} symbol={} days={} targetBars={}",
                cfg.sourceDb(), cfg.outputDb(), cfg.symbol(), cfg.days(), cfg.targetBars());

        var source   = new TickStore(cfg.sourceDb(), cfg.symbol());
        var output   = new TickStore(cfg.outputDb(), cfg.symbol());
        var analyzer = new PriceMoveAnalyzer();

        // ---- read: load the regular session of the most recent days ----
        List<TradingDay> all    = source.discoverTradingDays();
        List<TradingDay> recent = all.subList(Math.max(0, all.size() - cfg.days()), all.size());
        log.info("Loading session ticks for the {} most recent days of {}...", recent.size(), cfg.symbol());

        List<DayTicks> loaded;
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            loaded = recent.stream()
                    .map(d -> exec.submit(() -> source.loadWindow(d,
                            d.startMsInclusive() + PriceMoveAnalyzer.SESSION_START_SEC * 1000L,
                            d.startMsInclusive() + PriceMoveAnalyzer.SESSION_END_SEC   * 1000L)))
                    .toList()
                    .stream().map(ShredderMain::join)
                    .filter(dt -> dt.size() > 0)
                    .sorted(Comparator.comparing(DayTicks::date).reversed())
                    .toList();
        }

        // ---- bars + live model: newest-first until ~targetBars ----
        var selectedDays = new ArrayList<DayTicks>();
        var allBars      = new ArrayList<List<CandleBar>>();
        int barCount = 0;
        for (DayTicks dt : loaded) {
            List<CandleBar> bars = BarBuilder.fromDay(dt);
            if (bars.isEmpty()) continue;
            selectedDays.add(dt);
            allBars.add(bars);
            barCount += bars.size();
            if (barCount >= cfg.targetBars()) break;
        }

        var liveProfile = new PriceMoveProfile("LIVE", 0);
        for (DayTicks dt : selectedDays) liveProfile.merge(analyzer.analyze(dt));

        log.info("Built live model from {} days ({} distinct changes); shredding {} bars.",
                selectedDays.size(), String.format("%,d", liveProfile.totalChanges()), barCount);

        // ---- shred every day's bars (parallel), keeping each day's shredded ticks ----
        var model    = TickModel.from(liveProfile);
        var shredder = new TickShredder(model);

        List<DayTicks> shreddedDays;
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            shreddedDays = allBars.stream()
                    .filter(bars -> !bars.isEmpty())
                    .map(bars -> {
                        long seed = bars.get(0).date().toEpochDay() * 1_000_003L + bars.size();
                        return exec.submit(() -> shred(bars, shredder, new SplittableRandom(seed)));
                    })
                    .toList()
                    .stream().map(ShredderMain::join)
                    .toList();
        }

        // ---- verify: analyse the shredded ticks back into a profile ----
        var shreddedProfile = new PriceMoveProfile("SHREDDED", 0);
        for (DayTicks dt : shreddedDays) shreddedProfile.merge(analyzer.analyze(dt));

        // ---- save: write every shredded tick to the output database ----
        output.resetOutput();
        long rowsWritten = 0;
        for (DayTicks dt : shreddedDays) rowsWritten += output.write(dt);

        // ---- report ----
        log.info("========== LIVE model ==========\n{}", liveProfile.report());
        log.info("========== SHREDDED model ==========\n{}", shreddedProfile.report());
        Validation.compare(liveProfile, shreddedProfile);
        log.info("Saved {} shredded ticks for {} to {}",
                String.format("%,d", rowsWritten), cfg.symbol(), cfg.outputDb());
    }

    /** Shred one day's bars into a single time-ordered {@link DayTicks} (cumulative volume). */
    private static DayTicks shred(List<CandleBar> bars, TickShredder shredder, SplittableRandom rng) {
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
        return new DayTicks(bars.get(0).date(), ts, ltp, vol, oi, total);
    }

    private static <T> T join(Future<T> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new IllegalStateException("Task join failed", e);
        }
    }

    private ShredderMain() {}

    // =============================================================================================

    /**
     * Run configuration, loaded from a {@code .properties} file. Resolution: a path given on the
     * command line is read from disk; otherwise the bundled {@code shred.properties} on the classpath
     * is used. Missing keys take the defaults below, so a partial file (or none) still runs.
     */
    record ShredConfig(String sourceDb, String outputDb, String symbol, int days, int targetBars) {

        private static final String DEFAULT_SOURCE_DB = "C:/novaquant/data/sqlite/nifty_ticks.db";
        private static final String DEFAULT_OUTPUT_DB = "C:/novaquant/data/sqlite/nifty_ticks_shredded.db";
        private static final String DEFAULT_SYMBOL    = "NIFTY-1";
        private static final int    DEFAULT_DAYS        = 18;
        private static final int    DEFAULT_TARGET_BARS = 5000;
        private static final String CLASSPATH_RESOURCE  = "shred.properties";

        static ShredConfig load(String path) {
            var props = new Properties();
            if (path != null) {
                try (InputStream in = Files.newInputStream(Path.of(path))) {
                    props.load(in);
                } catch (IOException e) {
                    throw new IllegalArgumentException("Cannot read config file: " + path, e);
                }
            } else {
                try (InputStream in = ShredConfig.class.getClassLoader().getResourceAsStream(CLASSPATH_RESOURCE)) {
                    if (in != null) props.load(in);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read bundled " + CLASSPATH_RESOURCE, e);
                }
            }
            return new ShredConfig(
                    props.getProperty("source.db", DEFAULT_SOURCE_DB).trim(),
                    props.getProperty("output.db", DEFAULT_OUTPUT_DB).trim(),
                    props.getProperty("symbol", DEFAULT_SYMBOL).trim(),
                    parseInt(props, "days", DEFAULT_DAYS),
                    parseInt(props, "target.bars", DEFAULT_TARGET_BARS));
        }

        private static int parseInt(Properties props, String key, int dflt) {
            String v = props.getProperty(key);
            if (v == null || v.isBlank()) return dflt;
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Config '%s' is not an integer: %s".formatted(key, v), e);
            }
        }
    }

    // =============================================================================================

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

            Histogram lb = live.absRetBpsHist(), sbh = shred.absRetBpsHist();
            row("|move| bps  median",   lb.percentile(50), sbh.percentile(50), 15);
            row("|move| bps  p90",      lb.percentile(90), sbh.percentile(90), 15);
            row("|move| bps  p99",      lb.percentile(99), sbh.percentile(99), 20);
            row("|move| bps  mean",     lb.mean(),         sbh.mean(),         15);
            row("|move| bps  sd",       lb.stdDev(),       sbh.stdDev(),       20);

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
