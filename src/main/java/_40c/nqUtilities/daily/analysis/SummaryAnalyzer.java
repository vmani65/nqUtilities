package _40c.nqUtilities.daily.analysis;

import _40c.nqUtilities.daily.model.DayTicks;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Placeholder analyzer that proves out the framework end-to-end: for each day it reports
 * tick count, the session time window (IST), open/close LTP, intraday high/low and the
 * net point change. Replace or sit alongside this with the real analysis once defined.
 */
public final class SummaryAnalyzer implements DayAnalyzer<SummaryAnalyzer.Summary> {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(IST);

    /**
     * @param date      IST trading date
     * @param tickCount number of NIFTY-1 ticks seen
     * @param firstMs   epoch-millis of the first tick (0 if empty)
     * @param lastMs    epoch-millis of the last tick (0 if empty)
     * @param openLtp   LTP of the first tick
     * @param closeLtp  LTP of the last tick
     * @param highLtp   max LTP across the day
     * @param lowLtp    min LTP across the day
     */
    public record Summary(LocalDate date, int tickCount, long firstMs, long lastMs,
                          double openLtp, double closeLtp, double highLtp, double lowLtp)
            implements AnalysisResult {

        public double netChange() { return closeLtp - openLtp; }

        @Override
        public String summary() {
            if (tickCount == 0) {
                return "%s  no ticks".formatted(date);
            }
            return "%s  ticks=%-7d  %s-%s IST  O=%.2f H=%.2f L=%.2f C=%.2f  chg=%+.2f"
                    .formatted(date, tickCount, HMS.format(Instant.ofEpochMilli(firstMs)),
                            HMS.format(Instant.ofEpochMilli(lastMs)),
                            openLtp, highLtp, lowLtp, closeLtp, netChange());
        }
    }

    @Override
    public Summary analyze(DayTicks day) {
        int n = day.size();
        if (n == 0) {
            return new Summary(day.date(), 0, 0, 0, 0, 0, 0, 0);
        }
        double high = Double.NEGATIVE_INFINITY;
        double low  = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double p = day.ltp(i);
            if (p > high) high = p;
            if (p < low)  low = p;
        }
        return new Summary(day.date(), n, day.tsMs(0), day.tsMs(n - 1),
                day.ltp(0), day.ltp(n - 1), high, low);
    }
}
