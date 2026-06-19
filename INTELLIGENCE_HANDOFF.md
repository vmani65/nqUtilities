# Cross-host plan: learn tick intelligence on one host, shred 1-min data on another

This file is a complete, self-contained instruction set for an AI agent (or developer).
Read it top to bottom, then read the actual source before editing — do not trust this
summary over the code.

---

## The big idea (two hosts, one artifact)

```
  HOST A  (has real tick data)                 HOST B  (this host: has 1-min data only)
  ──────────────────────────────              ───────────────────────────────────────
  read real ticks                              read 1-min OHLCV bars
        │                                            │
  build the behaviour intelligence             load the frozen intelligence file
  (PriceMoveProfile — detailed below)                │
        │                                      shred each bar → tick path
  SAVE it to a portable model file  ──────►   (TickShredder, using the loaded model)
                                                     │
                                               write synthetic ticks to a new DB
                                                     │
                                               validate shredded vs the frozen profile
```

- **Host A** has access to real NIFTY ticks. It runs once, distils the tick behaviour
  into a `PriceMoveProfile`, and writes a single **portable model file**.
- That model file is copied to **Host B** (this machine), which has the 1-min candle
  history but no tick feed. Host B loads the file and shreds the candles into ticks.

After this work, the tick feed is needed **only to (re)learn** the model — never to use it.

---

## Why this work is needed (the current gap)

Today the program ([`ShredderMain`](src/main/java/_40c/nqUtilities/shred/ShredderMain.java))
is a closed-loop self-test that requires the tick DB for every run:

1. The behaviour model is **never persisted** — `TickModel`/`PriceMoveProfile` are rebuilt
   in memory from the tick DB on each run and thrown away on JVM exit. The only frozen
   numbers in code are `REVERSAL_BOOST=1.15` and a placeholder `medianBps()=1.0`.
2. There is **no external 1-min input path** — even the bars it shreds are re-aggregated
   from the tick DB by `BarBuilder.fromDay()`. No candle-table/CSV reader exists.

We are closing both gaps and splitting the run into **three modes**: `learn`, `shred`,
and the existing `selftest`.

---

## Source layout you are changing

Single package `_40c.nqUtilities.shred`, JDK 21, Maven. Co-locate small helper types in
existing files (project convention — see `README.md`). Dependencies are limited to
`sqlite-jdbc`, `slf4j-api`, `logback-classic`. **Do NOT add new dependencies** (no Jackson/
Gson) — hand-roll the serialization.

| File | What it holds | Touch? |
|---|---|---|
| `ShredderMain.java` | entry point + pipeline + `ShredConfig` + `Validation` | yes — add modes |
| `TickStore.java` | SQLite read/write for `nifty_ticks(sym,ts_ms,ltp,vol,oi)` | no (reuse for learn) |
| `PriceMoveAnalyzer.java` | tick→`PriceMoveProfile` analysis | add `save`/`load` to `PriceMoveProfile` |
| `TickShredder.java` | bar→ticks + `TickModel` | no (reuse) |
| `Stats.java` | `Histogram` + `Stats` | add `save`/`load` to both |
| `MarketData.java` | `TradingDay`/`DayTicks`/`CandleBar`/`BarBuilder` | add a candle reader (new type) |
| `shred.properties` | config | add the new keys |

---

## The "intelligence" to build and save (DETAILED)

The intelligence is the merged `PriceMoveProfile` for the `LIVE` aggregate. **Persist the
whole profile**, not just the `TickModel` subset — the full profile is needed both to
rebuild the model (`TickModel.from(profile)`) AND as the validation baseline on Host B.

It is computed by `PriceMoveAnalyzer.analyze()` over the regular session only
(09:15:00–15:30:00 IST), keeping only **distinct** LTP changes (poll repeats dropped),
measuring each move in points, in 0.05-tick units, and as a **% return in basis points**
(the part that transfers across price levels). Intraday buckets:

- `OPEN` = before 09:45, `MORNING` = 09:45–12:00, `MIDDAY` = 12:00–14:00, `CLOSE` = 14:00–15:30.

### Distributions (`Histogram` — must serialize the empirical shape, tails included)

