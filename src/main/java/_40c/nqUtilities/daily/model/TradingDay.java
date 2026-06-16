package _40c.nqUtilities.daily.model;

import java.time.LocalDate;

/**
 * One trading day's worth of NIFTY-1 data, identified by its IST calendar date and the
 * half-open UTC epoch-millis window {@code [startMsInclusive, endMsExclusive)} that
 * bounds it. The window is pure integer arithmetic over the IST day so it maps cleanly
 * onto the {@code idx_sym_ts} index in {@code nifty_ticks.db}.
 *
 * @param date             the IST calendar date
 * @param startMsInclusive UTC epoch-millis of 00:00 IST on {@code date} (inclusive)
 * @param endMsExclusive   UTC epoch-millis of 00:00 IST on the next day (exclusive)
 * @param tickCount        NIFTY-1 ticks observed in the window at discovery time; a
 *                         sizing hint only — the live current day may have grown since.
 */
public record TradingDay(LocalDate date, long startMsInclusive, long endMsExclusive, int tickCount) {

    /** Milliseconds offset of IST (UTC+05:30). */
    public static final long IST_OFFSET_MS = 19_800_000L;

    /** Milliseconds in a calendar day. */
    public static final long DAY_MS = 86_400_000L;

    /**
     * Builds a {@link TradingDay} from an IST epoch-day number
     * ({@code (ts_ms + IST_OFFSET_MS) / DAY_MS}).
     */
    public static TradingDay fromIstEpochDay(long istEpochDay, int tickCount) {
        long start = istEpochDay * DAY_MS - IST_OFFSET_MS;
        return new TradingDay(LocalDate.ofEpochDay(istEpochDay), start, start + DAY_MS, tickCount);
    }
}
