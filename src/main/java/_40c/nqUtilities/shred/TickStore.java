package _40c.nqUtilities.shred;

import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SQLite access to a {@code nifty_ticks(sym, ts_ms, ltp, vol, oi)} table for one symbol — the read
 * and write ends of the pipeline in one place. Construct one over the <em>source</em> database to
 * read ({@link #discoverTradingDays}, {@link #loadWindow}, all opened {@code READONLY} so a running
 * gateway is never blocked), and one over the <em>output</em> database to write ({@link #resetOutput},
 * {@link #write}). The output mirrors the source schema (and {@code idx_sym_ts}) so it is re-readable
 * here.
 *
 * <p>Each method opens its own short-lived connection, so a single instance is safe to share across
 * the per-day virtual threads with no external synchronisation. Serialise {@link #write} calls.
 */
final class TickStore {

    private static final String TABLE = "nifty_ticks";

    private final String dbPath;
    private final String jdbcUrl;
    private final String symbol;

    TickStore(String dbPath, String symbol) {
        this.dbPath  = dbPath;
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        this.symbol  = symbol;
    }

    // ---------------------------------------------------------------------- read

    private Connection openReadOnly() throws SQLException {
        var cfg = new SQLiteConfig();
        cfg.setReadOnly(true);
        return cfg.createConnection(jdbcUrl);
    }

    /**
     * Discovers every IST trading day with at least one tick for this symbol, ascending. Grouping is
     * integer arithmetic on {@code ts_ms} so the query is served from {@code idx_sym_ts}.
     */
    List<TradingDay> discoverTradingDays() {
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
            ps.setString(3, symbol);
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
     * Loads this symbol's ticks for {@code day} restricted to {@code [fromMs, toMs)}, time-ordered,
     * into columnar primitive arrays. Pushing the time bound into SQL lets callers read only the
     * regular session and skip the bulk of off-hours rows.
     */
    DayTicks loadWindow(TradingDay day, long fromMs, long toMs) {
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
            ps.setString(1, symbol);
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

    // ---------------------------------------------------------------------- write

    /** Creates the table/index if absent and removes any existing rows for this symbol. */
    void resetOutput() {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        sym   TEXT    NOT NULL,
                        ts_ms INTEGER NOT NULL,
                        ltp   REAL    NOT NULL,
                        vol   INTEGER NOT NULL,
                        oi    INTEGER NOT NULL
                    )""".formatted(TABLE));
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sym_ts ON %s(sym, ts_ms)".formatted(TABLE));
            try (PreparedStatement del = c.prepareStatement("DELETE FROM %s WHERE sym = ?".formatted(TABLE))) {
                del.setString(1, symbol);
                del.executeUpdate();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to reset output database " + dbPath, e);
        }
    }

    /**
     * Inserts every tick in {@code day} under this store's symbol, in one batched transaction.
     *
     * @return the number of rows written
     */
    int write(DayTicks day) {
        String sql = "INSERT INTO %s (sym, ts_ms, ltp, vol, oi) VALUES (?, ?, ?, ?, ?)".formatted(TABLE);
        int n = day.size();
        try (Connection c = DriverManager.getConnection(jdbcUrl)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < n; i++) {
                    ps.setString(1, symbol);
                    ps.setLong(2, day.tsMs(i));
                    ps.setDouble(3, day.ltp(i));
                    ps.setLong(4, day.vol(i));
                    ps.setLong(5, day.oi(i));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to write ticks for " + day.date(), e);
        }
        return n;
    }

    /** Unchecked wrapper so pipeline code need not declare {@link SQLException}. */
    static final class DataAccessException extends RuntimeException {
        DataAccessException(String message, Throwable cause) { super(message, cause); }
    }
}
