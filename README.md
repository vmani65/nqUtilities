# nqUtilities

Shreds 1-minute NIFTY OHLCV bars into tick-level data that reproduces the *measured* live
NIFTY-1 behaviour, verifies the result against that behaviour, and saves the synthesised ticks
to a new SQLite database.

## What it does

```
read source ticks ─► build behaviour model ─► aggregate to 1-min bars
        │                                              │
        │                                       shred each bar ─► ticks
        │                                              │
        └──────────────► verify (live vs shredded metrics) ◄─────┘
                                       │
                                save shredded ticks ─► new SQLite DB
```

Each 1-minute bar is shredded into a price path that satisfies the bar's **hard constraints**
(starts at O, ends at C, touches H and L, stays in `[L,H]`, snaps to the 0.05 grid, volume sums
to V) while reproducing the **soft statistical behaviour** distilled from the live data.

Because the bars are aggregated from real session ticks, the run is a **closed-loop test**: a
faithful shredder must regenerate the same statistics the bars were built from.

## Pipeline (`shred.ShredderMain`)

Everything lives in one package — `_40c.nqUtilities.shred` — across six files; small value/holder
and helper types are co-located rather than split into their own files.

| Step | Type | Role |
|---|---|---|
| read   | `TickStore` | SQLite read+write for a configurable symbol (day discovery, per-day load, output write) |
| model  | `PriceMoveAnalyzer` → `PriceMoveProfile` → `TickModel` | Distil live tick behaviour into samplable distributions |
| bars   | `BarBuilder` → `CandleBar` | Aggregate real ticks into 1-minute OHLCV bars |
| shred  | `TickShredder` | Shred one bar → tick path (waypoint segments + mean-reverting bridge walk) |
| verify | `ShredderMain.Validation` | Re-analyse the shredded ticks and flag any metric outside tolerance |
| save   | `TickStore.write` | Write the shredded ticks to a new DB mirroring the source schema |

Supporting types (co-located): `TradingDay`/`DayTicks` (in `MarketData.java`), `Histogram`/`Stats`
(in `Stats.java`), `TickModel`/`ShredEvents` (in `TickShredder.java`), and `ShredConfig` (nested in
`ShredderMain`).

### Method

1. The live analysis becomes a `TickModel` — empirical **histograms** are sampled (so tails match,
   not just means): changes/min per intraday bucket, |%move| in bps, gap-ms, reversal probability.
2. Per bar: sample the change count for its bucket; lay out waypoints `O → E1 → E2 → C` with
   `{E1,E2}={H,L}`; split the changes across the three legs by distance.
3. Each leg is a Markov-sign mean-reverting walk with bps-sampled magnitudes, steered toward its
   waypoint only when it risks not reaching it ("schedule pressure"). Prices clamp to `[L,H]` and
   snap to 0.05; volume and 250ms-grid timestamps are sampled to fit the minute.

`TickShredder.REVERSAL_BOOST` is the one calibration knob.

## Configuration

Run config lives in a `.properties` file. The bundled
[`src/main/resources/shred.properties`](src/main/resources/shred.properties) holds the defaults;
pass an alternate file as the first CLI argument. Missing keys fall back to the defaults.

| Key | Meaning |
|---|---|
| `source.db`   | source SQLite database to read ticks from (read-only) |
| `output.db`   | database the shredded ticks are written to (created/overwritten for the symbol) |
| `symbol`      | symbol read from source and written to output (default `NIFTY-1`) |
| `days`        | most-recent trading days to scan for bars |
| `target.bars` | stop selecting days once this many 1-minute bars are gathered |

Every source connection is opened `SQLITE_OPEN_READONLY`, so a running nqTicker gateway is never
blocked. The output DB mirrors the `nifty_ticks(sym, ts_ms, ltp, vol, oi)` schema (and `idx_sym_ts`),
so it is re-readable by the same `TickStore`.

## Requirements

- JDK 21 (virtual threads)
- Maven 3.x
- `org.xerial:sqlite-jdbc`, `org.slf4j:slf4j-api`, `ch.qos.logback:logback-classic` (all in `pom.xml`)

## Build & run

```sh
mvn clean compile

# let Maven assemble the runtime classpath once
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt -q

# run the shredder (uses the bundled shred.properties unless a config file is given)
java -cp "target/classes;$(cat cp.txt)" _40c.nqUtilities.shred.ShredderMain [configFile]
```

The runtime classpath needs the sqlite-jdbc, slf4j-api, logback-classic and logback-core jars,
plus `target/classes` (compiled code + `logback.xml` + `shred.properties`).

## Logging

All output goes through SLF4J + Logback (no `System.out`). The console pattern and levels are in
[`src/main/resources/logback.xml`](src/main/resources/logback.xml); multi-line reports/tables are
logged as a single message.

## Layout

```
nqUtilities/
  pom.xml
  src/main/java/_40c/nqUtilities/shred/
    ShredderMain.java        entry point + pipeline (+ ShredConfig, Validation)
    TickStore.java           SQLite read + write (+ DataAccessException)
    PriceMoveAnalyzer.java   behaviour analysis (+ PriceMoveProfile)
    TickShredder.java        bar → ticks (+ TickModel, ShredEvents)
    Stats.java               Histogram + Stats
    MarketData.java          TradingDay, DayTicks, CandleBar, BarBuilder
  src/main/resources/{shred.properties, logback.xml}
  README.md
```
