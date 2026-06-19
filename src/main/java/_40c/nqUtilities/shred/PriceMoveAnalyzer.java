package _40c.nqUtilities.shred;

import java.util.HashSet;

/**
 * Characterises tick-to-tick behaviour for one trading day over the regular session only
 * (09:15:00–15:30:00 IST). It walks the day's ticks in time order, keeps only <em>distinct</em> LTP
 * changes (poll repeats dropped), and measures each move both in absolute terms (points, 0.05-tick
 * units) and — the part that transfers across price levels — as a % return in basis points relative
 * to the from-price. Stateless: one instance is shared across all per-day threads.
 */
public final class PriceMoveAnalyzer {

    /** Regular session, seconds-of-day IST. */
    public static final int SESSION_START_SEC = 9 * 3600 + 15 * 60;  // 09:15:00
    public static final int SESSION_END_SEC   = 15 * 3600 + 30 * 60; // 15:30:00 (exclusive)

    private static final double TICK = 0.05;
    private static final double EPS  = 1e-9;

    public PriceMoveProfile analyze(DayTicks day) {
        var p = new PriceMoveProfile(day.date().toString(), 1);

        int n = day.size();
        int[]     minuteChanges = new int[1440];
        boolean[] minuteActive  = new boolean[1440];
        long[]    minuteLastVol  = new long[1440];

        double lastPrice      = Double.NaN;
        long   lastChangeMs    = 0;
        long   lastChangeVol   = 0;
        long   sessionOpenVol  = 0;
        int    prevSign         = 0;

        // per-minute oscillation/revisit tracking (a -> b -> c -> b -> d -> c -> a ...)
        int          osMinute   = -1;
        var          osLevels   = new HashSet<Long>();   // distinct 0.05 levels seen this minute
        double       osFirst    = 0;                     // first price of the minute
        double       osLast      = 0;                    // last price of the minute
        double       osPrev      = 0;                    // previous in-minute price (for gross travel)
        double       osGross     = 0;                    // sum |Δ| within the minute
        int          osChanges   = 0;                    // in-minute distinct changes
        int          osRevisits  = 0;                    // changes landing on an already-seen level

        for (int i = 0; i < n; i++) {
            long t   = day.tsMs(i);
            int  sec = (int) (((t + TradingDay.IST_OFFSET_MS) % TradingDay.DAY_MS) / 1000);
            if (sec < SESSION_START_SEC || sec >= SESSION_END_SEC) continue;

            int    minute = sec / 60;
            double price  = day.ltp(i);
            long   vol    = day.vol(i);

            if (minute != osMinute) {                    // minute boundary — flush the one just ended
                flushOscillation(p, osChanges, osRevisits, osLevels.size(), osFirst, osLast, osGross);
                osMinute = minute;
                osLevels.clear();
                osLevels.add(level(price));
                osFirst = osLast = osPrev = price;
                osGross = 0; osChanges = 0; osRevisits = 0;
            }

            minuteActive[minute] = true;
            minuteLastVol[minute] = vol;
            p.sessionRows++;

            // session OHLC context
            if (Double.isNaN(p.sessOpen)) { p.sessOpen = price; p.sessHigh = price; p.sessLow = price; }
            if (price > p.sessHigh) p.sessHigh = price;
            if (price < p.sessLow)  p.sessLow  = price;
            p.sessClose = price;

            if (Double.isNaN(lastPrice)) {           // first session tick — baseline only
                lastPrice = price;
                lastChangeMs = t;
                lastChangeVol = vol;
                sessionOpenVol = vol;
                continue;
            }
            if (price == lastPrice) continue;          // poll repeat — not a distinct change

            double delta  = price - lastPrice;
            double absd   = Math.abs(delta);
            double retBps = (delta / lastPrice) * 10_000.0;   // % return in basis points

            p.absMovePts.add(absd);
            p.signedRetBps.add(retBps);
            p.absRetBps.add(Math.abs(retBps));
            p.moveTicks.add(Math.round(absd / TICK));
            p.gapMs.add(t - lastChangeMs);

            long volDelta = vol - lastChangeVol;
            if (volDelta >= 0) p.volPerChange.add(volDelta);

            int sign = delta > 0 ? 1 : -1;
            if (sign > 0) p.upChanges++; else p.downChanges++;
            if (prevSign != 0 && sign != prevSign) p.reversals++;
            prevSign = sign;

            int b = bucketOf(sec);
            p.bucketAbsRetBps[b].add(Math.abs(retBps));

            if (absd < p.smallestMove) p.smallestMove = absd;
            double ratio = absd / TICK;
            if (Math.abs(ratio - Math.rint(ratio)) < EPS) p.movesMultipleOf005++;

            minuteChanges[minute]++;
            p.totalChanges++;

            // oscillation: this changed price is a level visited this minute; is it a revisit?
            long lv = level(price);
            if (!osLevels.add(lv)) osRevisits++;   // add() returns false if already present
            osGross += Math.abs(price - osPrev);
            osPrev = price;
            osLast = price;
            osChanges++;

            lastPrice = price;
            lastChangeMs = t;
            lastChangeVol = vol;
        }
        flushOscillation(p, osChanges, osRevisits, osLevels.size(), osFirst, osLast, osGross);  // last minute

        // per-minute aggregates: changes/min distribution, intraday buckets, volume/min
        long prevMinLastVol = -1;
        for (int m = 0; m < 1440; m++) {
            if (!minuteActive[m]) continue;
            p.sessionMinutes++;
            p.changesPerMin.add(minuteChanges[m]);

            int b = bucketOf(m * 60);
            p.bucketActiveMin[b]++;
            p.bucketChanges[b] += minuteChanges[m];
            p.bucketChangesPerMin[b].add(minuteChanges[m]);

            long base   = prevMinLastVol < 0 ? sessionOpenVol : prevMinLastVol;
            long traded = minuteLastVol[m] - base;
            if (traded >= 0) p.volPerMin.add(traded);
            prevMinLastVol = minuteLastVol[m];
        }

        if (p.totalChanges == 0) p.smallestMove = Double.NaN;
        return p;
    }

