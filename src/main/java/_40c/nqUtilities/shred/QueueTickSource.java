package _40c.nqUtilities.shred;

import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.function.Consumer;

/**
 * Reads the live <strong>nqTicker Chronicle Queue</strong> — the full tick history written by the
 * AccelPix gateway — as the {@code learn} source. Each queue entry is a self-describing document
 * {@code { ts, sym, ltp, op, hp, lp, cp, bid, ask, vol, oi }}:
 *
 * <ul>
 *   <li>{@code ts} is the feed timestamp in <em>nanoseconds since UTC midnight</em> (intraday,
 *       not epoch); combined with the cycle's UTC midnight it yields the true epoch-ms, so the
 *       analyzer's intraday extraction {@code (tsMs+IST_OFFSET)%DAY} is exact for any calendar date;</li>
 *   <li>{@code sym} is the instrument; we keep only the configured symbol (e.g. {@code NIFTY-1});</li>
 *   <li>{@code vol}/{@code oi} are the cumulative session volume / open interest, matching what the
 *       SQLite path feeds {@link PriceMoveAnalyzer}.</li>
 * </ul>
 *
 * <p>The queue rolls one cycle per trading day, so {@link ExcerptTailer#cycle()} groups ticks into
 * days. Ticks are streamed one {@link DayTicks} at a time to a consumer (the whole history is far
 * too large to hold at once). A concurrent read-only tailer is the Chronicle-blessed way to read a
 * queue that a writer is appending to, exactly as nqTicker's own consumers do.
 *
 * <p>Requires the Chronicle JVM flags (see README): {@code --add-opens} for
 * {@code java.base/{java.lang.reflect,java.nio,sun.nio.ch}}, {@code --add-exports
 * java.base/jdk.internal.ref=ALL-UNNAMED}, {@code --sun-misc-unsafe-memory-access=allow},
 * {@code --enable-native-access=ALL-UNNAMED}.
 */
final class QueueTickSource {

    private static final Logger log = LoggerFactory.getLogger(QueueTickSource.class);
    private static final long NANOS_PER_MS = 1_000_000L;

    private final String dir;
    private final String symbol;
    private final int    minDistinctPrices;
    private final int    sessionEndSec;

    /**
     * @param minDistinctPrices skip any day whose (capped) session has fewer than this many distinct
     *                          ltps — a stale/heartbeat day (e.g. a frozen non-trading day) rather
     *                          than genuine trading. Use {@code <= 1} to keep every day.
     * @param sessionEndSec     drop ticks at/after this IST second-of-day, so learning can be bounded
     *                          to the clean pre-freeze window (the AccelPix feed goes stale ~13:00 IST).
     *                          Use {@link PriceMoveAnalyzer#SESSION_END_SEC} for the full session.
     */
    QueueTickSource(String dir, String symbol, int minDistinctPrices, int sessionEndSec) {
        this.dir               = dir;
        this.symbol            = symbol;
        this.minDistinctPrices = minDistinctPrices;
        this.sessionEndSec     = sessionEndSec;
    }

