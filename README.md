# nqUtilities

Shreds 1-minute NIFTY OHLCV bars into tick-level data that reproduces the *measured* live
NIFTY-1 behaviour, verifies the result against that behaviour, and saves the synthesised ticks
to a new SQLite database.

Each 1-minute bar is shredded into a price path that satisfies the bar's **hard constraints**
(starts at O, ends at C, touches H and L, stays in `[L,H]`, snaps to the 0.05 grid, volume sums
to V) while reproducing the **soft statistical behaviour** distilled from the live data.

## Three modes

The behaviour model can be **learned once and reused** — the tick feed is needed only to (re)build
the model, never to use it. Set `mode` in the config:

| Mode | Host | Reads | Does |
|---|---|---|---|
| `selftest` (default) | tick host | tick DB | closed loop: ticks → bars → shred → verify (a faithful shredder regenerates the statistics the bars were built from) |
| `learn` | tick host (Host A) | tick DB | build the behaviour model and **freeze** the whole `PriceMoveProfile` to `model.file` |
| `shred` | candle host (Host B) | `model.file` + 1-min candle DB | load the frozen model, shred each candle into ticks, write them out, and validate against the **frozen** baseline (no tick DB needed) |

```
  learn  (Host A, has ticks)                    shred  (Host B, has 1-min candles only)
  ──────────────────────────                    ──────────────────────────────────────
  read real ticks                               PriceMoveProfile.load(model.file)
  build PriceMoveProfile                                │
        │                                        read candles (CandleStore)
  profile.save(model.file) ───── copy file ───►  shred each bar → ticks (frozen model)
                                                        │
                                                 write synthetic ticks → output DB
                                                        │
                                                 validate shredded vs the frozen profile
```

The frozen model is a single self-describing text file (`model/nifty1.profile.json`, hand-rolled
`key=value` — no JSON library): the full merged `PriceMoveProfile` (every `Histogram`/`Stats`,
counters, and a `formatVersion`). Load is a **bit-for-bit round-trip**, so percentiles and samples
are unchanged; `learn` logs a round-trip check after saving.

### `learn` tick source: `sqlite` or `queue`

`learn` reads ticks from one of two sources (`source.type`):

- **`sqlite`** — the `nifty_ticks` mirror DB (a small rolling window).
- **`queue`** — the live **nqTicker Chronicle Queue** (`queue.dir`, default `C:/novaquant/data/ticks`),
  the full tick history written by the AccelPix gateway. [`QueueTickSource`](src/main/java/_40c/nqUtilities/shred/QueueTickSource.java)
  opens a concurrent read-only tailer (safe alongside the live writer), decodes each
  `{ ts, sym, ltp, op, hp, lp, cp, bid, ask, vol, oi }` entry (where `ts` is nanoseconds since UTC
  midnight), groups ticks by daily roll cycle, and streams one day at a time into the analyzer. Days
  whose regular session has fewer than `learn.min.distinct.prices` distinct ltps are logged and
  **skipped as stale** (frozen/heartbeat feed data, not real trading).

Reading the queue uses Chronicle, which needs JVM flags (see **Build & run**) and the
`net.openhft:chronicle-queue` dependency (managed by `chronicle-bom` in `pom.xml`).

`selftest` keeps the original closed-loop diagram:

```
read source ticks ─► build behaviour model ─► aggregate to 1-min bars
        │                                              │
        │                                       shred each bar ─► ticks
        │                                              │
        └──────────────► verify (live vs shredded metrics) ◄─────┘
                                       │
                                save shredded ticks ─► new SQLite DB
```

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