| Field | Geometry `(lo,hi,binWidth)` | Meaning |
|---|---|---|
| `changesPerMin` | (0, 250, 1) | distinct price changes per session minute |
| `absMovePts` | (0, 60, 0.05) | per-change |Δprice| in points |
| `signedRetBps` | (-150, 150, 0.1) | signed per-change % return (bps) |
| `absRetBps` | (0, 150, 0.1) | **|% return| per change (bps) — the core move-size model** |
| `moveTicks` | (0, 500, 1) | |Δprice| / 0.05, rounded |
| `gapMs` | (0, 30000, 50) | ms between consecutive changes |
| `distinctLevelsPerMin` | (0, 250, 1) | distinct 0.05 levels visited per minute |
| `bucketChangesPerMin[0..3]` | (0, 250, 1) each | changes/min **per intraday bucket** |

### Running statistics (`Stats`)

| Field | Meaning |
|---|---|
| `volPerMin` | traded volume per session minute |
| `volPerChange` | traded volume between consecutive price changes |
| `revisitRatio` | fraction of changes landing on an already-seen level (per minute) |
| `pathEfficiency` | |net move| / gross travel per minute |
| `bucketAbsRetBps[0..3]` | mean |% return| (bps) per intraday bucket |

### Counters / scalars

`sessionRows`, `totalChanges`, `upChanges`, `downChanges`, `reversals`, `sessionMinutes`,
`movesMultipleOf005`, `smallestMove`, `bucketActiveMin[4]`, `bucketChanges[4]`.

The shredder's `TickModel.from(profile)` consumes a subset: the 4 `bucketChangesPerMin`
histograms, `absRetBps` (as the bps move model), `gapMs`, `reversalRate()` (→ reversal
probability), and `volPerChange.mean()`. Persisting the whole profile keeps all of this
plus the validation baseline.

### Exact fields to serialize per primitive type

- **`Histogram`**: `lo`, `binWidth`, `counts[]` (and its length), `underflow`, `overflow`,
  `n`, `sum`, `sumSq`, `min`, `max`. On load, reconstruct with the same geometry
  (`hi = lo + counts.length*binWidth`) then restore every scalar and bin exactly — a
  round-trip must be bit-for-bit identical so percentiles/samples are unchanged.
- **`Stats`**: `n`, `sum`, `sumSq`, `min`, `max`.
- **`PriceMoveProfile`**: `label`, `daysCount`, all counters above, plus each contained
  `Histogram`/`Stats` via their own `save`/`load`. (Per-day `sessOpen/High/Low/Close` are
  not meaningful on the aggregate — skip or write NaN.)

Use one self-describing text format (hand-rolled JSON or a simple `key=value` + arrays).
Include a `formatVersion` field so future changes are detectable. The file is small
(a few histograms of ≤ a few hundred bins) — readability beats compactness.

---

## Data sources & schemas (verified on the afl-bridge host)

> Paths below are what exist on **this** host. On **Host A**, point `source.db` at whatever
> path the real tick DB lives at — the table schema is the contract, not the path.

- **Tick data (Host A, for `learn`)** — table
  `nifty_ticks(ts_ms INTEGER, ltp REAL, vol INTEGER, oi INTEGER, sym TEXT)`. Use `sym='NIFTY-1'`.
  `ts_ms` = **UTC epoch-ms**. This is exactly what `TickStore` already reads. On this host a
  usable copy is `D:/Softwares/sqlite/nifty_ticks.db` (`NIFTY-1` ≈ 105,368 ticks); the path
  in `shred.properties` (`C:/novaquant/data/sqlite/nifty_ticks.db`) does not exist here.
- **1-min data (Host B, for `shred`)** — `D:/Softwares/sqlite/nifty.db`, table
  `candles(id, symbol TEXT, window_start INTEGER, window_end INTEGER, open, high, low, close REAL,
  volume INTEGER, tick_count INTEGER)`. Use `symbol='NIFTY'` (≈ 1,272,764 bars, 2011-01-03 →
  2024-12-31). `window_start` = **UTC epoch-ms at the start of the minute**.

Timezone: IST = UTC + 5:30 = `+19_800_000` ms (`TradingDay.IST_OFFSET_MS`). Both the tick and
candle timestamps are UTC epoch-ms, so they are consistent. Session filter constants live in
`PriceMoveAnalyzer.SESSION_START_SEC`/`SESSION_END_SEC` — reuse them, don't re-define.

---

## Implementation steps

### Step 1 — Persist the profile, and run `learn` first (TIME-SENSITIVE)

The tick DB may disappear; capture the intelligence before it does.

