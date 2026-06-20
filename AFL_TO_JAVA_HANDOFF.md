# Handoff: Replicate the AmiBroker AFL strategy in Java (tick-accurate execution)

**Audience:** the engineer/agent building the Java engine in the other codebase.
**Goal:** replace AmiBroker for the `breakout.afl` NIFTY strategy with a Java engine that (a) reproduces
the AFL's indicator/signal math exactly, and (b) fills entries/exits at **real tick prices** instead of
AmiBroker's bar-modelled prices — so the same code can run a backtest *and* go live.

Read this top to bottom before writing code. Reference the actual `breakout.afl` (the full strategy is
broken down in §5) — don't trust paraphrase over the source.

---

## 1. Why we're doing this (the core problem)

AmiBroker backtests on **15-minute OHLC bars**. It cannot see inside a bar, so it does **not** fill at a
traded price — it fills at a **level you computed** and assumes the bar traded cleanly through it. The
tail of `breakout.afl`:

```afl
BuyPrice   = ValueWhen(Buy,   iBuy2High);    // long entry  = the breakout LEVEL
ShortPrice = ValueWhen(Short, iShort2Low);   // short entry = the breakout LEVEL
SellPrice  = ShortPrice;                      // long EXIT   = the short level
CoverPrice = BuyPrice;                        // short EXIT  = the long level
```

Consequences:

1. **Entries** fill at the breakout level exactly — no gap, no slippage. Defensible as a stop-order
   model, but optimistic on gap-throughs.
2. **Stop-loss exits are mispriced.** When `StopLong = L <= entry - ATR14*1` fires, AFL still prices the
   exit at `SellPrice = ShortPrice` (a *stale* short breakout level), **not** the stop level. The
   recorded exit price is simply wrong for stop exits. This is the inaccuracy that motivated this work.

We now have **tick-level data** (synthetic for history, real when live), so we can fill at the actual
price the moment an order level is crossed. That is the whole point of the Java rewrite.

---

## 2. What's already been done (context)

- **Tick data exists.** A separate tool (`nqUtilities` shredder) converts 1-minute NIFTY OHLCV bars into
  synthetic tick streams that preserve each bar's O/H/L/C and volume exactly and reproduce measured
  live tick micro-behaviour. Output is AmiBroker-ASCII CSV and/or a SQLite `nifty_ticks` table.
  - 2011–2024 shredded: `F:/NIFTY Data/Tick Data/NIFTY_2011-01-01_2024-12-31.csv` (≈21.2M ticks).
  - The history ticks are **synthetic** (plausible intrabar path, not the true historical prints).
    Use them to validate the engine and stress-test slippage. **Live ticks (real) drive production.**
- **Tick data was imported into AmiBroker and backtested** at 15-min periodicity with `breakout.afl`.
  It runs and produces trades once two per-instrument settings are correct (see §10 gotchas):
  Analysis **Periodicity = 15-minute**, and the imported symbol's **Round Lot Size** set non-zero.
- **The tick-vs-1min backtests were compared** (full trade-by-trade diff, ~788 vs ~806 trades):
  ~89% of co-occurring trades are byte-identical, net P&L per contract within **~2.3%**, win rate
  ~28–29% both. Divergence is driven by (a) indicator **warm-up** (the tick DB started in 2019; the
  hourly ATR/PercentRank need ~3 months), and (b) the 0.05 tick-grid snapping nudging marginal crosses.
  Neither is a defect in the shredder — they tell us the tick data is a faithful stand-in.
- **An existing Java engine exists for reference**, in the `afl-bridge` project:
  `SwingSignalEngine` replicates a near-identical AFL (`swing_master.afl`) **field-by-field** against an
  AmiBroker reference log, at **1-minute** base periodicity. It is the best reference for the AFL
  primitives (Wilder ATR, PercentRank, LinearReg, hourly TimeFrame compression, Flip/ExRem/ValueWhen).
  ⚠️ It is **1-minute base**; this strategy runs at **15-minute base** (see §11). Reuse its *techniques*,
  not its bar geometry verbatim. `afl-bridge` also has a Python `intrabar_simulator` (worst-adverse-first
  / reach-check fill model) — that is essentially Layer 2 below; port its logic to Java.