Supporting types (co-located): `TradingDay`/`DayTicks`/`CandleStore` (in `MarketData.java`),
`Histogram`/`Stats` (in `Stats.java`), `TickModel`/`ShredEvents` (in `TickShredder.java`), and
`ShredConfig` (nested in `ShredderMain`). `CandleStore` is the `shred`-mode input: it reads
`candles(symbol, window_start, open, high, low, close, volume, …)` read-only, filters to the
session, and groups bars by IST trading day.

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
| `mode`          | `selftest` (default) / `learn` / `shred` |
| `source.type`   | `learn` tick source: `sqlite` or `queue` (`selftest` always `sqlite`) |
| `source.db`     | source tick DB, read-only (`selftest`, and `learn` when `source.type=sqlite`) |
| `queue.dir`     | nqTicker Chronicle Queue dir (`learn` when `source.type=queue`; default `C:/novaquant/data/ticks`) |
| `output.db`     | database the shredded ticks are written to (created/overwritten for the symbol) |
| `symbol`        | symbol read from source and written to output (default `NIFTY-1`) |
| `days`          | most-recent trading days to scan for bars (`selftest`, sqlite `learn`) |
| `target.bars`   | stop selecting once this many 1-minute bars are gathered; in `shred` it caps the bars read (`<= 0` = all) |
| `model.file`    | frozen model file — written by `learn`, read by `shred` (default `model/nifty1.profile.json`) |
| `candle.db`     | 1-min candle DB, read-only (`shred`) |
| `candle.symbol` | symbol in the candle table (default `NIFTY`) |
| `from` / `to`   | optional inclusive IST date window `yyyy-MM-dd`: bounds `shred` candles and queue-`learn` cycles (empty = all) |
| `learn.min.distinct.prices` | queue-`learn` stale filter: skip a day with fewer than this many distinct session ltps (default 10; `1` keeps all) |

SQLite connections are opened `SQLITE_OPEN_READONLY` and the Chronicle Queue is read with a
concurrent read-only tailer, so a running nqTicker gateway is never blocked. The output DB mirrors the
`nifty_ticks(sym, ts_ms, ltp, vol, oi)` schema (and `idx_sym_ts`), so it is re-readable by `TickStore`.

## Requirements

- JDK 21+ (virtual threads); JDK 25 if reading the Chronicle Queue (matches the nqTicker runtime)
- Maven 3.x
- `org.xerial:sqlite-jdbc`, `org.slf4j:slf4j-api`, `ch.qos.logback:logback-classic`, and
  `net.openhft:chronicle-queue` (via `chronicle-bom`) — all in `pom.xml`

## Build & run

```sh
mvn clean compile

# let Maven assemble the runtime classpath once
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt -q

# run (uses the bundled shred.properties unless a config file is given; mode is set inside it)
java -cp "target/classes;$(cat cp.txt)" _40c.nqUtilities.shred.ShredderMain [configFile]
```

Reading the Chronicle Queue (`source.type=queue`) additionally needs the Chronicle JVM flags:

```sh
java --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
     --add-opens java.base/java.nio=ALL-UNNAMED \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     --add-exports java.base/jdk.internal.ref=ALL-UNNAMED \
     --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
     -Duser.timezone=Asia/Kolkata \
     -cp "target/classes;$(cat cp.txt)" _40c.nqUtilities.shred.ShredderMain learn.properties
```

Typical cross-host flow: run with a `mode = learn` config on the tick host to write
`model/nifty1.profile.json`, copy that file to the candle host, then run with a `mode = shred`
config there.

The runtime classpath needs the sqlite-jdbc, slf4j-api, logback-classic/core, and (for queue learn)
the chronicle-* jars, plus `target/classes` (compiled code + `logback.xml` + `shred.properties`).

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
    PriceMoveAnalyzer.java   behaviour analysis (+ PriceMoveProfile, save/load)
    TickShredder.java        bar → ticks (+ TickModel, ShredEvents)
    Stats.java               Histogram + Stats (+ save/restore)
    MarketData.java          TradingDay, DayTicks, CandleBar, BarBuilder, CandleStore
    QueueTickSource.java     Chronicle Queue tick reader (learn source.type=queue)
  src/main/resources/{shred.properties, logback.xml}
  model/nifty1.profile.json  frozen behaviour model (written by learn, read by shred)
  README.md
```
