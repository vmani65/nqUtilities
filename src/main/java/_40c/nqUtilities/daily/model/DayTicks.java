package _40c.nqUtilities.daily.model;

import java.time.LocalDate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * All NIFTY-1 ticks for a single {@link TradingDay}, held in parallel primitive arrays
 * (struct-of-arrays / columnar layout). This avoids boxing and per-tick object headers —
 * a day is ~90k ticks and many days are analysed concurrently, so the memory and
 * cache-locality win is meaningful.
 *
 * <p>Ticks are ordered by {@code tsMs} ascending. Two access styles are offered:
 * <ul>
 *   <li>columnar accessors ({@link #tsMs(int)}, {@link #ltp(int)}, …) plus {@link #size()}
 *       for tight numeric loops, and</li>
 *   <li>{@link #tick(int)} / {@link #stream()} returning {@link Tick} records for
 *       readability when throughput is not critical.</li>
 * </ul>
 *
 * <p>Instances are effectively immutable; the backing arrays must not be mutated by callers.
 */
public final class DayTicks {

    private final LocalDate date;
    private final long[]    tsMs;
    private final double[]  ltp;
    private final long[]    vol;
    private final long[]    oi;
    private final int       size;

    /**
     * @param size number of valid leading elements; the arrays may be longer (spare capacity)
     */
    public DayTicks(LocalDate date, long[] tsMs, double[] ltp, long[] vol, long[] oi, int size) {
        this.date = date;
        this.tsMs = tsMs;
        this.ltp  = ltp;
        this.vol  = vol;
        this.oi   = oi;
        this.size = size;
    }

    public LocalDate date()      { return date; }
    public int       size()      { return size; }
    public boolean   isEmpty()   { return size == 0; }

    public long   tsMs(int i) { return tsMs[i]; }
    public double ltp(int i)  { return ltp[i]; }
    public long   vol(int i)  { return vol[i]; }
    public long   oi(int i)   { return oi[i]; }

    /** Object view of the i-th tick. Allocates — avoid in hot loops. */
    public Tick tick(int i) { return new Tick(tsMs[i], ltp[i], vol[i], oi[i]); }

    /** Lazy stream of {@link Tick} records in time order. */
    public Stream<Tick> stream() {
        return IntStream.range(0, size).mapToObj(this::tick);
    }
}
