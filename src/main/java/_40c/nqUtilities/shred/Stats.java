package _40c.nqUtilities.shred;

import java.util.Map;
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

    // ---------------------------------------------------------------- persistence

    /** Writes this {@code Stats}' raw fields into {@code m} under {@code key.*}. */
    void save(Map<String, String> m, String key) {
        m.put(key + ".n",     Long.toString(n));
        m.put(key + ".sum",   Double.toString(sum));
        m.put(key + ".sumSq", Double.toString(sumSq));
        m.put(key + ".min",   Double.toString(min));
        m.put(key + ".max",   Double.toString(max));
    }

    /** Restores every field from {@code m} (exact round-trip of {@link #save}). */
    void restore(Map<String, String> m, String key) {
        n     = Long.parseLong(m.get(key + ".n"));
        sum   = Double.parseDouble(m.get(key + ".sum"));
        sumSq = Double.parseDouble(m.get(key + ".sumSq"));
        min   = Double.parseDouble(m.get(key + ".min"));
        max   = Double.parseDouble(m.get(key + ".max"));
    }
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

    // ---------------------------------------------------------------- persistence

    /**
     * Writes this histogram's geometry, every running scalar, and the full bin array into {@code m}
     * under {@code key.*}. The geometry ({@code lo}, {@code binWidth}, {@code len}) is recorded so a
     * later {@link #restore} can verify it matches the histogram being loaded into.
     */
    void save(Map<String, String> m, String key) {
        m.put(key + ".lo",        Double.toString(lo));
        m.put(key + ".binWidth",  Double.toString(binWidth));
        m.put(key + ".len",       Integer.toString(counts.length));
        m.put(key + ".underflow", Long.toString(underflow));
        m.put(key + ".overflow",  Long.toString(overflow));
        m.put(key + ".n",         Long.toString(n));
        m.put(key + ".sum",       Double.toString(sum));
        m.put(key + ".sumSq",     Double.toString(sumSq));
        m.put(key + ".min",       Double.toString(min));
        m.put(key + ".max",       Double.toString(max));
        var sb = new StringBuilder(counts.length * 3);
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(counts[i]);
        }
        m.put(key + ".counts", sb.toString());
    }

    /**
     * Restores every scalar and bin from {@code m} in place — a bit-for-bit round-trip of
     * {@link #save}, so percentiles and samples are unchanged. The persisted geometry must match
     * this histogram's (same {@code lo}/{@code binWidth}/length), else the format/geometry has
     * drifted and we fail loudly rather than silently corrupt the distribution.
     */
    void restore(Map<String, String> m, String key) {
        double rLo  = Double.parseDouble(m.get(key + ".lo"));
        double rBw  = Double.parseDouble(m.get(key + ".binWidth"));
        int    rLen = Integer.parseInt(m.get(key + ".len"));
        if (rLo != lo || rBw != binWidth || rLen != counts.length)
            throw new IllegalStateException(("Histogram geometry mismatch for '%s': file (%s,%s,%d) "
                    + "vs model (%s,%s,%d)").formatted(key, rLo, rBw, rLen, lo, binWidth, counts.length));
        underflow = Long.parseLong(m.get(key + ".underflow"));
        overflow  = Long.parseLong(m.get(key + ".overflow"));
        n         = Long.parseLong(m.get(key + ".n"));
        sum       = Double.parseDouble(m.get(key + ".sum"));
        sumSq     = Double.parseDouble(m.get(key + ".sumSq"));
        min       = Double.parseDouble(m.get(key + ".min"));
        max       = Double.parseDouble(m.get(key + ".max"));
        String csv = m.get(key + ".counts");
        String[] parts = csv.isEmpty() ? new String[0] : csv.split(",");
        if (parts.length != counts.length)
            throw new IllegalStateException("Histogram counts length mismatch for '" + key + "'");
        for (int i = 0; i < parts.length; i++) counts[i] = Long.parseLong(parts[i]);
    }
}