    /** 0.05-grid level key for a price, so revisits to the same level are detectable. */
    private static long level(double price) { return Math.round(price / TICK); }

    /** Records one finished minute's oscillation metrics (no-op for empty/flat minutes). */
    private static void flushOscillation(PriceMoveProfile p, int changes, int revisits,
                                         int distinctLevels, double first, double last, double gross) {
        if (changes == 0) return;
        p.distinctLevelsPerMin.add(distinctLevels);
        p.revisitRatio.add((double) revisits / changes);
        if (gross > 0) p.pathEfficiency.add(Math.abs(last - first) / gross);
    }

    private static int bucketOf(int sec) {
        if (sec < 9 * 3600 + 45 * 60) return 0;   // OPEN    <09:45
        if (sec < 12 * 3600)          return 1;   // MORNING <12:00
        if (sec < 14 * 3600)          return 2;   // MIDDAY  <14:00
        return 3;                                 // CLOSE
    }
}

/**
 * The "intelligence" produced for one trading day (or, when several are {@link #merge merged}, for
 * the whole capture window): a statistical characterisation of how price actually moves tick-to-tick
 * during the regular session, in units that transfer across price levels.
 *
 * <p>The headline is the per-change <strong>% return</strong> (basis points). All accumulators are
 * mergeable, so per-day threads each emit one profile and the runner folds them into a global model.
 */
final class PriceMoveProfile {

    /** Session bucket boundaries (IST), used to expose the intraday activity profile. */
    enum Bucket {
        OPEN("09:15-09:45"), MORNING("09:45-12:00"), MIDDAY("12:00-14:00"), CLOSE("14:00-15:30");
        final String label;
        Bucket(String l) { this.label = l; }
    }

    private final String label;     // date for a single day, or e.g. "LIVE" for the aggregate
    private int daysCount;

    // --- distributions ---
    final Histogram changesPerMin = new Histogram(0, 250, 1);     // distinct changes per session minute
    final Histogram absMovePts     = new Histogram(0, 60, 0.05);  // |Δprice| in points
    final Histogram signedRetBps   = new Histogram(-150, 150, 0.1);// signed % return, basis points
    final Histogram absRetBps      = new Histogram(0, 150, 0.1);  // |% return|, basis points
    final Histogram moveTicks      = new Histogram(0, 500, 1);    // |Δprice| / 0.05, rounded
    final Histogram gapMs          = new Histogram(0, 30_000, 50);// ms between consecutive changes

    final Stats volPerMin    = new Stats();   // traded volume per session minute (cumulative Δ)
    final Stats volPerChange = new Stats();   // traded volume between consecutive price changes

