package _40c.nqUtilities.daily.shred;

import _40c.nqUtilities.daily.analysis.PriceMoveAnalyzer;
import _40c.nqUtilities.daily.model.DayTicks;
import _40c.nqUtilities.daily.model.TradingDay;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates a day's NIFTY-1 ticks into 1-minute OHLCV bars over the regular session.
 *
 * <p>This is the inverse of shredding: it manufactures realistic historical-style candles from
 * the live tick stream, so the shredder can be tested closed-loop (real bars → shred → must
 * reproduce the real tick statistics). A CSV-backed source can replace this later without
 * touching the shredder.
 */
public final class BarBuilder {

    private BarBuilder() {}

    public static List<CandleBar> fromDay(DayTicks day) {
        var bars = new ArrayList<CandleBar>();

        int    curMinute = -1;
        double o = 0, h = 0, l = 0, c = 0;
        long   minuteStartMs = 0;
        long   lastVolInMin = 0;
        long   prevMinuteEndVol = -1;

        int n = day.size();
        for (int i = 0; i < n; i++) {
            long t   = day.tsMs(i);
            int  sec = (int) (((t + TradingDay.IST_OFFSET_MS) % TradingDay.DAY_MS) / 1000);
            if (sec < PriceMoveAnalyzer.SESSION_START_SEC || sec >= PriceMoveAnalyzer.SESSION_END_SEC) continue;

            int    minute = sec / 60;
            double price  = day.ltp(i);
            long   vol    = day.vol(i);

            if (minute != curMinute) {
                if (curMinute != -1) {
                    bars.add(closeBar(day, curMinute, minuteStartMs, o, h, l, c, prevMinuteEndVol, lastVolInMin));
                    prevMinuteEndVol = lastVolInMin;
                }
                curMinute = minute;
                minuteStartMs = (t / 60_000) * 60_000;  // start of this clock minute (epoch grid)
                o = h = l = c = price;
            }
            if (price > h) h = price;
            if (price < l) l = price;
            c = price;
            lastVolInMin = vol;
        }
        if (curMinute != -1) {
            bars.add(closeBar(day, curMinute, minuteStartMs, o, h, l, c, prevMinuteEndVol, lastVolInMin));
        }
        return bars;
    }

    private static CandleBar closeBar(DayTicks day, int minute, long minuteStartMs,
                                      double o, double h, double l, double c,
                                      long prevMinuteEndVol, long lastVolInMin) {
        long traded = prevMinuteEndVol < 0 ? 0 : Math.max(0, lastVolInMin - prevMinuteEndVol);
        return new CandleBar(day.date(), minute, minuteStartMs, o, h, l, c, traded);
    }
}
