package _40c.nqUtilities.daily.shred;

import _40c.nqUtilities.daily.analysis.Histogram;
import _40c.nqUtilities.daily.analysis.PriceMoveProfile;

import java.util.random.RandomGenerator;

/**
 * The behavioural model the shredder samples from, distilled from a live {@link PriceMoveProfile}.
 *
 * <p>It keeps the empirical <em>distributions</em> (as samplable histograms), not just summary
 * stats, so a shredded stream can match the live tails (p90/p99) and shape — not merely the mean:
 * <ul>
 *   <li>changes-per-minute, per intraday bucket — how many distinct ticks to emit in a minute;</li>
 *   <li>|% move| in basis points — the per-tick step size, level-independent;</li>
 *   <li>gap between changes (ms) — intra-minute timing;</li>
 *   <li>reversal probability — how often direction flips (mean-reversion);</li>
 *   <li>mean volume per change — for splitting a bar's volume across its ticks.</li>
 * </ul>
 */
public final class TickModel {

    public static final double TICK = 0.05;

    private final Histogram[] changesPerMinByBucket;   // [4]
    private final Histogram   absMoveBps;
    private final Histogram   gapMs;
    private final double      reversalProb;            // 0..1
    private final double      volPerChangeMean;

    private TickModel(Histogram[] cpm, Histogram absMoveBps, Histogram gapMs,
                      double reversalProb, double volPerChangeMean) {
        this.changesPerMinByBucket = cpm;
        this.absMoveBps            = absMoveBps;
        this.gapMs                 = gapMs;
        this.reversalProb          = reversalProb;
        this.volPerChangeMean      = volPerChangeMean;
    }

    public static TickModel from(PriceMoveProfile live) {
        Histogram[] cpm = new Histogram[4];
        for (int b = 0; b < 4; b++) cpm[b] = live.bucketChangesPerMin(b);
        return new TickModel(cpm, live.absRetBpsHist(), live.gapMsHist(),
                live.reversalRate() / 100.0, live.volPerChangeMean());
    }

    /** Number of distinct price changes to emit for a minute in the given bucket (>= 0). */
    public int sampleChangeCount(int bucket, RandomGenerator rng) {
        double v = changesPerMinByBucket[bucket].sample(rng);
        return Double.isNaN(v) ? 0 : Math.max(0, (int) Math.round(v));
    }

    /** A |% move| in basis points, drawn from the live distribution. */
    public double sampleAbsMoveBps(RandomGenerator rng) {
        double v = absMoveBps.sample(rng);
        return Double.isNaN(v) ? 0 : Math.max(0, v);
    }

    /** A gap (ms) between consecutive changes, drawn from the live distribution. */
    public double sampleGapMs(RandomGenerator rng) {
        double v = gapMs.sample(rng);
        return Double.isNaN(v) ? 250 : Math.max(0, v);
    }

    public double reversalProb()     { return reversalProb; }
    public double volPerChangeMean() { return volPerChangeMean; }
}
