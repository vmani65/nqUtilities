package _40c.nqUtilities.shred;

import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The plain data the pipeline moves around: a {@link TradingDay} (IST date + its epoch-ms window),
 * a {@link DayTicks} (one day's ticks in columnar primitive arrays), a {@link CandleBar} (one
 * 1-minute OHLCV bar), and {@link BarBuilder} which aggregates ticks into bars.
 *
 * <p>Grouped in one file because they are small value/holder types with no behaviour beyond shape.
 */
final class MarketData {
    private MarketData() {}
}

/**
 * One trading day, identified by its IST calendar date and the half-open UTC epoch-millis window
 * {@code [startMsInclusive, endMsExclusive)} that bounds it — pure integer arithmetic over the IST
 * day, so it maps cleanly onto the {@code idx_sym_ts} index.
 *
 * @param tickCount ticks observed at discovery time; a sizing hint only (the live day may have grown)
 */
record TradingDay(LocalDate date, long startMsInclusive, long endMsExclusive, int tickCount) {

    /** Milliseconds offset of IST (UTC+05:30). */
    static final long IST_OFFSET_MS = 19_800_000L;

    /** Milliseconds in a calendar day. */
    static final long DAY_MS = 86_400_000L;

    /** Builds a day from an IST epoch-day number ({@code (ts_ms + IST_OFFSET_MS) / DAY_MS}). */
    static TradingDay fromIstEpochDay(long istEpochDay, int tickCount) {
        long start = istEpochDay * DAY_MS - IST_OFFSET_MS;
        return new TradingDay(LocalDate.ofEpochDay(istEpochDay), start, start + DAY_MS, tickCount);
    }
}

/**
 * All ticks for a single {@link TradingDay}, held in parallel primitive arrays (struct-of-arrays):
 * no boxing, good cache locality. Ticks are ordered by {@code tsMs} ascending; read them with the
 * columnar accessors in tight loops. Effectively immutable — callers must not mutate the arrays.
 */
final class DayTicks {

    private final LocalDate date;
    private final long[]    tsMs;
    private final double[]  ltp;
    private final long[]    vol;
    private final long[]    oi;
    private final int       size;

    /** @param size number of valid leading elements; the arrays may be longer (spare capacity) */
    DayTicks(LocalDate date, long[] tsMs, double[] ltp, long[] vol, long[] oi, int size) {
        this.date = date;
        this.tsMs = tsMs;
        this.ltp  = ltp;
        this.vol  = vol;
        this.oi   = oi;
        this.size = size;
    }

    LocalDate date() { return date; }
    int       size() { return size; }

    long   tsMs(int i) { return tsMs[i]; }
    double ltp(int i)  { return ltp[i]; }
    long   vol(int i)  { return vol[i]; }
    long   oi(int i)   { return oi[i]; }
}

/**
 * One 1-minute OHLCV bar — the unit of historical data the shredder consumes.
 *
 * @param minuteOfDay   minute-of-day IST (e.g. 09:15 → 555)
 * @param minuteStartMs UTC epoch-millis of the start of this clock minute
 */
record CandleBar(LocalDate date, int minuteOfDay, long minuteStartMs,
                 double open, double high, double low, double close, long volume) {

    /** Intraday bucket (0 OPEN / 1 MORNING / 2 MIDDAY / 3 CLOSE) this bar falls in. */
    int bucket() {
        int sec = minuteOfDay * 60;
        if (sec < 9 * 3600 + 45 * 60) return 0;   // OPEN   <09:45
        if (sec < 12 * 3600)          return 1;   // MORNING<12:00
        if (sec < 14 * 3600)          return 2;   // MIDDAY <14:00
        return 3;                                 // CLOSE
    }
}

/**
 * Aggregates a day's ticks into 1-minute OHLCV bars over the regular session — the inverse of
 * shredding, used to manufacture the bars the shredder is tested against (real bars → shred → must
 * reproduce the real tick statistics). A CSV bar source can replace this later without touching the
 * shredder.
 */
final class BarBuilder {

    private BarBuilder() {}

    static List<CandleBar> fromDay(DayTicks day) {
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

/**
 * Read-only access to a 1-minute candle database — the Host B input path. Distinct from
 * {@link TickStore} because the schema differs:
 * {@code candles(symbol, window_start, window_end, open, high, low, close, volume, tick_count)} with
 * {@code window_start} = UTC epoch-ms at the start of the minute. Rows are filtered to the regular
 * session (09:15–15:30 IST), mapped to {@link CandleBar}, and grouped by IST trading day so the
 * shredder can seed its RNG per day. Opened {@code SQLITE_OPEN_READONLY} (a live gateway may be writing).
 */
final class CandleStore {

    private final String jdbcUrl;
    private final String symbol;

    CandleStore(String dbPath, String symbol) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        this.symbol  = symbol;
    }

    /**
     * Loads session candles for this symbol, optionally bounded to the inclusive IST calendar-date
     * range {@code [fromIst, toIst]} (each {@code yyyy-MM-dd}; blank/null = unbounded) and capped at
     * {@code maxBars} bars ({@code <= 0} = all). Returns one inner list per IST trading day, days in
     * ascending order, bars within a day in {@code window_start} order.
     */
    List<List<CandleBar>> loadDays(String fromIst, String toIst, int maxBars) {
        var sql = new StringBuilder(
                "SELECT window_start, open, high, low, close, volume FROM candles WHERE symbol = ?");
        Long fromMs = null, toMs = null;
        if (fromIst != null && !fromIst.isBlank()) {
            fromMs = LocalDate.parse(fromIst.trim()).toEpochDay() * TradingDay.DAY_MS - TradingDay.IST_OFFSET_MS;
            sql.append(" AND window_start >= ?");
        }
        if (toIst != null && !toIst.isBlank()) {
            toMs = (LocalDate.parse(toIst.trim()).toEpochDay() + 1) * TradingDay.DAY_MS - TradingDay.IST_OFFSET_MS;
            sql.append(" AND window_start < ?");
        }
        sql.append(" ORDER BY window_start");

        var days = new ArrayList<List<CandleBar>>();
        List<CandleBar> cur = null;
        LocalDate curDate = null;
        int total = 0;

        try (Connection c = openReadOnly();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int p = 1;
            ps.setString(p++, symbol);
            if (fromMs != null) ps.setLong(p++, fromMs);
            if (toMs   != null) ps.setLong(p++, toMs);
            ps.setFetchSize(10_000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ws    = rs.getLong(1);
                    long istMs = ws + TradingDay.IST_OFFSET_MS;
                    int  sec   = (int) ((istMs % TradingDay.DAY_MS) / 1000);
                    if (sec < PriceMoveAnalyzer.SESSION_START_SEC || sec >= PriceMoveAnalyzer.SESSION_END_SEC)
                        continue;

                    LocalDate date = LocalDate.ofEpochDay(istMs / TradingDay.DAY_MS);
                    var bar = new CandleBar(date, sec / 60, ws,
                            rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getLong(6));

                    if (!date.equals(curDate)) {
                        cur = new ArrayList<>();
                        days.add(cur);
                        curDate = date;
                    }
                    cur.add(bar);
                    if (maxBars > 0 && ++total >= maxBars) break;
                }
            }
        } catch (SQLException e) {
            throw new TickStore.DataAccessException("Failed to load candles for " + symbol, e);
        }
        return days;
    }

    private Connection openReadOnly() throws SQLException {
        var cfg = new SQLiteConfig();
        cfg.setReadOnly(true);
        return cfg.createConnection(jdbcUrl);
    }
}