---

## 3. Target architecture — two layers

```
  LAYER 1 — SIGNAL / LEVEL engine        (runs on 15-min bars)
     Reproduces breakout.afl exactly: VWAP, lvwap, ATR(1), ATR(14), hourly PRBATR/istate,
     15-min Highest(4)/Lowest(4), bands, regime, iBuy2High / iShort2Low, stop levels.
     OUTPUT per closed bar: the working ORDER LEVELS + regime state for the next bar.
                                  │
                                  ▼
  LAYER 2 — EXECUTION engine             (runs on TICKS)
     Holds resting stop orders at those levels, walks ticks in timestamp order, fills at the
     ACTUAL tick price when a level is crossed (gap-aware, first-touch wins).
     OUTPUT: real entry/exit prices, positions, P&L.
```

Key enabler: **every order is a resting stop at a level known *before* the bar opens.** `iBuy2High` /
`iShort2Low` come from a *prior* regime-transition bar; the protective stop (`entry − ATR14×1`) is known
once you're in. Nothing needs the current bar's close to *place* an order — which is exactly what makes
tick execution clean and live-deployable. (One timing subtlety in §8 — read it.)

---

## 4. The strategy in one paragraph

Regime is set by which band the **close** last crossed (above `upper` → long regime; below `lower` →
short regime). On a fresh regime flip, freeze the transition bar's high (long) / low (short) as the
**breakout level**. Enter when price breaks that level. Exit on either a reversal (opposite regime
breakout) or a protective stop at `entry ∓ ATR(14)×1`. It's **positional** — trades hold across days/weeks
(no intraday flatten). No new entries before **09:30** (`avoid_opening_vol`).

---

## 5. Full spec of `breakout.afl` (replicate exactly in Layer 1)

### 5.0 Data, periodicity, sizing
- **Strategy class: POSITIONAL, 15-minute only.** This strategy runs *exclusively* on 15-minute bars and
  holds positions across days/weeks. There is **no** 1-minute engine, **no** intraday flatten, and **no**
  EOD square-off. Do not build or compress to any other timeframe — 15-minute is the one and only bar.
- **Base periodicity: 15-minute bars**, session 09:15–15:30 IST. Build 15-min bars from ticks (or from
  1-min bars, then aggregate to 15-min). Bars are **start-stamped** (09:15 bar = TimeNum 091500). NSE
  late-close days run to 15:30. (VWAP still *resets each day* — that's a daily-reset measure, not an
  intraday strategy; the strategy itself is positional.)
- `SetTradeDelays(0,0,0,0)` — act on the signal bar (no delay).
- `SetPositionSize(1 * RoundLotSize, spsShares)` — fixed lot per trade (RoundLotSize shares). In Java,
  size is a config; it does not affect signal/level logic.

### 5.1 Fixed parameters (hard-coded in this version)
```
periods = 50                  // ATR period on hourly TF
ua,ub,uc,ud = 7, 40, 40, 110  // upper-band offsets per volatility tier
la,lb,lc,ld = 90, 80, 50, 70  // lower-band offsets per volatility tier
len1,len2,len3,len4 = 60,60,30,110          // LinearReg length, selected by regime
vol_thresh1 = 1.10
vol_thresh2 = 2.00            // = vol_thresh1 + 0.90
vol_thresh3 = 2.80            // = vol_thresh2 + 0.80
UseStopLoss = 1 ; StopLossATR = 1.0
marketstarttime = 091500
```

### 5.2 Intraday VWAP (resets daily at 09:15)
```
Bars_so_far_today = bars since start of day (1-based)
StartBar          = bar index where TimeNum()==091500 (first bar of the day)
average           = (H + L + C) / 3                      // typical price
TodayVolume       = sum of V from day start (min 1)
VWAP              = sum(average*V from day start) / TodayVolume   (Null before StartBar)
yVWAP             = VWAP of the last bar of the previous day (carried)
```

