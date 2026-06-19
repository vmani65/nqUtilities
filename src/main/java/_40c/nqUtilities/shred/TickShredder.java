package _40c.nqUtilities.shred;

import java.util.random.RandomGenerator;

/**
 * Shreds a single 1-minute {@link CandleBar} into a tick-level price path that obeys the bar's hard
 * constraints and mimics live behaviour drawn from a {@link TickModel}.
 *
 * <p><b>Hard constraints (always satisfied):</b> path starts at O, ends at C, its maximum equals H
 * and minimum equals L, it never leaves {@code [L, H]}, every price sits on the 0.05 grid, and the
 * per-tick traded volume sums to the bar's volume.
 *
 * <p><b>Soft behaviour (sampled):</b> the number of distinct changes (per intraday bucket), the
 * per-tick % move (basis points), direction reversals (mean-reversion), intra-minute timing, volume.
 *
 * <p><b>Method:</b> waypoints {@code O → E1 → E2 → C} with {@code {E1,E2}={H,L}} guarantee both
 * extremes are touched; clamping to {@code [L,H]} guarantees containment. The N changes are split
 * across the three segments by distance, and each segment is a Markov-sign random walk (reversal
 * probability from the model, magnitudes from the bps distribution) with a Brownian-bridge correction
 * that lands it exactly on the waypoint. Stateless and thread-safe; the caller supplies the RNG.
 */
public final class TickShredder {

    /** A shredded minute: parallel arrays of tick time, price and traded volume (open tick first). */
    public record ShredEvents(long[] tsMs, double[] ltp, long[] traded) {
        public int size() { return tsMs.length; }
    }

    static final double TICK = 0.05;
    private static final long POLL   = 250;            // ms grid the live feed samples on
    private static final long WINDOW = 60_000 - POLL;  // usable span inside the minute

    /**
     * Calibration: waypoint steering forces short directional runs near the end of each leg, which
     * suppresses the observed reversal rate by a few points. We over-set the Markov reversal
     * probability by this factor so the net reversal rate lands on the live model's value.
     */
    private static final double REVERSAL_BOOST = 1.15;

    private final TickModel model;

    public TickShredder(TickModel model) { this.model = model; }

    public ShredEvents shred(CandleBar bar, RandomGenerator rng) {
        double O = bar.open(), H = bar.high(), L = bar.low(), C = bar.close();

        // --- waypoints: O -> E1 -> E2 -> C, random extreme order ---
        boolean highFirst = rng.nextBoolean();
        double E1 = highFirst ? H : L;
        double E2 = highFirst ? L : H;
        double[] way = { O, E1, E2, C };

        // --- how many distinct changes this minute, with a feasibility floor ---
        int sampled = model.sampleChangeCount(bar.bucket(), rng);
        double[] segLen = { Math.abs(way[1] - way[0]), Math.abs(way[2] - way[1]), Math.abs(way[3] - way[2]) };
        double totalTravel = segLen[0] + segLen[1] + segLen[2];
        double typStepPts  = Math.max(medianBps() * O / 10_000.0, TICK);
        int feasibleMin = (int) Math.ceil(totalTravel / typStepPts);
        int positiveSegs = (segLen[0] > 0 ? 1 : 0) + (segLen[1] > 0 ? 1 : 0) + (segLen[2] > 0 ? 1 : 0);
        int n = Math.max(sampled, Math.max(feasibleMin, positiveSegs));

        // --- allocate the n changes across the three segments by distance ---
        int[] segSteps = allocate(segLen, totalTravel, n);
        n = segSteps[0] + segSteps[1] + segSteps[2];   // exact after allocation

        // --- generate the change prices, segment by segment ---
        double[] prices = new double[n];
        int off = 0;
        double cur = O;
        for (int s = 0; s < 3; s++) {
            int k = segSteps[s];
            if (k == 0) { cur = way[s + 1]; continue; }
            subWalk(cur, way[s + 1], k, L, H, O, prices, off, rng);
            off += k;
            cur = way[s + 1];
        }

        // --- assemble emitted ticks: open tick first, then the n change ticks ---
        int m = n + 1;
        long[]   ts  = new long[m];
        double[] ltp = new double[m];
        ts[0]  = bar.minuteStartMs();
        ltp[0] = snap(O);
        long[] changeTimes = changeTimes(bar.minuteStartMs(), n, rng);
        for (int i = 0; i < n; i++) {
            ts[i + 1]  = changeTimes[i];
            ltp[i + 1] = prices[i];
        }

        long[] traded = splitVolume(bar.volume(), m, rng);
        return new ShredEvents(ts, ltp, traded);
    }

    /** Median |move| in bps, used only to size the feasibility floor (conservative ~1 bps constant). */
    private double medianBps() {
        return 1.0;
    }

    private int[] allocate(double[] segLen, double total, int n) {
        int[] steps = new int[3];
        if (total <= 0) return steps;                 // flat bar — no changes needed
        int positive = 0;
        for (double v : segLen) if (v > 0) positive++;
        int remaining = Math.max(0, n - positive);    // reserve 1 per positive segment
        int assigned = 0;
        for (int s = 0; s < 3; s++) {
            if (segLen[s] <= 0) continue;
            int extra = (int) Math.floor(remaining * (segLen[s] / total));
            steps[s] = 1 + extra;
            assigned += steps[s];
        }
        // hand any rounding leftover to the longest segment
        int leftover = n - assigned;
        if (leftover > 0) {
            int longest = 0;
            for (int s = 1; s < 3; s++) if (segLen[s] > segLen[longest]) longest = s;
            steps[longest] += leftover;
        }
        return steps;
    }

