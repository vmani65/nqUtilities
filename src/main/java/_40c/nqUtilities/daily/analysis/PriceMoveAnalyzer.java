package _40c.nqUtilities.daily.analysis;

import _40c.nqUtilities.daily.model.DayTicks;
import _40c.nqUtilities.daily.model.TradingDay;

import java.util.HashSet;

/**
 * Characterises NIFTY-1 tick-to-tick behaviour for one trading day, over the regular session
 * only (09:15:00–15:30:00 IST). It walks the day's ticks in time order, keeps only
 * <em>distinct</em> LTP changes (poll repeats are dropped), and measures each move both in
 * absolute terms (points, 0.05-tick units) and — the part that transfers across price levels —
 * as a % return in basis points relative to the from-price.
 *
 * <p>Stateless: a single instance is shared across all per-day threads.
 */
public final class PriceMoveAnalyzer implements DayAnalyzer<PriceMoveProfile> {

    /** Regular session, seconds-of-day IST. */
    public static final int SESSION_START_SEC = 9 * 3600 + 15 * 60;  // 09:15:00
    public static final int SESSION_END_SEC   = 15 * 3600 + 30 * 60; // 15:30:00 (exclusive)

    private static final double TICK = 0.05;
    private static final double EPS  = 1e-9;

    @Override
    public PriceMoveProfile analyze(DayTicks day) {
        var p = new PriceMoveProfile(day.date().toString(), 1);

        int n = day.size();
        int[]     minuteChanges  = new int[1440];
        boolean[] minuteActive   = new boolean[1440];
        long[]    minuteLastVol   = new long[1440];

        double lastPrice      = Double.NaN;
        long   lastChangeMs    = 0;
        long   lastChangeVol   = 0;
        long   sessionOpenVol  = 0;
        int    prevSign         = 0;

        // per-minute oscillation/revisit tracking (a -> b -> c -> b -> d -> c -> a ...)
        int          osMinute   = -1;
        var          osLevels   = new HashSet<Long>();   // distinct 0.05 levels seen this minute
        double       osFirst    = 0;                     // first price of the minute
        double       osLast      = 0;                    // last price of the minute
        double       osPrev      = 0;                    // previous in-minute price (for gross travel)
        double       osGross     = 0;                    // sum |Δ| within the minute
        int          osChanges   = 0;                    // in-minute distinct changes
        int          osRevisits  = 0;                    // changes landing on an already-seen level

        for (int i = 0; i < n; i++) {
            long t   = day.tsMs(i);
            int  sec = (int) (((t + TradingDay.IST_OFFSET_MS) % TradingDay.DAY_MS) / 1000);
            if (sec < SESSION_START_SEC || sec >= SESSION_END_SEC) continue;

            int    minute = sec / 60;
            double price  = day.ltp(i);
            long   vol    = day.vol(i);

            if (minute != osMinute) {                    // minute boundary — flush the one just ended
                flushOscillation(p, osChanges, osRevisits, osLevels.size(), osFirst, osLast, osGross);
                osMinute = minute;
                osLevels.clear();
                osLevels.add(level(price));
                osFirst = osLast = osPrev = price;
                osGross = 0; osChanges = 0; osRevisits = 0;
            }

            minuteActive[minute] = true;
            minuteLastVol[minute] = vol;
            p.sessionRows++;

            // session OHLC context
            if (Double.isNaN(p.sessOpen)) { p.sessOpen = price; p.sessHigh = price; p.sessLow = price; }
            if (price > p.sessHigh) p.sessHigh = price;
            if (price < p.sessLow)  p.sessLow  = price;
            p.sessClose = price;

            if (Double.isNaN(lastPrice)) {           // first session tick — baseline only
                lastPrice = price;
                lastChangeMs = t;
                lastChangeVol = vol;
                sessionOpenVol = vol;
                continue;
            }
            if (price == lastPrice) continue;          // poll repeat — not a distinct change

            double delta  = price - lastPrice;
            double absd   = Math.abs(delta);
            double retBps = (delta / lastPrice) * 10_000.0;   // % return in basis points

            p.absMovePts.add(absd);
            p.signedRetBps.add(retBps);
            p.absRetBps.add(Math.abs(retBps));
            p.moveTicks.add(Math.round(absd / TICK));
            p.gapMs.add(t - lastChangeMs);

            long volDelta = vol - lastChangeVol;
            if (volDelta >= 0) p.volPerChange.add(volDelta);

            int sign = delta > 0 ? 1 : -1;
            if (sign > 0) p.upChanges++; else p.downChanges++;
            if (prevSign != 0 && sign != prevSign) p.reversals++;
            prevSign = sign;

            int b = bucketOf(sec);
            p.bucketAbsRetBps[b].add(Math.abs(retBps));

            if (absd < p.smallestMove) p.smallestMove = absd;
            double ratio = absd / TICK;
            if (Math.abs(ratio - Math.rint(ratio)) < EPS) p.movesMultipleOf005++;

            minuteChanges[minute]++;
            p.totalChanges++;

            // oscillation: this changed price is a level visited this minute; is it a revisit?
            long lv = level(price);
            if (!osLevels.add(lv)) osRevisits++;   // add() returns false if already present
            osGross += Math.abs(price - osPrev);
            osPrev = price;
            osLast = price;
            osChanges++;

            lastPrice = price;
            lastChangeMs = t;
            lastChangeVol = vol;
        }
        flushOscillation(p, osChanges, osRevisits, osLevels.size(), osFirst, osLast, osGross);  // last minute

        // per-minute aggregates: changes/min distribution, intraday buckets, volume/min
        long prevMinLastVol = -1;
        for (int m = 0; m < 1440; m++) {
            if (!minuteActive[m]) continue;
            p.sessionMinutes++;
            p.changesPerMin.add(minuteChanges[m]);

            int b = bucketOf(m * 60);
            p.bucketActiveMin[b]++;
            p.bucketChanges[b] += minuteChanges[m];
            p.bucketChangesPerMin[b].add(minuteChanges[m]);

            long base   = prevMinLastVol < 0 ? sessionOpenVol : prevMinLastVol;
            long traded = minuteLastVol[m] - base;
            if (traded >= 0) p.volPerMin.add(traded);
            prevMinLastVol = minuteLastVol[m];
        }

        if (p.totalChanges == 0) p.smallestMove = Double.NaN;
        return p;
    }

    /** 0.05-grid level key for a price, so revisits to the same level are detectable. */
    private static long level(double price) { return Math.round(price / TICK); }

    /** Records one finished minute's oscillation metrics (no-op for empty/flat minutes). */
    private static void flushOscillation(PriceMoveProfile p, int changes, int revisits,
                                         int distinctLevels, double first, double last, double gross) {
        if (changes == 0) return;
        p.distinctLevelsPerMin.add(distinctLevels);
        p.revisitRatio.add((double) revisits / changes);
        if (gross > 0) p.pathEfficiency.add(Math.abs(last - first) / gross);
    }

    private static int bucketOf(int sec) {
        if (sec < 9 * 3600 + 45 * 60) return PriceMoveProfile.Bucket.OPEN.ordinal();    // <09:45
        if (sec < 12 * 3600)          return PriceMoveProfile.Bucket.MORNING.ordinal();  // <12:00
        if (sec < 14 * 3600)          return PriceMoveProfile.Bucket.MIDDAY.ordinal();   // <14:00
        return PriceMoveProfile.Bucket.CLOSE.ordinal();
    }
}