### 5.3 Hourly regime (PRBATR / istate) — compress 15-min → hourly
```
on hourly bars:
   PRBATR = PercentRank( LinearReg( ATR(periods), periods ), periods )   // periods=50
   state  = (PRBATR > prev PRBATR) OR (PRBATR >= 100) ? +1 : -1
expand back to 15-min:
   istate  = state  expanded to each 15-min bar of the hour
   iPRBATR = PRBATR expanded
```
- `ATR` is **Wilder** ATR. Match AmiBroker's float32 internal precision if you want exact parity
  (afl-bridge stores hourly ATR as `float`). `PercentRank(X,period)` compares vs `period` PRIOR values,
  divides by `period`. `LinearReg(X,len)` = endpoint of OLS line over `len` bars.
- **Hourly-from-15min boundary alignment is the single biggest parity risk — see §11.**

### 5.4 LinearReg of VWAP (`lvwap`)
```
len   = istate==-1 && iPRBATR==0   ? len1 :
        istate==-1 && iPRBATR!=0   ? len2 :
        istate==1  && iPRBATR==100 ? len3 : len4
lvwap = LinearReg(VWAP, len)        // endpoint of regression of VWAP over `len` 15-min bars
atrIntraday = ATR(1)                // 1-bar (15-min) ATR = current bar true range
```

### 5.5 15-minute Highest/Lowest(4) → volatility % → bands
```
on 15-min TF (this is the base TF here):
   _h4 = Highest(4) of last 4 closed 15-min highs
   _l4 = Lowest(4)  of last 4 closed 15-min lows
hold (expandLast) until next 15-min close:
   h_hour = _h4 ; l_hour = _l4
diff_hour = h_hour - l_hour
mid_hour  = (h_hour + l_hour)/2
pct_hour  = 100*diff_hour / max(1e-10, mid_hour)
flag      = (0 <= pct_hour <= vol_thresh3) ? 1 : 0

upper = lvwap + atrIntraday + (pct_hour<=t1 ? ua : pct_hour<t2 ? ub : pct_hour<t3 ? uc : ud)
lower = lvwap - atrIntraday - (pct_hour<=t1 ? la : pct_hour<t2 ? lb : pct_hour<t3 ? lc : ld)
        // t1,t2,t3 = vol_thresh1,2,3 ; note the boundary comparisons (<= vs <) exactly as in AFL
```

### 5.6 Regime, transition, breakout levels
```
iBuy   = BarsSince(Cross(lower, Low)) > BarsSince(Cross(C, upper)) ? 1 : 0
iShort = 1 - iBuy
iBuyPos   = Flip(iBuy,  iShort)     // latches long  until iShort fires
iShortPos = Flip(iShort, iBuy)      // latches short until iBuy  fires
iBuy2   = prev(iShortPos) && iBuyPos     // bar where regime flips short->long
iShort2 = prev(iBuyPos)   && iShortPos   // bar where regime flips long->short
iBuy2High  = newday ? max(Open, ValueWhen(iBuy2,  H)) : ValueWhen(iBuy2,  H)   // frozen long level
iShort2Low = newday ? min(Open, ValueWhen(iShort2, L)) : ValueWhen(iShort2, L) // frozen short level
```
- `Cross(a,b)` = a crosses **above** b this bar (`prev a<=prev b && a>b`). Note `Cross(C,upper)` uses
  **close**; `Cross(lower, Low)` is lower crossing above Low (i.e. Low dipping below lower).
- `Flip(set,reset)` latches 1 on `set`, 0 on `reset`, holds otherwise.
- `ValueWhen(cond, x)` = value of `x` at the most recent bar where `cond` was true.
- `newday` adjusts the level to the day's open on gaps.

### 5.7 Entries, exits, stops, dedup
```
avoid_opening_vol = TimeNum() < 093000        // no NEW entries before 09:30
Buy   = iBuyPos   && H > iBuy2High  && !avoid_opening_vol
Short = iShortPos && L < iShort2Low && !avoid_opening_vol

StopLong  = L <= ValueWhen(Buy,   iBuy2High)  - ATR(14)*StopLossATR
StopShort = H >= ValueWhen(Short, iShort2Low) + ATR(14)*StopLossATR
Sell  = (Short && !avoid_opening_vol) || StopLong     // exit long: reversal OR stop
Cover = (Buy   && !avoid_opening_vol) || StopShort    // exit short: reversal OR stop

Buy,Sell,Short,Cover = ExRem(...)   // remove repeated same-direction signals (applied twice in AFL)
```
- `ATR(14)` here is **15-min** ATR(14) (Wilder), separate from the hourly ATR(50).
- `ExRem(a,b)` removes consecutive repeats of `a` until `b` occurs.
- The AFL prices these at levels (see §1). **In Java, ignore AFL's price assignment — price from ticks.**