    // --- intra-minute oscillation / revisit behaviour (a->b->c->b->d->c->a ...) ---
    final Histogram distinctLevelsPerMin = new Histogram(0, 250, 1); // distinct 0.05 levels per minute
    final Stats     revisitRatio         = new Stats();              // fraction of changes hitting a seen level
    final Stats     pathEfficiency       = new Stats();              // |net move| / gross travel per minute

    // --- counters ---
    long sessionRows;     // session ticks examined (after dedup these become changes)
    long totalChanges;    // distinct price-change events
    long upChanges;
    long downChanges;
    long reversals;       // sign flips between consecutive changes
    long sessionMinutes;  // distinct active session minutes

    // --- tick-size detection ---
    double smallestMove = Double.POSITIVE_INFINITY;
    long   movesMultipleOf005;

    // --- intraday buckets ---
    final Stats[] bucketAbsRetBps = { new Stats(), new Stats(), new Stats(), new Stats() };
    final long[]  bucketActiveMin = new long[4];
    final long[]  bucketChanges    = new long[4];
    final Histogram[] bucketChangesPerMin = {
            new Histogram(0, 250, 1), new Histogram(0, 250, 1),
            new Histogram(0, 250, 1), new Histogram(0, 250, 1) };

    // --- per-day session context (NaN / undefined on the aggregate) ---
    double sessOpen = Double.NaN, sessHigh = Double.NaN, sessLow = Double.NaN, sessClose = Double.NaN;

    PriceMoveProfile(String label, int daysCount) {
        this.label = label;
        this.daysCount = daysCount;
    }

    /** Fold another profile into this one (for building the global model). */
    void merge(PriceMoveProfile o) {
        daysCount += o.daysCount;
        changesPerMin.merge(o.changesPerMin);
        absMovePts.merge(o.absMovePts);
        signedRetBps.merge(o.signedRetBps);
        absRetBps.merge(o.absRetBps);
        moveTicks.merge(o.moveTicks);
        gapMs.merge(o.gapMs);
        volPerMin.merge(o.volPerMin);
        volPerChange.merge(o.volPerChange);
        distinctLevelsPerMin.merge(o.distinctLevelsPerMin);
        revisitRatio.merge(o.revisitRatio);
        pathEfficiency.merge(o.pathEfficiency);
        sessionRows  += o.sessionRows;
        totalChanges += o.totalChanges;
        upChanges    += o.upChanges;
        downChanges  += o.downChanges;
        reversals    += o.reversals;
        sessionMinutes += o.sessionMinutes;
        movesMultipleOf005 += o.movesMultipleOf005;
        if (o.smallestMove < smallestMove) smallestMove = o.smallestMove;
        for (int b = 0; b < 4; b++) {
            bucketAbsRetBps[b].merge(o.bucketAbsRetBps[b]);
            bucketActiveMin[b] += o.bucketActiveMin[b];
            bucketChanges[b]   += o.bucketChanges[b];
            bucketChangesPerMin[b].merge(o.bucketChangesPerMin[b]);
        }
        // session OHLC intentionally not merged — meaningless across days
    }

    // --- accessors used by the shredder model and the validator ---
    Histogram bucketChangesPerMin(int b) { return bucketChangesPerMin[b]; }
    Histogram changesPerMinHist()        { return changesPerMin; }
    Histogram absRetBpsHist()            { return absRetBps; }
    Histogram absMovePtsHist()           { return absMovePts; }
    Histogram gapMsHist()                { return gapMs; }
    long      totalChanges()             { return totalChanges; }
    double    upPctValue()               { return upPct(); }
    double    reversalRate()             { return reversalPct(); }
    double    signedRetMeanBps()         { return signedRetBps.mean(); }
    double    volPerMinMean()            { return volPerMin.mean(); }
    double    volPerChangeMean()         { return volPerChange.mean(); }
    Histogram distinctLevelsPerMinHist() { return distinctLevelsPerMin; }
    double    revisitRatioMean()         { return revisitRatio.mean(); }
    double    pathEfficiencyMean()       { return pathEfficiency.mean(); }
    double    changesPerMinAvgValue()    { return changesPerMinAvg(); }
    double    bucketChangesPerMinAvg(int b) {
        return bucketActiveMin[b] == 0 ? 0 : (double) bucketChanges[b] / bucketActiveMin[b];
    }
    double    bucketAbsRetBpsMean(int b) { return bucketAbsRetBps[b].mean(); }

