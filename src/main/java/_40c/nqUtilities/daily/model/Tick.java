package _40c.nqUtilities.daily.model;

/**
 * A single NIFTY-1 tick as stored in {@code nifty_ticks.db}.
 *
 * <p>This is the convenience, object-oriented view of a tick. For hot analysis loops
 * prefer the primitive columnar arrays exposed by {@link DayTicks} to avoid per-tick
 * allocation; use this record where ergonomics matter more than throughput.
 *
 * @param tsMs UTC epoch-millis at which nqTicker wrote the tick
 * @param ltp  last traded price
 * @param vol  cumulative traded volume reported by the feed
 * @param oi   open interest reported by the feed
 */
public record Tick(long tsMs, double ltp, long vol, long oi) {}