    /**
     * Fills {@code out[off..off+k)} with a price walk from {@code a} to exactly {@code b}. Magnitudes
     * are sampled from the live bps distribution and left untouched (so the per-tick move distribution
     * is preserved). The sign is a Markov mean-reverting choice while the walk has room, but as the
     * remaining steps run low relative to the distance still to cover ("schedule pressure") it is
     * increasingly steered toward {@code b} — oscillation mid-leg, directional runs near its end.
     * Clamped to {@code [lo,hi]} and snapped to the 0.05 grid throughout.
     */
    private void subWalk(double a, double b, int k, double lo, double hi, double refLevel,
                         double[] out, int off, RandomGenerator rng) {
        double cont = 1.0 - Math.min(0.9, model.reversalProb() * REVERSAL_BOOST); // P(keep direction) when unpressured
        double cur  = a;
        int    prev = (b >= a) ? 1 : -1;
        for (int i = 0; i < k; i++) {
            if (i == k - 1) { cur = snap(clamp(b, lo, hi)); out[off + i] = cur; break; }  // pin endpoint

            double mag  = Math.max(model.sampleAbsMoveBps(rng) * refLevel / 10_000.0, TICK);
            double need = b - cur;
            int    toward = need >= 0 ? 1 : -1;
            double pressure = Math.abs(need) / ((k - i) * mag);   // >1 ⇒ can't reach b without committing

            int markov = rng.nextDouble() < cont ? prev : -prev;
            int sign   = pressure > 1.0 ? toward : markov;

            cur = snap(clamp(cur + sign * mag, lo, hi));
            out[off + i] = cur;
            prev = sign;
        }
    }

    /** Change timestamps on the 250ms grid, gaps sampled from the live distribution, fit to the minute. */
    private long[] changeTimes(long minuteStartMs, int n, RandomGenerator rng) {
        long[] ts = new long[n];
        if (n == 0) return ts;
        double[] gaps = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) { gaps[i] = Math.max(POLL, model.sampleGapMs(rng)); sum += gaps[i]; }
        double scale = sum > WINDOW ? WINDOW / sum : 1.0;
        double acc = 0;
        for (int i = 0; i < n; i++) {
            acc += gaps[i] * scale;
            long t = minuteStartMs + Math.round(acc / POLL) * POLL;
            if (i > 0 && t <= ts[i - 1]) t = ts[i - 1] + POLL;        // strictly increasing on the grid
            ts[i] = Math.min(t, minuteStartMs + WINDOW);
        }
        return ts;
    }

    /** Splits {@code volume} across {@code m} ticks with skewed (exponential) weights; sums exactly. */
    private long[] splitVolume(long volume, int m, RandomGenerator rng) {
        long[] traded = new long[m];
        if (m == 0 || volume <= 0) return traded;
        double[] w = new double[m];
        double ws = 0;
        for (int i = 0; i < m; i++) { w[i] = -Math.log(1.0 - rng.nextDouble()); ws += w[i]; }
        long assigned = 0;
        for (int i = 0; i < m - 1; i++) { traded[i] = Math.round(volume * w[i] / ws); assigned += traded[i]; }
        traded[m - 1] = Math.max(0, volume - assigned);
        return traded;
    }

    private static double clamp(double x, double lo, double hi) { return x < lo ? lo : Math.min(x, hi); }
    private static double snap(double x) { return Math.round(x / TICK) * TICK; }
}

/**
 * The behavioural model the shredder samples from, distilled from a live {@link PriceMoveProfile}.
 * It keeps the empirical <em>distributions</em> (as samplable histograms), not just summary stats,
 * so a shredded stream can match the live tails (p90/p99) and shape — not merely the mean.
 */
final class TickModel {

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

    static TickModel from(PriceMoveProfile live) {
        Histogram[] cpm = new Histogram[4];
        for (int b = 0; b < 4; b++) cpm[b] = live.bucketChangesPerMin(b);
        return new TickModel(cpm, live.absRetBpsHist(), live.gapMsHist(),
                live.reversalRate() / 100.0, live.volPerChangeMean());
    }

    /** Number of distinct price changes to emit for a minute in the given bucket (>= 0). */
    int sampleChangeCount(int bucket, RandomGenerator rng) {
        double v = changesPerMinByBucket[bucket].sample(rng);
        return Double.isNaN(v) ? 0 : Math.max(0, (int) Math.round(v));
    }

    /** A |% move| in basis points, drawn from the live distribution. */
    double sampleAbsMoveBps(RandomGenerator rng) {
        double v = absMoveBps.sample(rng);
        return Double.isNaN(v) ? 0 : Math.max(0, v);
    }

    /** A gap (ms) between consecutive changes, drawn from the live distribution. */
    double sampleGapMs(RandomGenerator rng) {
        double v = gapMs.sample(rng);
        return Double.isNaN(v) ? 250 : Math.max(0, v);
    }

    double reversalProb()     { return reversalProb; }
    double volPerChangeMean() { return volPerChangeMean; }
}
