package _40c.nqUtilities.daily.analysis;

import java.time.LocalDate;

/**
 * The "intelligence" produced for one trading day (or, when several are {@link #merge merged},
 * for the whole capture window): a statistical characterisation of how NIFTY-1 actually moves
 * tick-to-tick during the regular session, expressed in units that transfer across price levels.
 *
 * <p>The headline is the per-change <strong>% return</strong> (basis points): the move from
 * one distinct LTP to the next, relative to the from-price. A 12-point move at 21000 and the
 * proportional move at 9000 land in the same bps bin — which is exactly what a shredder needs
 * to apply realistic moves to historical candles at any price level.
 *
 * <p>All accumulators are mergeable, so the per-day virtual threads each emit one profile and
 * the runner folds them into a single global model.
 */
public final class PriceMoveProfile implements AnalysisResult {

    /** Session bucket boundaries (IST), used to expose the intraday activity profile. */
    public enum Bucket {
        OPEN("09:15-09:45"), MORNING("09:45-12:00"), MIDDAY("12:00-14:00"), CLOSE("14:00-15:30");
        final String label;
        Bucket(String l) { this.label = l; }
    }

    private final String label;     // date for a single day, or e.g. "ALL DAYS" for the aggregate
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

    public PriceMoveProfile(String label, int daysCount) {
        this.label = label;
        this.daysCount = daysCount;
    }

    /** Fold another profile into this one (for building the global model). */
    public void merge(PriceMoveProfile o) {
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
    public Histogram bucketChangesPerMin(int b) { return bucketChangesPerMin[b]; }
    public Histogram changesPerMinHist()        { return changesPerMin; }
    public Histogram absRetBpsHist()            { return absRetBps; }
    public Histogram absMovePtsHist()           { return absMovePts; }
    public Histogram gapMsHist()                { return gapMs; }
    public long      totalChanges()             { return totalChanges; }
    public double    upPctValue()               { return upPct(); }
    public double    reversalRate()             { return reversalPct(); }
    public double    signedRetMeanBps()         { return signedRetBps.mean(); }
    public double    volPerMinMean()            { return volPerMin.mean(); }
    public double    volPerChangeMean()         { return volPerChange.mean(); }
    public Histogram distinctLevelsPerMinHist() { return distinctLevelsPerMin; }
    public double    revisitRatioMean()         { return revisitRatio.mean(); }
    public double    pathEfficiencyMean()       { return pathEfficiency.mean(); }
    public double    changesPerMinAvgValue()    { return changesPerMinAvg(); }
    public double    bucketChangesPerMinAvg(int b) {
        return bucketActiveMin[b] == 0 ? 0 : (double) bucketChanges[b] / bucketActiveMin[b];
    }
    public double    bucketAbsRetBpsMean(int b) { return bucketAbsRetBps[b].mean(); }

    private double upPct()       { return totalChanges == 0 ? 0 : 100.0 * upChanges   / totalChanges; }
    private double downPct()     { return totalChanges == 0 ? 0 : 100.0 * downChanges / totalChanges; }
    private double reversalPct()  { return totalChanges < 2 ? 0 : 100.0 * reversals   / (totalChanges - 1); }
    private double mult005Pct()  { return totalChanges == 0 ? 0 : 100.0 * movesMultipleOf005 / totalChanges; }
    private double changesPerMinAvg() { return sessionMinutes == 0 ? 0 : (double) totalChanges / sessionMinutes; }

    @Override
    public String summary() {
        if (totalChanges == 0) {
            return "%s  no session price changes".formatted(label);
        }
        return ("%s  changes=%-6d  ~%.0f/min(med %.0f)  |move| med=%.2fpt/%.2fbps p99=%.2fpt/%.2fbps  "
                + "up=%.0f%% rev=%.0f%%").formatted(
                label, totalChanges, changesPerMinAvg(), changesPerMin.percentile(50),
                absMovePts.percentile(50), absRetBps.percentile(50),
                absMovePts.percentile(99), absRetBps.percentile(99),
                upPct(), reversalPct());
    }

    @Override
    public String report() {
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
