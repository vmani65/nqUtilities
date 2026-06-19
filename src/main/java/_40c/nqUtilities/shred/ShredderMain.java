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
        switch (cfg.mode()) {
            case "learn" -> learn(cfg);
            case "shred" -> shred(cfg);
            default      -> selftest(cfg);
        }
    }

    // ============================================================================ modes

    /**
     * <strong>selftest</strong> (default, closed loop): aggregate real ticks into 1-min bars, build
     * the live model, shred those bars back into ticks, and verify the shredded statistics reproduce
     * the live ones. Requires the tick DB.
     */
    private static void selftest(ShredConfig cfg) {
        log.info("Mode=selftest  source={} output={} symbol={} days={} targetBars={}",
                cfg.sourceDb(), cfg.outputDb(), cfg.symbol(), cfg.days(), cfg.targetBars());

        LiveBuild b = buildFromTicks(cfg);
        log.info("Built live model from {} days ({} distinct changes); shredding {} bars.",
                b.selectedDays.size(), fmt(b.liveProfile.totalChanges()), b.barCount);

        var shredder     = new TickShredder(TickModel.from(b.liveProfile));
        var shreddedDays = shredAll(b.allBars, shredder);

        var analyzer        = new PriceMoveAnalyzer();
        var shreddedProfile = new PriceMoveProfile("SHREDDED", 0);
        for (DayTicks dt : shreddedDays) shreddedProfile.merge(analyzer.analyze(dt));

        var output = new TickStore(cfg.outputDb(), cfg.symbol());
        output.resetOutput();
        long rows = 0;
        for (DayTicks dt : shreddedDays) rows += output.write(dt);

        log.info("========== LIVE model ==========\n{}", b.liveProfile.report());
        log.info("========== SHREDDED model ==========\n{}", shreddedProfile.report());
        Validation.compare(b.liveProfile, shreddedProfile);
        log.info("Saved {} shredded ticks for {} to {}", fmt(rows), cfg.symbol(), cfg.outputDb());
    }

    /**
     * <strong>learn</strong> (Host A): build the live model from the tick DB exactly as selftest does,
     * then freeze the whole {@link PriceMoveProfile} to {@code model.file}. No shredding. The saved file
     * is the portable intelligence copied to Host B; a round-trip check confirms it reloads identically.
     */
    private static void learn(ShredConfig cfg) {
        PriceMoveProfile profile;
        int daysUsed;
        if ("queue".equals(cfg.sourceType())) {
            log.info("Mode=learn  source=queue dir={} symbol={} from={} to={} sessionEndSec={} minDistinctPrices={} model={}",
                    cfg.queueDir(), cfg.symbol(), cfg.from(), cfg.to(), cfg.learnSessionEndSec(),
                    cfg.minDistinctPrices(), cfg.modelFile());
            profile = new PriceMoveProfile("LIVE", 0);
            var analyzer = new PriceMoveAnalyzer();
            var queue    = new QueueTickSource(cfg.queueDir(), cfg.symbol(),
                    cfg.minDistinctPrices(), cfg.learnSessionEndSec());
            daysUsed = queue.forEachDay(cfg.from(), cfg.to(), day -> profile.merge(analyzer.analyze(day)));
        } else {
            log.info("Mode=learn  source=sqlite db={} symbol={} days={} targetBars={} model={}",
                    cfg.sourceDb(), cfg.symbol(), cfg.days(), cfg.targetBars(), cfg.modelFile());
            LiveBuild b = buildFromTicks(cfg);
            profile  = b.liveProfile;
            daysUsed = b.selectedDays.size();
        }

        log.info("========== LIVE model ==========\n{}", profile.report());

        Path modelFile = Path.of(cfg.modelFile());
        profile.save(modelFile);
        log.info("Saved frozen intelligence ({} distinct changes from {} days) to {}",
                fmt(profile.totalChanges()), daysUsed, modelFile.toAbsolutePath());

        verifyRoundTrip(profile, PriceMoveProfile.load(modelFile));
    }

    /**
     * <strong>shred</strong> (Host B): load the frozen model, read 1-min candles from the candle DB,
     * shred each bar into ticks, write them to the output DB, then validate the shredded statistics
     * against the <em>frozen</em> baseline — so validation works with no tick DB present.
     */
    private static void shred(ShredConfig cfg) {
        log.info("Mode=shred  model={} candleDb={} candleSymbol={} from={} to={} cap={} output={}",
                cfg.modelFile(), cfg.candleDb(), cfg.candleSymbol(), cfg.from(), cfg.to(),
                cfg.targetBars(), cfg.outputDb());

        Path modelFile = Path.of(cfg.modelFile());
        PriceMoveProfile loaded = PriceMoveProfile.load(modelFile);
        log.info("Loaded frozen intelligence ({} distinct changes) from {}",
                fmt(loaded.totalChanges()), modelFile.toAbsolutePath());

        var shredder = new TickShredder(TickModel.from(loaded));
        var candles  = new CandleStore(cfg.candleDb(), cfg.candleSymbol());
        List<List<CandleBar>> days = candles.loadDays(cfg.from(), cfg.to(), cfg.targetBars());
        int barCount = days.stream().mapToInt(List::size).sum();
        if (barCount == 0) {
            log.warn("No session candles for symbol={} in range [{}..{}] — nothing to shred.",
                    cfg.candleSymbol(), cfg.from(), cfg.to());
            return;
        }
        log.info("Loaded {} 1-min bars across {} days; shredding...", fmt(barCount), days.size());

        var shreddedDays = shredAll(days, shredder);

        var output = new TickStore(cfg.outputDb(), cfg.symbol());
        output.resetOutput();
        long rows = 0;
        for (DayTicks dt : shreddedDays) rows += output.write(dt);

        var analyzer        = new PriceMoveAnalyzer();
        var shreddedProfile = new PriceMoveProfile("SHREDDED", 0);
        for (DayTicks dt : shreddedDays) shreddedProfile.merge(analyzer.analyze(dt));

        log.info("========== LIVE model (frozen) ==========\n{}", loaded.report());
        log.info("========== SHREDDED model ==========\n{}", shreddedProfile.report());
        Validation.compare(loaded, shreddedProfile);
        log.info("Saved {} shredded ticks for {} to {}", fmt(rows), cfg.symbol(), cfg.outputDb());
    }

    // ============================================================================ shared steps

    /** Bars/days/profile selected from the tick DB (shared by selftest and learn). */
    private record LiveBuild(List<DayTicks> selectedDays, List<List<CandleBar>> allBars,
                             int barCount, PriceMoveProfile liveProfile) {}

    /**
     * Reads the regular session of the most-recent {@code days}, selects days newest-first until
     * {@code targetBars} 1-min bars are gathered, and merges those days into the {@code LIVE} profile.
     */
    private static LiveBuild buildFromTicks(ShredConfig cfg) {
        var source   = new TickStore(cfg.sourceDb(), cfg.symbol());
        var analyzer = new PriceMoveAnalyzer();

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
        return new LiveBuild(selectedDays, allBars, barCount, liveProfile);
    }

    /** Shreds every day's bars in parallel into one {@link DayTicks} per day (per-day RNG seed). */
    private static List<DayTicks> shredAll(List<List<CandleBar>> allBars, TickShredder shredder) {
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            return allBars.stream()
                    .filter(bars -> !bars.isEmpty())
                    .map(bars -> {
                        long seed = bars.get(0).date().toEpochDay() * 1_000_003L + bars.size();
                        return exec.submit(() -> shred(bars, shredder, new SplittableRandom(seed)));
                    })
                    .toList()
                    .stream().map(ShredderMain::join)
                    .toList();
        }
    }

    /** Confirms a saved-then-reloaded profile yields identical headline percentiles. */
    private static void verifyRoundTrip(PriceMoveProfile saved, PriceMoveProfile reloaded) {
        String[] names = { "absRetBps p50", "absRetBps p90", "absRetBps p99",
                           "changesPerMin p50", "gapMs p50" };
        double[] a = { saved.absRetBpsHist().percentile(50), saved.absRetBpsHist().percentile(90),
                       saved.absRetBpsHist().percentile(99), saved.changesPerMinHist().percentile(50),
                       saved.gapMsHist().percentile(50) };
        double[] r = { reloaded.absRetBpsHist().percentile(50), reloaded.absRetBpsHist().percentile(90),
                       reloaded.absRetBpsHist().percentile(99), reloaded.changesPerMinHist().percentile(50),
                       reloaded.gapMsHist().percentile(50) };
        var sb = new StringBuilder("========== ROUND-TRIP CHECK (saved vs reloaded) ==========\n");
        sb.append("%-22s %12s %12s   %s%n".formatted("metric", "saved", "reloaded", ""));
        boolean allOk = saved.totalChanges() == reloaded.totalChanges();
        for (int i = 0; i < names.length; i++) {
            boolean ok = a[i] == r[i];
            allOk &= ok;
            sb.append("%-22s %12.4f %12.4f   %s%n".formatted(names[i], a[i], r[i], ok ? "ok" : "MISMATCH"));
        }
        sb.append(allOk ? "round-trip exact" : "round-trip MISMATCH");
        log.info(sb.toString());
    }

    private static String fmt(long v) { return String.format("%,d", v); }

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
    record ShredConfig(String mode, String sourceType, String sourceDb, String queueDir,
                       String outputDb, String symbol, int days, int targetBars, String modelFile,
                       String candleDb, String candleSymbol, String from, String to,
                       int minDistinctPrices, int learnSessionEndSec) {

        private static final String DEFAULT_MODE        = "selftest";
        private static final String DEFAULT_SOURCE_TYPE = "sqlite";
        private static final String DEFAULT_SOURCE_DB   = "C:/novaquant/data/sqlite/nifty_ticks.db";
        private static final String DEFAULT_QUEUE_DIR   = "C:/novaquant/data/ticks";
        private static final int    DEFAULT_MIN_DISTINCT_PRICES = 10;
        private static final String DEFAULT_OUTPUT_DB = "C:/novaquant/data/sqlite/nifty_ticks_shredded.db";
        private static final String DEFAULT_SYMBOL    = "NIFTY-1";
        private static final int    DEFAULT_DAYS         = 18;
        private static final int    DEFAULT_TARGET_BARS  = 5000;
        private static final String DEFAULT_MODEL_FILE   = "model/nifty1.profile.json";
        private static final String DEFAULT_CANDLE_DB    = "D:/Softwares/sqlite/nifty.db";
        private static final String DEFAULT_CANDLE_SYMBOL = "NIFTY";
        private static final String CLASSPATH_RESOURCE   = "shred.properties";

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
                    props.getProperty("mode", DEFAULT_MODE).trim().toLowerCase(),
                    props.getProperty("source.type", DEFAULT_SOURCE_TYPE).trim().toLowerCase(),
                    props.getProperty("source.db", DEFAULT_SOURCE_DB).trim(),
                    props.getProperty("queue.dir", DEFAULT_QUEUE_DIR).trim(),
                    props.getProperty("output.db", DEFAULT_OUTPUT_DB).trim(),
                    props.getProperty("symbol", DEFAULT_SYMBOL).trim(),
                    parseInt(props, "days", DEFAULT_DAYS),
                    parseInt(props, "target.bars", DEFAULT_TARGET_BARS),
                    props.getProperty("model.file", DEFAULT_MODEL_FILE).trim(),
                    props.getProperty("candle.db", DEFAULT_CANDLE_DB).trim(),
                    props.getProperty("candle.symbol", DEFAULT_CANDLE_SYMBOL).trim(),
                    props.getProperty("from", "").trim(),
                    props.getProperty("to", "").trim(),
                    parseInt(props, "learn.min.distinct.prices", DEFAULT_MIN_DISTINCT_PRICES),
                    parseSecOfDay(props.getProperty("learn.session.end", ""),
                            PriceMoveAnalyzer.SESSION_END_SEC));
        }

        /** Parses an {@code HH:mm} IST time to second-of-day; blank/unset returns {@code dflt}. */
        private static int parseSecOfDay(String v, int dflt) {
            if (v == null || v.isBlank()) return dflt;
            String[] p = v.trim().split(":");
            if (p.length != 2) throw new IllegalArgumentException("learn.session.end must be HH:mm: " + v);
            return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60;
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
