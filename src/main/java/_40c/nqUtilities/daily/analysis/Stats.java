package _40c.nqUtilities.daily.analysis;

/**
 * Mergeable running statistics over a stream of {@code double} samples — count, mean,
 * sample standard deviation, min and max. No samples are retained, so it is O(1) memory
 * and safe to {@link #merge} across day-threads to build a global view.
 */
public final class Stats {

    private long   n;
    private double sum;
    private double sumSq;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;

    public void add(double x) {
        n++;
        sum   += x;
        sumSq += x * x;
        if (x < min) min = x;
        if (x > max) max = x;
    }

    public void merge(Stats o) {
        if (o.n == 0) return;
        n     += o.n;
        sum   += o.sum;
        sumSq += o.sumSq;
        if (o.min < min) min = o.min;
        if (o.max > max) max = o.max;
    }

    public long   count() { return n; }
    public double sum()   { return sum; }
    public double mean()  { return n == 0 ? Double.NaN : sum / n; }
    public double min()   { return n == 0 ? Double.NaN : min; }
    public double max()   { return n == 0 ? Double.NaN : max; }

    public double stdDev() {
        if (n < 2) return Double.NaN;
        double var = (sumSq - (sum * sum) / n) / (n - 1);
        return var <= 0 ? 0 : Math.sqrt(var);
    }
}