    /**
     * Streams one {@link DayTicks} per trading day for the configured symbol to {@code dayConsumer},
     * oldest first. {@code fromIst}/{@code toIst} (each {@code yyyy-MM-dd}, blank = unbounded) bound the
     * IST calendar dates kept. Days that fail the stale check are logged and skipped. Returns the number
     * of days emitted.
     */
    int forEachDay(String fromIst, String toIst, Consumer<DayTicks> dayConsumer) {
        LocalDate from = (fromIst == null || fromIst.isBlank()) ? null : LocalDate.parse(fromIst.trim());
        LocalDate to   = (toIst   == null || toIst.isBlank())   ? null : LocalDate.parse(toIst.trim());

        int  daysEmitted = 0, daysSkipped = 0;
        long kept = 0, scanned = 0;
        try (ChronicleQueue queue = ChronicleQueue.singleBuilder(dir).build()) {
            ExcerptTailer tailer = queue.createTailer().toStart();

            var day = new DayBuffer();
            int  curCycle = Integer.MIN_VALUE;
            LocalDate curDate = null;
            boolean curKept = true;

            while (true) {
                try (DocumentContext dc = tailer.readingDocument()) {
                    if (!dc.isPresent()) break;                // reached the end
                    if (!dc.isData()) continue;                // skip metadata
                    int cycle = tailer.cycle();

                    if (cycle != curCycle) {                   // day boundary — flush the finished day
                        if (curCycle != Integer.MIN_VALUE && curKept) {
                            if (flush(curDate, day, dayConsumer)) daysEmitted++; else daysSkipped++;
                        }
                        day.clear();
                        curCycle = cycle;
                        curDate  = LocalDate.ofEpochDay(cycle);          // daily roll ⇒ cycle == epoch day
                        curKept  = (from == null || !curDate.isBefore(from))
                                && (to   == null || !curDate.isAfter(to));
                    }

                    scanned++;
                    Wire w = dc.wire();
                    long   ts  = w.read("ts").int64();
                    String sym = w.read("sym").text();
                    if (!symbol.equals(sym) || !curKept) continue;       // wrong instrument / out of range

                    ValueIn in = w.read("ltp"); double ltp = in.float64();
                    w.read("op").float64(); w.read("hp").float64();
                    w.read("lp").float64(); w.read("cp").float64();
                    w.read("bid").float64(); w.read("ask").float64();
                    long vol = w.read("vol").int64();
                    long oi  = w.read("oi").int64();

                    // ts is nanoseconds since UTC midnight; cycle*DAY is that day's UTC midnight, so
                    // their sum is the true UTC epoch-ms. The analyzer derives IST time-of-day as
                    // (tsMs + IST_OFFSET) % DAY — exact regardless of the calendar date.
                    long tsMs   = (long) cycle * TradingDay.DAY_MS + ts / NANOS_PER_MS;
                    int  istSec = (int) (((tsMs + TradingDay.IST_OFFSET_MS) % TradingDay.DAY_MS) / 1000);
                    if (istSec >= sessionEndSec) continue;               // past the clean/learn window
                    day.add(tsMs, ltp, vol, oi);
                    kept++;
                }
            }
            if (curKept) {                                       // last day
                if (flush(curDate, day, dayConsumer)) daysEmitted++; else daysSkipped++;
            }
        }
        log.info("Queue scan complete: {} entries scanned, {} {} ticks kept; {} days learned, {} skipped (stale).",
                String.format("%,d", scanned), String.format("%,d", kept), symbol, daysEmitted, daysSkipped);
        return daysEmitted;
    }

    /**
     * Logs one day's coverage and emits it unless it is stale (no session ticks, or fewer than
     * {@code minDistinctPrices} distinct session ltps — a frozen/heartbeat day, not real trading).
     *
     * @return {@code true} if the day was emitted, {@code false} if skipped
     */
    private boolean flush(LocalDate date, DayBuffer day, Consumer<DayTicks> dayConsumer) {
        if (day.n == 0) return false;
        int minSec = Integer.MAX_VALUE, maxSec = Integer.MIN_VALUE, sessionRows = 0;
        var distinctLtp = new java.util.HashSet<Double>();
        for (int i = 0; i < day.n; i++) {
            int sec = (int) (((day.tsMs[i] + TradingDay.IST_OFFSET_MS) % TradingDay.DAY_MS) / 1000);
            if (sec < minSec) minSec = sec;
            if (sec > maxSec) maxSec = sec;
            if (sec >= PriceMoveAnalyzer.SESSION_START_SEC && sec < PriceMoveAnalyzer.SESSION_END_SEC) {
                sessionRows++;
                distinctLtp.add(day.ltp[i]);
            }
        }
        boolean stale = sessionRows == 0 || distinctLtp.size() < minDistinctPrices;
        log.info("  day={} kept={} istSpan={}..{} sessionTicks={} distinctSessionLtp={}{}",
                date, day.n, hhmm(minSec), hhmm(maxSec), sessionRows, distinctLtp.size(),
                stale ? "   [SKIPPED — stale]" : "");
        if (stale) return false;
        dayConsumer.accept(day.toDayTicks(date));
        return true;
    }

    private static String hhmm(int sec) {
        if (sec < 0) return "--:--";
        return String.format("%02d:%02d", sec / 3600, (sec % 3600) / 60);
    }

    /** Growable struct-of-arrays buffer for one day's ticks. */
    private static final class DayBuffer {
        long[]   tsMs = new long[1 << 16];
        double[] ltp  = new double[1 << 16];
        long[]   vol  = new long[1 << 16];
        long[]   oi   = new long[1 << 16];
        int      n;

        void add(long t, double p, long v, long o) {
            if (n == tsMs.length) {
                int grown = n + (n >> 1);
                tsMs = Arrays.copyOf(tsMs, grown);
                ltp  = Arrays.copyOf(ltp,  grown);
                vol  = Arrays.copyOf(vol,  grown);
                oi   = Arrays.copyOf(oi,   grown);
            }
            tsMs[n] = t; ltp[n] = p; vol[n] = v; oi[n] = o; n++;
        }

        void clear() { n = 0; }

        DayTicks toDayTicks(LocalDate date) {
            return new DayTicks(date, tsMs, ltp, vol, oi, n);
        }
    }
}
