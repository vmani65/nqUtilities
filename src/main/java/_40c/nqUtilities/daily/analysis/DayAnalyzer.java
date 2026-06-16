package _40c.nqUtilities.daily.analysis;

import _40c.nqUtilities.daily.model.DayTicks;

/**
 * Strategy that turns one day of NIFTY-1 ticks into an {@link AnalysisResult}.
 *
 * <p>This is the extension point of the framework: implement it to define <em>what</em> the
 * per-day analysis actually computes. The runner takes care of <em>how</em> — discovering the
 * days, loading each day's ticks, and running one analyzer invocation per day on its own
 * virtual thread.
 *
 * <p>Implementations should be stateless (or thread-safe): the same analyzer instance is
 * invoked concurrently from many threads, one per trading day.
 *
 * @param <R> the result type this analyzer produces
 */
@FunctionalInterface
public interface DayAnalyzer<R extends AnalysisResult> {

    /**
     * Analyse a single day's ticks. Called on a dedicated virtual thread per day.
     *
     * @param day the day's ticks, time-ordered (never {@code null}, possibly empty)
     * @return the result for this day
     */
    R analyze(DayTicks day);

    /** Display name for reports; defaults to the implementing class's simple name. */
    default String name() {
        return getClass().getSimpleName();
    }
}