1. Add `save(Path)`/`load(Path)` to `Histogram` and `Stats` (`Stats.java`) and to
   `PriceMoveProfile` (`PriceMoveAnalyzer.java`), per the field lists above. Same-package
   access lets you read/write the private fields directly.
2. Add a `learn` mode to `ShredderMain`: read the tick DB exactly as the current pipeline
   does (discover days → `loadWindow` → `analyzer.analyze` → merge into the `LIVE` profile),
   then `profile.save(modelFile)`. Do **not** shred in this mode.
3. **Run `learn` against the real tick DB now** and confirm the saved file's reported numbers
   match the current run's `========== LIVE model ==========` block. Commit the model file
   into the repo (e.g. `model/nifty1.profile.json`) so the intelligence is preserved.
4. Add a round-trip test/check: `load` the file back, rebuild the profile, and verify a few
   percentiles (`absRetBps` p50/p90/p99, `changesPerMin` p50, `gapMs` p50) are identical.

### Step 2 — Add a 1-min candle reader (Host B input path)

1. Add a `CandleStore` (new class; **do not** overload `TickStore` — different schema). Open
   the candle DB `SQLITE_OPEN_READONLY`. Read `candles WHERE symbol=? [AND window_start range]`
   ordered by `window_start`.
2. Map each row to a `CandleBar`: `minuteStartMs = window_start`; derive the IST `LocalDate`
   and IST `minuteOfDay` from `window_start + IST_OFFSET_MS`; copy `open/high/low/close/volume`.
   Filter to the 09:15–15:30 IST session. Group rows by trading day (the shredder seeds its RNG
   per day — see `ShredderMain.shred(...)`).
3. Support an optional date range (`from`/`to`) and a bar cap so small test runs are cheap.
4. Leave `BarBuilder` and the closed-loop `selftest` path untouched.

### Step 3 — Wire the `shred` mode (Host B)

1. `shred` mode: `PriceMoveProfile.load(modelFile)` → `TickModel.from(profile)` →
   read bars via `CandleStore` → `TickShredder.shred(bar, rng)` per bar (reuse the per-day
   RNG seeding from `ShredderMain.shred`) → write ticks via `TickStore.write` to the output DB.
2. After writing, re-analyse the shredded ticks into a `shreddedProfile` and call
   `Validation.compare(loadedProfile, shreddedProfile)` — so validation still works on a host
   with **no tick DB present** (baseline comes from the frozen file).

---

## Config (`shred.properties`) — add these keys

```properties
# mode: selftest (default, current closed-loop) | learn (build+save model) | shred (use model on 1-min data)
mode = selftest

# learn mode reads source.db (the tick DB) and writes the model file
model.file = model/nifty1.profile.json

# shred mode reads 1-min candles from this DB/table
candle.db     = D:/Softwares/sqlite/nifty.db
candle.symbol = NIFTY
# optional date window for shred (IST calendar dates); empty = all
from =
to   =
```

Keep existing keys (`source.db`, `output.db`, `symbol`, `days`, `target.bars`) working for
`learn`/`selftest`.

---

## Constraints (do not violate)

1. **Preserve `TickShredder`'s hard constraints**: path starts at O, ends at C, touches H and
   L, never leaves `[L,H]`, snaps to the 0.05 grid, per-tick volume sums to the bar volume.
   Do not retune the model or change `REVERSAL_BOOST`.
2. **Serialization must round-trip exactly** — load then save reproduces identical statistics.
3. Source DBs opened `SQLITE_OPEN_READONLY` (a live gateway may be writing).
4. All output via SLF4J/Logback — no `System.out`. Match existing logging style (multi-line
   reports as one message).
5. Keep the single-package, co-located-types convention. No new Maven dependencies.

---

## Verification before declaring done

1. **learn**: saved model reproduces the current LIVE-model numbers; round-trip load is exact;
   model file committed.
2. **shred**: runs on a small candle subset (a few days via `from`/`to` or the bar cap) with
   **no tick DB on the host**, and writes ticks to the output DB.
3. **hard constraints**: re-aggregating the shredded output back into 1-min bars reproduces
   each source candle's O/H/L/C exactly and volume to the bar.
4. **validation**: `Validation.compare(loadedProfile, shreddedProfile)` reports all rows `ok`
   (or each `OFF` row is explained).
5. **docs**: update `README.md` and `shred.properties` to describe the three modes, the model
   file, and the candle reader.