### 5.8 AFL primitives → Java stateful equivalents (build these once, unit-test them)
| AFL | Java state machine |
|---|---|
| `Ref(x,-1)` | previous bar's value |
| `BarsSince(cond)` | counter since cond last true |
| `Cross(a,b)` | `prevA<=prevB && a>b` |
| `Flip(set,reset)` | latch |
| `ExRem(a,b)` | suppress repeats |
| `ValueWhen(cond,x)` | last x where cond true |
| `Highest/Lowest(n)` | rolling window max/min |
| `Sum(x,n)` | rolling sum |
| `LinearReg(x,len)` | OLS endpoint over len |
| `PercentRank(x,period)` | % of prior `period` values below x |
| `ATR(n)` | Wilder ATR (seed = SMA of first n TRs incl. TR[0]=H-L) |
| `TimeFrameSet/Expand` | wall-clock bar compression + expandLast |

---

## 6. Layer 1 build steps (signal/level engine, bar-based)

1. **Bar builder.** From ticks (or 1-min bars) build **15-min bars** (start-stamped, midnight-aligned,
   session 09:15–15:30). Build **hourly bars** by compressing 15-min bars (see §11 for alignment).
2. **Primitives library** (§5.8) with unit tests against hand-computed cases.
3. **Indicators:** VWAP, yVWAP, ATR(1), ATR(14), hourly ATR(50)→LinearReg→PercentRank→istate/iPRBATR,
   15-min Highest/Lowest(4), pct_hour, bands (upper/lower), lvwap.
4. **Signal chain:** iBuy/iShort → Flip → iBuy2/iShort2 → iBuy2High/iShort2Low → Buy/Short/Stops → ExRem.
5. **Per-bar output object** exposing every AFL field (so it can be diffed) **plus** the order levels and
   regime state Layer 2 needs: `{istate, iPRBATR, len, lvwap, upper, lower, iBuy2High, iShort2Low,
   atr14, regimeLong/Short, inLongRegime/inShortRegime, newday, avoidOpeningVol}`.
6. **Validate Layer 1 against AmiBroker** by exporting the same per-bar fields from a `printVariableLogs`-
   style AFL on the *same* tick DB at 15-min periodicity, and diffing column-by-column (reuse the
   afl-bridge comparison approach). Target: istate/bands/levels match to float tolerance. Do **not**
   proceed to Layer 2 fills until the *levels* match.

---

## 7. Layer 2 build steps (tick execution engine)

Layer 2 consumes the tick stream and the per-bar levels from Layer 1, and produces fills. Build it in
**two modes** sharing one order-matching core:

- **(A) Backtest-parity mode** — confirm the signal on the 15-min bar close (as AFL does), then fill from
  the ticks *within that bar* at the order level / first touch. Use this to reconcile against AFL trade
  lists and to quantify the fill-price improvement.
- **(B) Live mode** — the production path. Regime/levels are taken from the **last closed bar**; resting
  stop orders are placed for the **next** bar; ticks fill them first-touch. No use of the current bar's
  close to confirm a fill (no look-ahead). This is what runs live and what you ultimately trust.

Steps:
1. **Tick feed abstraction** over the `nifty_ticks(sym, ts_ms, ltp, vol, oi)` schema (historical) and the
   live gateway (same shape). One interface, two implementations.
2. **Order book**: resting buy-stop / sell-stop / protective-stop with a level and a side.
3. **Matching core**: for each tick in timestamp order, check every working order; on the first tick that
   crosses a level, fill (gap-aware, §8). Update position; cancel/replace dependent orders.
4. **Per-15-min-boundary hook**: when a 15-min bar closes, call Layer 1, refresh regime + levels, and
   (re)arm the resting orders for the next bar.
