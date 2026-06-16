package _40c.nqUtilities.daily.data;

import _40c.nqUtilities.daily.model.DayTicks;
import _40c.nqUtilities.daily.model.TradingDay;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Read-only access to the {@code nifty_ticks} table for the {@code NIFTY-1} symbol.
 *
 * <p>The database is the <em>live</em> file written by nqTicker's {@code SqliteTickWriter},
 * so every connection is opened {@code SQLITE_OPEN_READONLY}: we never lock or write, and
 * the running gateway is unaffected.
 *
 * <p>JDBC {@link Connection}s are not thread-safe, so each public method opens and closes
 * its own short-lived connection. That makes the repository safe to share across the
 * per-day virtual threads with no external synchronisation.
 */
public final class TickRepository {

    /** The only symbol this framework cares about. */
    public static final String SYMBOL = "NIFTY-1";

    private final String jdbcUrl;

    public TickRepository(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
    }

    private Connection openReadOnly() throws SQLException {
        var cfg = new SQLiteConfig();
        cfg.setReadOnly(true);
        return cfg.createConnection(jdbcUrl);
    }

    /**
     * Discovers every IST trading day that has at least one NIFTY-1 tick, ordered ascending.
     * Grouping is done with integer arithmetic on {@code ts_ms} so the query is served from
     * the {@code idx_sym_ts} index without materialising rows.
     */
    public List<TradingDay> discoverTradingDays() {
        String sql = """
                SELECT (ts_ms + ?) / ?      AS ist_day,
                       COUNT(*)             AS n
                FROM   nifty_ticks
                WHERE  sym = ?
                GROUP  BY ist_day
                ORDER  BY ist_day
                """;
        var days = new ArrayList<TradingDay>();
        try (Connection c = openReadOnly();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, TradingDay.IST_OFFSET_MS);
            ps.setLong(2, TradingDay.DAY_MS);
            ps.setString(3, SYMBOL);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    days.add(TradingDay.fromIstEpochDay(rs.getLong("ist_day"), rs.getInt("n")));
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to discover trading days", e);
        }
        return days;
    }

    /**
     * Loads all NIFTY-1 ticks for the given day, time-ordered, into a columnar {@link DayTicks}.
     * The arrays are pre-sized from {@link TradingDay#tickCount()} and grow geometrically if the
     * live day produced more ticks since discovery.
     */
    public DayTicks loadDay(TradingDay day) {
        return loadWindow(day, day.startMsInclusive(), day.endMsExclusive());
    }

    /**
     * Loads NIFTY-1 ticks for {@code day} restricted to {@code [fromMs, toMs)}, time-ordered.
     * Pushing the time bound into SQL lets callers (e.g. the shredder) read only the regular
     * session and skip the bulk of off-hours rows.
     */
    public DayTicks loadWindow(TradingDay day, long fromMs, long toMs) {
        String sql = """
                SELECT ts_ms, ltp, vol, oi
                FROM   nifty_ticks
                WHERE  sym = ? AND ts_ms >= ? AND ts_ms < ?
                ORDER  BY ts_ms
                """;
        int cap = Math.max(day.tickCount(), 16);
        long[]   tsMs = new long[cap];
        double[] ltp  = new double[cap];
        long[]   vol  = new long[cap];
        long[]   oi   = new long[cap];
        int n = 0;

        try (Connection c = openReadOnly();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, SYMBOL);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            ps.setFetchSize(10_000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (n == tsMs.length) {
                        int grown = tsMs.length + (tsMs.length >> 1);  // 1.5x
                        tsMs = Arrays.copyOf(tsMs, grown);
                        ltp  = Arrays.copyOf(ltp,  grown);
                        vol  = Arrays.copyOf(vol,  grown);
                        oi   = Arrays.copyOf(oi,   grown);
                    }
                    tsMs[n] = rs.getLong(1);
                    ltp[n]  = rs.getDouble(2);
                    vol[n]  = rs.getLong(3);
                    oi[n]   = rs.getLong(4);
                    n++;
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to load ticks for " + day.date(), e);
        }
        return new DayTicks(day.date(), tsMs, ltp, vol, oi, n);
    }

    /** Unchecked wrapper so analysis tasks need not declare {@link SQLException}. */
    public static final class DataAccessException extends RuntimeException {
        public DataAccessException(String message, Throwable cause) { super(message, cause); }
    }
}
