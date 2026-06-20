package _40c.nqUtilities.shred;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Writes shredded ticks as <strong>AmiBroker-friendly ASCII CSV</strong> — one row per tick, the
 * easiest thing for AmiBroker's ASCII Import Wizard to ingest (no SQLite/ODBC plugin needed):
 *
 * <pre>
 *   Ticker,Date,Time,Close,Volume
 *   NIFTY,2011-01-03,09:15:00.000,6177.45,150
 * </pre>
 *
 * <ul>
 *   <li><b>Close only</b> — for tick data AmiBroker fills O=H=L=C from Close, so one price column is
 *       enough (and half the size of an OHLC dump).</li>
 *   <li><b>Millisecond IST time</b> — the shredder lays ticks on a 250&nbsp;ms grid; keeping the
 *       milliseconds means no two ticks share a timestamp, so none collapse on import.</li>
 *   <li><b>Per-tick incremental volume</b> — the {@link DayTicks} volume is the day's running
 *       cumulative total; we emit the per-tick delta so AmiBroker re-sums ticks to each bar's exact
 *       volume. (Cumulative resets per day, so the first tick of a day carries its own delta.)</li>
 * </ul>
 *
 * <p>One file per run; the whole selected range goes into a single CSV.
 */
final class CsvTickWriter {

    private static final Logger log = LoggerFactory.getLogger(CsvTickWriter.class);

    private final Path   file;
    private final String ticker;

    CsvTickWriter(Path file, String ticker) {
        this.file   = file;
        this.ticker = ticker;
    }

    /** Writes every day's ticks (days already ascending) to one CSV; returns the row count. */
    long write(List<DayTicks> days) {
        long rows = 0;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file)) {
                w.write("Ticker,Date,Time,Close,Volume\n");
                var sb = new StringBuilder(48);
                for (DayTicks d : days) {
                    int  n       = d.size();
                    long prevCum = 0;                       // cumulative volume resets each day
                    for (int i = 0; i < n; i++) {
                        long cum = d.vol(i);
                        appendRow(sb, d.tsMs(i), d.ltp(i), cum - prevCum);
                        prevCum = cum;
                        w.write(sb.toString());
                        sb.setLength(0);
                        rows++;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write CSV " + file, e);
        }
        log.info("Wrote {} tick rows to {}", String.format("%,d", rows), file.toAbsolutePath());
        return rows;
    }

    /** One CSV line: {@code Ticker,yyyy-MM-dd,HH:mm:ss.SSS,price,volume}. */
    private void appendRow(StringBuilder sb, long tsMs, double ltp, long vol) {
        long      ist   = tsMs + TradingDay.IST_OFFSET_MS;   // shift UTC epoch-ms into IST wall-clock
        long      dayMs = Math.floorMod(ist, TradingDay.DAY_MS);
        LocalDate date  = LocalDate.ofEpochDay(Math.floorDiv(ist, TradingDay.DAY_MS));
        int ms = (int) (dayMs % 1000);
        int s  = (int) (dayMs / 1000);
        int hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;

        sb.append(ticker).append(',')
          .append(date).append(',')
          .append(two(hh)).append(':').append(two(mm)).append(':').append(two(ss)).append('.').append(three(ms))
          .append(',');
        appendPrice(sb, ltp);
        sb.append(',').append(vol).append('\n');
    }

    /** Prices are on the 0.05 grid, so two decimals are exact; emit them without float artefacts. */
    private static void appendPrice(StringBuilder sb, double ltp) {
        long cents = Math.round(ltp * 100.0);
        sb.append(cents / 100).append('.');
        int f = (int) (cents % 100);
        if (f < 10) sb.append('0');
        sb.append(f);
    }

    private static String two(int v)   { return v < 10 ? "0" + v : Integer.toString(v); }
    private static String three(int v) { return v < 10 ? "00" + v : v < 100 ? "0" + v : Integer.toString(v); }
}
