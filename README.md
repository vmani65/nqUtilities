# nqUtilities

Standalone Java utilities for NQ / Nifty market-data processing.

## Per-day NIFTY-1 analysis framework

A framework that reads the **live** `nifty_ticks.db` written by nqTicker, finds every IST
trading day that has `NIFTY-1` data, and fans out **one virtual thread per day**. Each thread
loads only its own day's ticks and runs a pluggable analyzer over them; results are collected
back in date order.

```
discover trading days (NIFTY-1)
        │
        ├── vthread day 1 ─► load day ─► DayAnalyzer.analyze ─► result
        ├── vthread day 2 ─► load day ─► DayAnalyzer.analyze ─► result
        └── …            (one virtual thread per day)
```

### Key types

| Type | Role |
|---|---|
| `daily.DailyAnalysisMain` | Entry point; wires DB path + analyzer and prints the report |
| `daily.DailyAnalysisRunner<R>` | Discovers days, spawns one virtual thread per day, collects outcomes |
| `daily.data.TickRepository` | Read-only SQLite access for `NIFTY-1` (day discovery + per-day load) |
| `daily.model.TradingDay` | One IST trading day + its `[start, end)` epoch-ms window |
| `daily.model.DayTicks` | A day's ticks in columnar primitive arrays (no boxing) |
| `daily.model.Tick` | Convenience record view of a single tick |
| `daily.analysis.DayAnalyzer<R>` | **Extension point** — implement to define the analysis |
| `daily.analysis.AnalysisResult` | Marker with a one-line `summary()` for reports |
| `daily.analysis.SummaryAnalyzer` | Placeholder analyzer (count / OHLC / net change) |

### Adding a real analysis

Implement `DayAnalyzer<R>` (where `R implements AnalysisResult`) and wire it into
`DailyAnalysisMain`. The analyzer instance is shared across all day-threads, so keep it
stateless / thread-safe. Use `DayTicks` columnar accessors in hot loops.

### Notes

- Every connection is opened `SQLITE_OPEN_READONLY` — the running nqTicker gateway is never
  blocked or affected.
- Only `NIFTY-1` is read; option (CE/PE) symbols in the table are ignored by design.
- IST day boundaries are computed with integer math on `ts_ms`, served from `idx_sym_ts`.

## Candle → tick shredder

Synthesises tick-level data from 1-minute OHLCV bars so it matches the *measured* live NIFTY-1
behaviour. Each bar is shredded into a price path that satisfies the bar's hard constraints
(starts at O, ends at C, touches H and L, stays in `[L,H]`, snaps to the 0.05 grid, volume sums to V)
while reproducing the soft statistical behaviour from the analysis.

### Types (`daily.shred`)

| Type | Role |
|---|---|
| `ShredderMain` | Builds the live model, shreds ~5000 real bars, validates live vs shredded |
| `TickModel` | Samplable distributions distilled from a `PriceMoveProfile` (counts, bps moves, gaps, reversal, volume) |
| `TickShredder` | Shreds one `CandleBar` → tick path (waypoint segments + mean-reverting bridge walk) |
| `CandleBar` | One 1-minute OHLCV bar |
| `BarBuilder` | Aggregates a `DayTicks` into 1-minute bars (the closed-loop test source) |

### Method

1. The live analysis becomes a `TickModel` — empirical **histograms** are sampled (so tails match,
   not just means): changes/min per intraday bucket, |%move| in bps, gap-ms, reversal probability.
2. Per bar: sample the change count for its bucket; lay out waypoints `O → E1 → E2 → C` with
   `{E1,E2}={H,L}`; split the changes across the three legs by distance.
3. Each leg is a Markov-sign mean-reverting walk with bps-sampled magnitudes, steered toward its
   waypoint only when it risks not reaching it ("schedule pressure"). Prices clamp to `[L,H]` and
   snap to 0.05; volume and 250ms-grid timestamps are sampled to fit the minute.

### Validation

`ShredderMain` runs the **same** `PriceMoveAnalyzer` over the shredded ticks and compares the
resulting profile to the live one across the full behaviour set — percentiles (median/p90/p99) of
move size and counts, reversal rate, direction, gaps, volume, and the per-bucket intraday profile —
flagging any metric outside tolerance. The closed-loop test (bars aggregated from real ticks) passes
all metrics; `TickShredder.REVERSAL_BOOST` is the one calibration knob.

```sh
java -cp "target/classes;$(cat cp.txt)" _40c.nqUtilities.daily.shred.ShredderMain [dbPath] [targetBars]
```

A real historical CSV can replace `BarBuilder` as the bar source without touching the shredder.

## Other utilities

- `_40c.nqUtilities.utilities.Nifty1MinDataShredder` — splits a large Nifty 1-minute OHLCV CSV
  into smaller per-day / per-symbol files.

## Requirements

- JDK 21 (virtual threads)
- Maven 3.x
- `org.xerial:sqlite-jdbc`, `org.slf4j:slf4j-api`, `ch.qos.logback:logback-classic` (all in `pom.xml`)

## Logging

All output goes through SLF4J + Logback (no `System.out`). The console pattern and levels are in
`src/main/resources/logback.xml`; multi-line reports/tables are logged as a single message.

## Build & run

```sh
mvn -o clean compile

# easiest: let Maven assemble the runtime classpath once
mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt -q

# run the per-day analysis (defaults to the live nqTicker DB)
java -cp "target/classes;$(cat cp.txt)" _40c.nqUtilities.daily.DailyAnalysisMain [dbPath]
```

The runtime classpath needs the sqlite-jdbc, slf4j-api, logback-classic and logback-core jars
(plus `target/classes`, which holds the compiled code and `logback.xml`).

## Layout

```
nqUtilities/
  pom.xml
  src/main/java/_40c/nqUtilities/
    daily/                 # per-day NIFTY-1 analysis framework
      DailyAnalysisMain.java
      DailyAnalysisRunner.java
      data/TickRepository.java
      model/{Tick,TradingDay,DayTicks}.java
      analysis/{DayAnalyzer,AnalysisResult,SummaryAnalyzer}.java
    utilities/Nifty1MinDataShredder.java
  README.md
```