    private double upPct()       { return totalChanges == 0 ? 0 : 100.0 * upChanges   / totalChanges; }
    private double downPct()     { return totalChanges == 0 ? 0 : 100.0 * downChanges / totalChanges; }
    private double reversalPct()  { return totalChanges < 2 ? 0 : 100.0 * reversals   / (totalChanges - 1); }
    private double mult005Pct()  { return totalChanges == 0 ? 0 : 100.0 * movesMultipleOf005 / totalChanges; }
    private double changesPerMinAvg() { return sessionMinutes == 0 ? 0 : (double) totalChanges / sessionMinutes; }

    String report() {
        if (totalChanges == 0) {
            return "%s  — no price changes in regular session".formatted(label);
        }
        var sb = new StringBuilder();
        sb.append("==== ").append(label);
        if (daysCount > 1) sb.append("  (").append(daysCount).append(" days)");
        sb.append(" ====\n");

        sb.append("  session rows .......... %,d   distinct changes %,d   active minutes %,d%n"
                .formatted(sessionRows, totalChanges, sessionMinutes));

        sb.append("  distinct changes/min .. avg %.1f  median %.0f  p90 %.0f  p99 %.0f  max %.0f%n"
                .formatted(changesPerMinAvg(), changesPerMin.percentile(50), changesPerMin.percentile(90),
                        changesPerMin.percentile(99), changesPerMin.max()));

        sb.append("  move |%%| (bps) ........ median %.2f  p90 %.2f  p99 %.2f  max %.2f  (mean %.2f, sd %.2f)%n"
                .formatted(absRetBps.percentile(50), absRetBps.percentile(90), absRetBps.percentile(99),
                        absRetBps.max(), absRetBps.mean(), absRetBps.stdDev()));

        sb.append("  move |pts| ............ median %.2f  p90 %.2f  p99 %.2f  max %.2f%n"
                .formatted(absMovePts.percentile(50), absMovePts.percentile(90),
                        absMovePts.percentile(99), absMovePts.max()));

        sb.append("  move (0.05 ticks) ..... median %.0f  p90 %.0f  p99 %.0f  max %.0f%n"
                .formatted(moveTicks.percentile(50), moveTicks.percentile(90),
                        moveTicks.percentile(99), moveTicks.max()));

        sb.append("  signed return (bps) ... mean %+.3f  sd %.3f%n"
                .formatted(signedRetBps.mean(), signedRetBps.stdDev()));

        sb.append("  direction ............. up %.1f%%  down %.1f%%  reversal rate %.1f%%%n"
                .formatted(upPct(), downPct(), reversalPct()));

        sb.append("  gap between changes ... median %.0fms  p90 %.0fms  p99 %.0fms  max %.0fms%n"
                .formatted(gapMs.percentile(50), gapMs.percentile(90), gapMs.percentile(99), gapMs.max()));

        sb.append("  volume / minute ....... mean %,.0f  max %,.0f%n"
                .formatted(volPerMin.mean(), volPerMin.max()));
        sb.append("  volume / change ....... mean %,.0f  max %,.0f%n"
                .formatted(volPerChange.mean(), volPerChange.max()));

        sb.append("  intra-min oscillation . distinct levels/min median %.0f  mean %.1f  max %.0f%n"
                .formatted(distinctLevelsPerMin.percentile(50), distinctLevelsPerMin.mean(), distinctLevelsPerMin.max()));
        sb.append("                          revisit ratio %.1f%%   path efficiency %.1f%% (net/gross travel)%n"
                .formatted(revisitRatio.mean() * 100, pathEfficiency.mean() * 100));

        sb.append("  tick size ............. smallest move %.2f pt   %.1f%% of moves are exact 0.05 multiples%n"
                .formatted(smallestMove, mult005Pct()));

        sb.append("  intraday profile:\n");
        for (Bucket bk : Bucket.values()) {
            int b = bk.ordinal();
            double cpm = bucketActiveMin[b] == 0 ? 0 : (double) bucketChanges[b] / bucketActiveMin[b];
            sb.append("    %-12s (%s)  %6.1f changes/min   |move| %.2f bps%n"
                    .formatted(bk.name(), bk.label, cpm, bucketAbsRetBps[b].mean()));
        }

        if (!Double.isNaN(sessOpen)) {
            double rangePct = sessOpen == 0 ? 0 : (sessHigh - sessLow) / sessOpen * 100;
            sb.append("  session O/H/L/C ....... %.2f / %.2f / %.2f / %.2f   range %.2f%%%n"
                    .formatted(sessOpen, sessHigh, sessLow, sessClose, rangePct));
        }
        return sb.toString();
    }
}
