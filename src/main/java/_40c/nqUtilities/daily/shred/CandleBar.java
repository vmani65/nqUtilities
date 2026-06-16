package _40c.nqUtilities.daily.shred;

import _40c.nqUtilities.daily.analysis.PriceMoveAnalyzer;

import java.time.LocalDate;

/**
 * One 1-minute OHLCV bar — the unit of historical data the shredder consumes.
 *
 * @param date         IST trading date
 * @param minuteOfDay  minute-of-day IST (e.g. 09:15 → 555)
 * @param minuteStartMs UTC epoch-millis of the start of this clock minute
 * @param open/high/low/close prices
 * @param volume       traded volume during the minute
 */
public record CandleBar(LocalDate date, int minuteOfDay, long minuteStartMs,
                        double open, double high, double low, double close, long volume) {

    /** Intraday bucket (OPEN/MORNING/MIDDAY/CLOSE) this bar falls in. */
    public int bucket() {
        int sec = minuteOfDay * 60;
        if (sec < 9 * 3600 + 45 * 60) return 0;   // OPEN   <09:45
        if (sec < 12 * 3600)          return 1;   // MORNING<12:00
        if (sec < 14 * 3600)          return 2;   // MIDDAY <14:00
        return 3;                                 // CLOSE
    }

    public double range() { return high - low; }

    /** True if this minute is inside the regular session. */
    public boolean inSession() {
        int sec = minuteOfDay * 60;
        return sec >= PriceMoveAnalyzer.SESSION_START_SEC && sec < PriceMoveAnalyzer.SESSION_END_SEC;
    }
}
