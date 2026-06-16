package _40c.nqUtilities.daily.analysis;

import java.util.random.RandomGenerator;

/**
 * Fixed-bin histogram with running min/max/mean and approximate percentiles. Samples below
 * {@code lo} or at/above {@code hi} are tallied in underflow/overflow buckets so nothing is
 * lost. Two histograms with identical geometry can be {@link #merge merged}, which is how
 * per-day profiles fold into a single global distribution for the whole capture window.
 */
public final class Histogram {

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

    public Histogram(double lo, double hi, double binWidth) {
        if (hi <= lo || binWidth <= 0) throw new IllegalArgumentException("bad histogram geometry");
        this.lo       = lo;
        this.binWidth = binWidth;
        this.counts   = new long[(int) Math.ceil((hi - lo) / binWidth)];
    }

    public void add(double x) {
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

    public void merge(Histogram o) {
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

    public long   count() { return n; }
    public double mean()  { return n == 0 ? Double.NaN : sum / n; }
    public double min()   { return n == 0 ? Double.NaN : min; }
    public double max()   { return n == 0 ? Double.NaN : max; }

    public double stdDev() {
        if (n < 2) return Double.NaN;
        double var = (sumSq - (sum * sum) / n) / (n - 1);
        return var <= 0 ? 0 : Math.sqrt(var);
    }

    /**
     * Approximate p-th percentile (0–100) using bin centres. Underflow maps to {@code lo},
     * overflow to the top edge.
     */
    public double percentile(double p) {
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
     * Draws a sample from this histogram by inverse-CDF over the bin counts, with uniform jitter
     * inside the chosen bin. Reproduces the empirical shape — including the tails — which is what
     * the shredder needs to match p90/p99 behaviour, not just the mean. Underflow/overflow samples
     * collapse to the bottom/top edge.
     */
    public double sample(RandomGenerator rng) {
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