5. **Trade ledger**: entry/exit time, level vs fill price, slippage, P&L, bars held — mirror the
   AmiBroker result columns so outputs are directly comparable.

---

## 8. Fill rules (the heart of the accuracy gain)

| AFL construct | Real order | Fill price from ticks |
|---|---|---|
| `Buy  = iBuyPos AND H>iBuy2High` | buy-stop @ `iBuy2High` (long regime, after 09:30) | first tick `≥ iBuy2High` → fill at **that tick** (= level, or higher on gap-up) |
| `Short = iShortPos AND L<iShort2Low` | sell-stop @ `iShort2Low` | first tick `≤ iShort2Low` → fill at that tick (or lower on gap-down) |
| `StopLong  = L≤entry−ATR14` | protective sell-stop @ `entry − ATR14×1` | first tick `≤ stop` → fill at that tick ← **AFL got this wrong; you fix it** |
| `StopShort = H≥entry+ATR14` | protective buy-stop @ `entry + ATR14×1` | symmetric |
| reversal (`Sell=Short`) | the opposite-side stop both closes and flips | one fill flips the position |

Decisions tick data forces (AFL hid them — pick explicitly and document):
1. **Gap fills.** If the first tick of a bar is already beyond the level, fill at that first tick, not the
   level. (This is where realistic slippage appears and where AFL was optimistic.)
2. **Same-bar priority.** If within one bar price touches both the protective stop and a reversal level,
   the **earlier tick wins** (you have timestamps — use them). Define the tie/ambiguity rule;
   "worst-adverse-first" is the conservative default and matches afl-bridge's intrabar simulator.
3. **Timing / look-ahead (critical).** AFL's regime `iBuyPos` depends on the *current bar's close*, but it
   fills intrabar — a mild look-ahead. In **live mode** you cannot do that: arm orders from the
   last-closed-bar regime and let ticks fill them. Expect live mode to differ slightly from AFL on bars
   that reverse by their close; that difference is AFL's look-ahead, not your bug. Keep both modes so you
   can measure it.

---

## 9. Data & schemas

- **Ticks (historical, synthetic):** SQLite `nifty_ticks(ts_ms INTEGER, ltp REAL, vol INTEGER, oi INTEGER,
  sym TEXT)`, `sym='NIFTY'` (or `NIFTY-1`). `ts_ms` = UTC epoch-ms. `vol` is per-tick incremental in the
  CSV; if you load the CSV columns are `Ticker,Date,Time(ms),Close,Volume`. IST = UTC+5:30
  (`+19_800_000` ms). Also available as the F:/ CSV noted in §2.
- **1-min candles (source of truth for bars):** SQLite `D:/Softwares/sqlite/nifty.db`,
  `candles(symbol, window_start, window_end, open, high, low, close, volume, tick_count)`,
  `symbol='NIFTY'`, `window_start` = UTC epoch-ms at minute start, 2011-01-03 → 2024-12-31.
- **Strategy source of truth:** `breakout.afl` (this is the exact logic to match; §5 is its breakdown).
- **Reference AmiBroker outputs** for validation: export per-bar fields and the trade list from AmiBroker
  on the *same* tick DB at 15-min periodicity.

---

## 10. Reusable assets (don't reinvent)

- **`afl-bridge` `SwingSignalEngine`** — field-by-field AFL parity engine (1-min base). Best reference for
  Wilder ATR seeding, PercentRank denominator, LinearReg, Flip/ExRem/ValueWhen, and the hourly TimeFrame
  edge cases (boundary alignment, late-close tail, circuit-breaker gaps). Read its `CLAUDE.md` notes.
- **`afl-bridge` `intrabar_simulator`** (Python) — the fill model (worst-adverse-first, reach-check,
  same-bar priority, tick_size=0.05). This is Layer 2's matching logic; port to Java.
- **`afl-bridge` comparison harness** (`compare_logs.py`) — pattern for diffing Java output vs an AFL log.

---

## 11. Risks & gotchas (read before coding)

