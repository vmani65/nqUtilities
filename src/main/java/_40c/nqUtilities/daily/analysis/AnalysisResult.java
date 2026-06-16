package _40c.nqUtilities.daily.analysis;

/**
 * Marker for whatever a {@link DayAnalyzer} produces for one trading day.
 *
 * <p>Concrete analyses define their own result records (counts, signals, statistics, …);
 * the only contract the framework needs is a one-line human-readable {@link #summary()}
 * so the runner can print a uniform report regardless of the analysis plugged in.
 */
public interface AnalysisResult {

    /** A concise, single-line description of this day's result, for the console report. */
    String summary();

    /** A full, multi-line breakdown. Defaults to {@link #summary()}. */
    default String report() {
        return summary();
    }
}
