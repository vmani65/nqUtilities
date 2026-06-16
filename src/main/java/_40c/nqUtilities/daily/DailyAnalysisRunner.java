package _40c.nqUtilities.daily;

import _40c.nqUtilities.daily.analysis.AnalysisResult;
import _40c.nqUtilities.daily.analysis.DayAnalyzer;
import _40c.nqUtilities.daily.data.TickRepository;
import _40c.nqUtilities.daily.model.DayTicks;
import _40c.nqUtilities.daily.model.TradingDay;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Framework core. Discovers every IST trading day of NIFTY-1 data, then fans out
 * <strong>one virtual thread per day</strong> — each thread loads only its own day's ticks
 * and runs the supplied {@link DayAnalyzer} over them. Results are collected back in date order.
 *
 * <p>Virtual threads (JDK 21) make a thread-per-day model cheap even at hundreds of days:
 * the work is I/O-bound (SQLite reads), so threads spend most of their time parked, and the
 * carrier pool stays small. The actual analysis is whatever {@link DayAnalyzer} you supply;
 * this class never assumes what it computes.
 *
 * @param <R> the per-day result type
 */
public final class DailyAnalysisRunner<R extends AnalysisResult> {

    private final TickRepository  repository;
    private final DayAnalyzer<R>  analyzer;

    public DailyAnalysisRunner(TickRepository repository, DayAnalyzer<R> analyzer) {
        this.repository = repository;
        this.analyzer   = analyzer;
    }

    /** Outcome of analysing one day: either a {@code result} or a {@code failure}. */
    public record DayOutcome<R extends AnalysisResult>(
            TradingDay day, R result, Throwable failure, long loadMillis, long analyzeMillis) {

        public boolean ok()        { return failure == null; }
        public LocalDate date()    { return day.date(); }
    }

    /**
     * Runs the analyzer across all discovered days, one virtual thread each.
     *
     * @return outcomes ordered by trading date
     */
    public List<DayOutcome<R>> run() {
        List<TradingDay> days = repository.discoverTradingDays();
        System.out.printf("Discovered %d trading days of %s data - spawning one thread each (analyzer: %s)%n",
                days.size(), TickRepository.SYMBOL, analyzer.name());

        // One virtual thread per day. try-with-resources awaits all tasks on close().
        List<Future<DayOutcome<R>>> futures;
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = days.stream()
                    .map(day -> exec.submit(() -> analyseDay(day)))
                    .toList();
        }

        return futures.stream()
                .map(DailyAnalysisRunner::join)
                .sorted(Comparator.comparing(DayOutcome::date))
                .toList();
    }

    private DayOutcome<R> analyseDay(TradingDay day) {
        long t0 = System.nanoTime();
        try {
            DayTicks ticks = repository.loadDay(day);
            long t1 = System.nanoTime();
            R result = analyzer.analyze(ticks);
            long t2 = System.nanoTime();
            return new DayOutcome<>(day, result, null, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);
        } catch (Throwable t) {
            long t1 = System.nanoTime();
            return new DayOutcome<>(day, null, t, (t1 - t0) / 1_000_000, 0);
        }
    }

    private static <R extends AnalysisResult> DayOutcome<R> join(Future<DayOutcome<R>> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new IllegalStateException("Task join failed", e);
        }
    }
}
