package _40c.nqUtilities.shred;

import java.util.random.RandomGenerator;

/**
 * Mergeable running statistics over a stream of {@code double} samples — count, mean, sample
 * standard deviation, min and max. No samples are retained (O(1) memory), so it is safe to
 * {@link #merge} across day-threads to build a global view.
 */
final class Stats {

    private long   n;
    private double sum;
    private double sumSq;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;

    void add(double x) {
        n++;
        sum   += x;
        sumSq += x * x;
        if (x < min) min = x;
        if (x > max) max = x;
    }

    void merge(Stats o) {
        if (o.n == 0) return;
        n     += o.n;
        sum   += o.sum;
        sumSq += o.sumSq;
        if (o.min < min) min = o.min;
        if (o.max > max) max = o.max;
    }

    double mean() { return n == 0 ? Double.NaN : sum / n; }
    double max()  { return n == 0 ? Double.NaN : max; }
}

/**
 * Fixed-bin histogram with running min/max/mean and approximate percentiles. Samples below
 * {@code lo} or at/above {@code hi} land in underflow/overflow buckets so nothing is lost. Two
 * histograms with identical geometry can be {@link #merge merged}, which is how per-day profiles
 * fold into one global distribution. {@link #sample} draws from the empirical shape (tails included),
 * which is what the shredder needs to match p90/p99 — not just the mean.
 */
final class Histogram {

    private final double lo;
    private final double binWidth;
    private final long[] counts;
    private long underflow;
    private long overflow;

    // running scalars over ALL samples (including under/overflow)
    private long   n;
    private double sum;
    private double sumSq;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;

    Histogram(double lo, double hi, double binWidth) {
        if (hi <= lo || binWidth <= 0) throw new IllegalArgumentException("bad histogram geometry");
        this.lo       = lo;
        this.binWidth = binWidth;
        this.counts   = new long[(int) Math.ceil((hi - lo) / binWidth)];
    }

    void add(double x) {
        n++;
        sum   += x;
        sumSq += x * x;
        if (x < min) min = x;
        if (x > max) max = x;

        if (x < lo) { underflow++; return; }
        int idx = (int) ((x - lo) / binWidth);
        if (idx >= counts.length) overflow++;
        else counts[idx]++;
    }

    void merge(Histogram o) {
        if (o.counts.length != counts.length || o.lo != lo || o.binWidth != binWidth)
            throw new IllegalArgumentException("histogram geometry mismatch");
        for (int i = 0; i < counts.length; i++) counts[i] += o.counts[i];
        underflow += o.underflow;
        overflow  += o.overflow;
        n     += o.n;
        sum   += o.sum;
        sumSq += o.sumSq;
        if (o.min < min) min = o.min;
        if (o.max > max) max = o.max;
    }

    double mean() { return n == 0 ? Double.NaN : sum / n; }
    double max()  { return n == 0 ? Double.NaN : max; }

    double stdDev() {
        if (n < 2) return Double.NaN;
        double var = (sumSq - (sum * sum) / n) / (n - 1);
        return var <= 0 ? 0 : Math.sqrt(var);
    }

    /** Approximate p-th percentile (0–100) using bin centres. Underflow→{@code lo}, overflow→top edge. */
    double percentile(double p) {
        if (n == 0) return Double.NaN;
        long target = (long) Math.ceil(p / 100.0 * n);
        if (target < 1) target = 1;
        long cum = underflow;
        if (cum >= target) return lo;
        for (int i = 0; i < counts.length; i++) {
            cum += counts[i];
            if (cum >= target) return lo + (i + 0.5) * binWidth;
        }
        return lo + counts.length * binWidth; // overflow region
    }

    /**
     * Draws a sample by inverse-CDF over the bin counts, with uniform jitter inside the chosen bin —
     * reproducing the empirical shape including the tails. Underflow/overflow samples collapse to the
     * bottom/top edge.
     */
    double sample(RandomGenerator rng) {
        if (n == 0) return Double.NaN;
        long target = (long) (rng.nextDouble() * n);   // 0 .. n-1
        long cum = underflow;
        if (target < cum) return lo;
        for (int i = 0; i < counts.length; i++) {
            cum += counts[i];
            if (target < cum) return lo + (i + rng.nextDouble()) * binWidth;
        }
        return lo + counts.length * binWidth;
    }
}