1. **15-minute base, not 1-minute.** The reference engine is 1-min base; this strategy is 15-min base.
   `TimeFrameSet(in15Minute)` is a no-op on a 15-min base (it's the base TF); `inHourly` compresses **four**
   15-min bars. The afl-bridge `:14`-boundary logic was for 1-min→hourly and does **not** transfer
   verbatim. Re-derive how AmiBroker aligns hourly bars built from 15-min bars (midnight-aligned
   wall-clock; the first hourly bar of the session is partial: 09:15/09:30/09:45, then 10:00–10:45, …).
   Validate hourly istate against AmiBroker before trusting it.
2. **Warm-up.** Hourly ATR(50)→LinearReg(50)→PercentRank(50) needs a long warm-up (months). Start the
   engine from enough prior history (ideally 2011) or PRBATR/istate will be wrong early, cascading into
   wrong trades (this explained most of the tick-vs-1min divergence).
3. **Float32 vs double.** AmiBroker's hourly ATR is float32 internally; tiny ULP differences can flip
   istate on boundary bars. afl-bridge stores hourly ATR as `float` to match. Decide if you need exact
   parity or are content with "economically identical."
4. **0.05 tick grid.** Prices snap to 0.05. Use it consistently in level comparisons and fills.
5. **Positional, not intraday.** No EOD flatten — positions hold across days/weeks. Only stop/reversal
   exit. `avoid_opening_vol` blocks **new entries** before 09:30, not exits.
6. **Look-ahead in AFL regime** (see §8.3). Don't accidentally "fix" it in parity mode; do avoid it in
   live mode.
7. **Per-instrument AmiBroker settings** (for any AmiBroker re-validation): Periodicity = 15-minute,
   Round Lot Size set non-zero, "Time stamp of compressed bars" = Start of interval.

---

## 12. Verification plan

1. **Primitives**: unit tests for each §5.8 function vs hand-computed values.
2. **Indicators/levels (Layer 1)**: diff per-bar fields vs an AmiBroker `printVariableLogs` export on the
   same tick DB at 15-min. Gate: istate/bands/`iBuy2High`/`iShort2Low`/`atr14` match to tolerance.
3. **Fills (Layer 2, parity mode)**: run on the same tick DB; reconcile the trade list vs AmiBroker's.
   Expect entries to match closely and **stop-loss exits to differ on purpose** (yours are real, AFL's
   were stale) — quantify the difference and confirm it's in the expected direction.
4. **Live mode**: shadow-run on the live tick feed; confirm orders arm/fill sensibly with no look-ahead;
   compare to parity mode to size the look-ahead gap.
5. **Regression**: a fixed date range + golden trade ledger checked into the repo.

---

## 13. Implementation checklist (ordered)

- [ ] Bar builder: ticks → 15-min bars (start-stamped, session-filtered); 15-min → hourly (alignment!).
- [ ] AFL primitives library + unit tests (§5.8).
- [ ] Indicators: VWAP/yVWAP, ATR(1)/ATR(14), hourly ATR(50)→LinReg→PercentRank→istate/iPRBATR.
- [ ] 15-min Highest/Lowest(4), pct_hour, bands, lvwap, len selection.
- [ ] Signal chain: iBuy/iShort→Flip→iBuy2/iShort2→levels→Buy/Short/Stops→ExRem.
- [ ] Layer-1 per-bar output object (all AFL fields + order levels + regime).
- [ ] **Validate Layer 1 vs AmiBroker** (gate before fills).
- [ ] Tick feed abstraction (historical SQLite + live gateway, one interface).
- [ ] Order book + matching core (buy-stop/sell-stop/protective-stop, first-touch, gap-aware).
- [ ] Per-15-min-boundary hook: refresh levels, re-arm orders.
- [ ] Trade ledger mirroring AmiBroker columns.
- [ ] Backtest-parity mode → reconcile trade list; live mode → shadow run.
- [ ] Golden regression range checked in.

---

### TL;DR for the implementer
Build the indicator/signal math from §5 exactly (validate against AmiBroker), then **throw away AFL's
fill prices** and fill from ticks using §8. Entries become real stop fills; stop-loss exits get a *real*
price instead of AFL's stale level. Keep a parity mode (to trust the math) and a live mode (to trade).
