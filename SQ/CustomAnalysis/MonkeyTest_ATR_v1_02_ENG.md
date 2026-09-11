# Monkey Test ATR v1.02 - Custom Analysis Snippet

A Monte Carlo permutation test for StrategyQuant X (SQX) that measures a strategy's **geometric edge** — its ability to anticipate price displacement per unit of exposure — against randomness, without ever converting to money.

> ⚠️ **v1.02 results are not comparable with v1.00 ones.** This version's monkeys shuffle their direction sequence and replicate each direction's exposure separately, so the reference distribution is different — and more demanding — than the previous version's. Percentiles and Z-Scores computed with v1.00 must be recalculated; mixing them in the same table makes no sense. The rationale for both changes is in [5.15](#515-the-direction-sequence-is-shuffled-in-every-monkey) and [5.16](#516-exposure-is-replicated-per-direction-not-just-in-total).

---

## 1. What this test is and why it exists

### 1.1. The Monkey Test idea

The **Monkey Test** is a statistical validation method that determines whether a strategy's historical performance is a genuine edge (precise entries and exits) or simply luck: for example, having traded during a strong, sustained trend where any random entry would have made money.

It simulates "monkeys" that execute the same number of trades as the original strategy, with the same number of trades in each direction and the same total market exposure in each of them, but with **entries placed at random** throughout the analysed period and **in random order**. If the real strategy beats a high percentile of these randomised runs, it passes the test.

Put differently, the monkey is handed exactly the same "materials" the strategy had — how many times it traded in each direction and how long it was exposed in each — and is stripped of the only thing being measured: knowing *when* and *in what order* to use them.

### 1.2. The problem this test solves

The most natural way to measure that edge is **in money**: comparing the strategy's profit against the monkeys'. And with a fixed lot, comparing profits is exactly equivalent to comparing the sum of every trade's displacement in pips, because every pip is always worth the same.

And there lies the problem: **that sum does not weight all trades equally**.

If an instrument's relative volatility is roughly constant, its volatility **in absolute pips** grows with the price level. A 1% move is 100 pips when the instrument trades at 10,000 and 200 pips when it trades at 20,000. So on an instrument with price drift — a long-term index, gold, crypto, a growth stock — **trades executed in the high-price stretch weigh arithmetically far more in the total**, even though in relative terms they captured the same thing.

The consequence is a perfectly possible false positive: a strategy that got its timing right *only* in the high-price stretch, and did worse than the monkeys throughout most of the history, can still come out PASSED because that handful of trades dominates the sum.

Put differently, the test stops measuring this:

> **Edge**: the ability to generate returns per unit of market exposure, compared against randomness.

and starts measuring this instead, without declaring it:

> The ability to generate returns per unit of exposure, compared against randomness, **in periods of elevated instrument price**.

These are two different quantities. And the second is precisely the kind of market-regime-dependent advantage the Monkey Test declares it exists to detect.

### 1.3. The solution: normalising by ATR

This test divides each trade's displacement by the **ATR prevailing at the moment of its entry**, and never converts to money. The unit becomes *"how many ATRs of movement did this trade capture"*, which is dimensionless and comparable across the whole history.

A quick comparison of the three possible approaches:

| Approach | What it corrects | What it does not correct |
| :--- | :--- | :--- |
| **Monetary** | Nothing — it is the natural measure, and it accurately reconstructs the real money the strategy generated. | Subject to the price-level bias described above. It also forces fixed-lot and variable-lot strategies to be handled differently. |
| **Percentage** | The price level: normalising by the entry price equalises the weight of the same relative move whether the instrument is high or low. | The **local volatility regime**. Two entries at the same price, one in a consolidation and one in a volatility expansion, are treated identically even though the expected displacement is very different. It is an approximation, not a complete correction. |
| **ATR** (this test's) | Both at once: the ATR scales with the price level **and** with the volatility prevailing at that specific moment. It is the quantity that actually determines how much displacement to expect. | None of the above. In exchange, it stops expressing money (see [1.5](#15-scope-and-limitations)). |

As an additional benefit, **the strategy's money management stops mattering**. A test that measures in money needs to know whether the lot size is fixed or variable in order to convert displacement into euros correctly, applying a different formula in each case. Here nothing is converted, so the same formula serves any strategy whatever its sizing.

Normalising by volatility is also standard practice in quantitative research — "R multiples" or ATR multiples exist precisely to allow comparing trades across regimes, eras or different instruments — and it has a statistical advantage: with all the terms of the sum in a similar typical magnitude, the variance stabilises and the percentile and Z-Score become more interpretable.

### 1.4. The workflow that motivated this design

This test was not born as a generic tool, but to solve a problem within one specific development flow — its author's. Knowing it matters for two reasons: it explains several design decisions that would otherwise look arbitrary, and it delimits which situations the approach fits and which it does not.

That flow has two phases:

**Phase 1 — rule mining.** Rules with a statistical edge are searched for, still without Stop Loss or Take Profit. In SQX that forces the use of a **fixed lot**, because without an SL no other money management is available. It is a limitation of the tool, not a decision: that lot size will be discarded as soon as the rule advances. This test evaluates those rules.

**Phase 2 — complete strategies.** On the surviving rules, strategies are built by adding risk management, with an **ATR-based SL**, letting the optimiser search for the appropriate period over a wide range (ATR 50–350, or others depending on the case).

**Why that leads to measuring in ATR rather than money.** In phase 1 the fixed lot is a technical artifact, so measuring euros of a sizing that will be thrown away says little about what the rule will be worth later. And since the destination is an SL proportional to volatility, there is a usable correspondence: when position size is inversely proportional to a volatility measure — which is what happens when risking a fixed amount with `SL = k·ATR` — the money generated is proportional to the displacement normalised by that same measure. Measuring the geometric edge then anticipates phase 2's outcome **better than measuring euros of a lot that will not be used**.

> **An honest caveat, so as not to oversell the argument.** The ATR period used here (14 by default) need not match the one the phase 2 SL ends up using. If the SL turns out to be `k·ATR(200)`, the money is proportional to the normalised displacement multiplied by the ratio `ATR(14)/ATR(200)` — the volatility term structure, which fluctuates around 1 rather than being constant. The correspondence is therefore **approximate and strongly positive, not near-exact**.
>
> What is robust to that choice is **the bias correction, which is this test's reason for existing**: any ATR scales with the price level in the same way, so the bias towards high-price stretches disappears whichever period is used. The residual is a ratio of volatilities that reverts to the mean and does not drift systematically with price.

**Why the period is fixed and not tied to the future SL's**: in phase 1 no SL exists yet, so there is no "this strategy's ATR period" to match. And even if there were, normalising each strategy by its own period would make the metric non-comparable between strategies in the same databank, which is exactly what it is for. ATR(14) is the industry standard — Wilder's original — which makes the figure interpretable without explanation.

**When this approach is NOT the most appropriate one.** The test is designed and tested for the flow above; outside it, it is worth judging whether it is still the right tool:

* **The strategy is already complete, with its real risk management, and what you want to know is how much money it would have made against randomness.** That is a different question, and a test that measures in money answers it better.
* **The Stop Loss is not proportional to volatility** — fixed in pips, a percentage of price, or structure-based such as prior highs and lows. The correspondence between geometric edge and money weakens, because position size stops scaling with the ATR.
* **You genuinely trade a fixed lot in production**, not as a mining artifact. Then absolute pip displacement **is** what determines your P&L, and normalising by ATR deliberately measures something different from what you will be paid.
* **The instrument has no appreciable price drift** — for example a currency pair that has spent decades oscillating in a bounded range. There the bias this test corrects is small, and a monetary measure is both exact and directly interpretable.

### 1.5. Scope and limitations

It is worth being explicit about what this test does **not** guarantee:

- **It does not measure money.** Its figures are ATR multiples, not euros. Knowing how much a strategy would have made with its real money management requires a test that does measure in money; this project has a companion one, described in [section 6](#6-relationship-with-the-companion-monetary-test).
- **It validates directional edge at a dithered average duration.** A rule passing does **not** guarantee that a specific SL/TP will capture that edge: adding SL/TP changes *when* each trade exits, not only how it is sized. A too-tight TP can cut the gain before it develops; a too-wide SL can let losses run beyond what the average displacement suggests.
- **It does not replace validating the complete strategy in phase 2.** It is a "is this rule worth developing?" filter, not a final verdict.

---

## 2. Core Logic and How It Works

For each strategy in the databank, the snippet:

### 2.1. Reads the native historical data

It locates the symbol connection and timeframe from the main backtest and directly parses StrategyQuant's native BDF database file (`.dat`).

> **Important**: this snippet does **not** use SQX's backtest engine. The simulation is its own and operates on the raw candles from the `.dat`. This has a direct consequence: the spread is not applied automatically and must be applied explicitly (see [section 2.5](#25-spread-application)).

### 2.2. Filters by sample period

It applies the selected period (`FULL`, `IS`, `OOS`, `ISV`, or numbered segments such as `OOS1`, `OOS2`, `ISV1`) to restrict both the real trade set and the monkey simulations to that window. Orders are filtered directly by their SQX sample type, so each numbered segment stays genuinely isolated — a period is never silently substituted by another period's trades.

* If the selected period contains no trades, the strategy is flagged as `LOW TRADES`.
* If a non-existent numbered segment is specified (e.g. `OOS3` when only 2 OOS segments exist), it is flagged as `FAILED (INVALID PERIOD)`.
* **`FULL` also evaluates each period separately.** In addition to the aggregate, it probes `IS`, `OOS`, `ISV` and every existing `OOS1..10` / `ISV1..10` segment, running the test independently on each one. This does **not** multiply the cost by the number of periods: each period only simulates its own trades.
* **With a single OOS segment, `OOS` and `OOS1` are the same period** — SQX copies the stats of one onto the other. In that case the test is simulated once and **the result is published under both suffixes**, so the OOS and OOS1 columns show the same thing. It does not matter how you asked for the period: it holds whether you run with `FULL`, with `OOS`, or with `OOS1`. When the strategy does have several OOS segments, the aggregate and each numbered part are different periods and are **never** published across each other.

### 2.3. Computes the ATR

The ATR is computed by reproducing, bit for bit, the same algorithm SQX's internal ATR indicator uses, instead of an approximate textbook formula. This removes any doubt about whether the value matches what the user would see on an SQX chart: it is the same calculation, bar by bar, not an approximation that converges over time.

The formula has a single variable denominator, `D_i = min(i+1, P)` (where `i` is the candle index within the full history, starting at 0, and `P` is the period, 14 unless `ATRPeriod=N` is passed):

```
TR_0  = high_0 - low_0                                              (the first candle has no previous close)
ATR_0 = TR_0

TR_i  = max(high_i - low_i, |high_i - close_(i-1)|, |low_i - close_(i-1)|)   for i ≥ 1
ATR_i = (ATR_(i-1) * (D_i - 1) + TR_i) / D_i                                  for i ≥ 1
```

While `i+1` is smaller than `P` (that is, during the first `P-1` candles after the first one), `D_i` grows with every new candle, and the formula is, in practice, a cumulative average of the `TR` values seen so far. Once `i+1` reaches `P`, `D_i` freezes at `P` and the formula becomes the usual Wilder smoothing, with a fixed denominator. There is no separate seed phase: it is the same formula throughout, with a denominator that grows until it hits a ceiling and then stays constant.

Three important properties:

* **It is causal, no lookahead.** An entry on bar `i` happens at its *open*, so the only information available is that of the preceding bars. The ATR of the **last completed bar** is used, never that of the bar containing the entry instant — whose high and low are not yet known. This holds equally for the real trades and the monkeys': any asymmetry here would invalidate the comparison.
* **It is computed over the full history**, not over the period slice, so the `P`-bar warm-up is absorbed by the prior data and never affects a real evaluable period.
* **It is always computed on the main timeframe**, even with `Precision=M1` (see [2.6](#26-precision-and-atr-scale)).

### 2.4. Measures the normalised displacement

**The strategy's real trades** — their prices already incorporate the spread, because SQX fills at ask/bid:

```
direction   = short ? -1 : +1
atrDisp_i   = ((close price - open price) * direction) / ATR at entry
edgeReal    = sum of all atrDisp_i
```

**The monkeys' trades** — the `.dat` candles are raw prices, so the spread must be subtracted. Here `direction` is the one that trade drew **in that monkey's own shuffle** (see [2.8](#28-shuffles-the-direction-duration-pairs)), not that of the real trade occupying the same position:

```
atrDisp_k   = ((exit price - entry price) * direction - spread) / ATR at entry
edgeMonkey  = sum of all atrDisp_k
```

**The verdict is issued on the SUM**, not on any average: `edgeReal` is compared against the distribution of the N monkey sums.

> For the verdict, sum and average are **mathematically equivalent**, because each monkey executes exactly the same number of trades as the strategy. Dividing every value by the same `n` alters neither the percentile nor the Z-Score. The distinction only matters when comparing strategies against each other in the Databank, where `n` does vary — hence both figures are published.

### 2.5. Spread application

The spread is subtracted **in price units and before normalising**, because it is a price displacement: entering at ask and exiting at bid is exactly equivalent to penalising the displacement by one full spread.

It is always read from the strategy's configuration XML (`Data > Setups > Setup > Chart[@spread]`), in *points*, and converted to price by multiplying it by the instrument's `tickSize`. When there are several `Setup` elements (cross-checks on additional markets), it is matched by the `@symbol` attribute so as not to pick up the wrong market's spread.

### 2.6. Precision and ATR scale

`Precision=M1` makes the simulation run on one-minute candles, which increases the resolution at which durations are replicated. But **the ATR is still computed on the strategy's main timeframe**.

The reason is direct: an ATR(14) on M1 candles measures the volatility of the last 14 minutes, which has nothing to do with the scale an H4 strategy operates at. If the ATR changed with the precision, the same strategy would produce non-comparable figures depending on how the test was launched.

Internally, each candle of the simulation window is mapped to the last completed bar of the main chart. The practical consequence is that **the edge values are comparable between a run with `Precision=M1` and one without it**.

### 2.7. Computes the duration of the monkeys' trades

Duration is measured as a **fractional position on the bar axis**, not in calendar time:

```
position(t) = index of the bar containing t + fraction elapsed within that bar
duration    = position(close) - position(open)
```

By relying on the bar index, weekends and holidays do not count (indices are contiguous even when the calendar jumps), while the fractional term preserves the sub-bar resolution of the original backtest.

**The average is computed separately for each direction.** Long trades form one pool and short trades another, each with its own average duration. This is not a detail: averaging both together would erase the directional asymmetry of exposure and open up a false positive — see [5.16](#516-exposure-is-replicated-per-direction-not-just-in-total).

When a pool's average duration is not a whole number of bars, **deterministic dithering** is applied: if the average is 1.5625 H4 bars (6h15m), impossible to replicate with integer durations, then with 16 trades **9 trades of 2 bars and 7 of 1 bar** are assigned, summing to exactly 25 bars = 100 hours. Rounding to 2 bars would give 32 bars (+28% over-exposure); truncating to 1 would give 16 (-36%).

The trades that receive the extra bar are chosen **at random and without repetition in each monkey**, so that the longer-duration ones are spread across the whole period instead of clustering in one area. How many receive it is always exactly the same number, so total exposure is identical across all monkeys and differs from the original strategy's by **at most half a bar per pool over the entire period**.

### 2.8. Shuffles the direction-duration pairs

At this point the monkey has N trades, each with its direction and its duration. Before placing them in time, **the (direction, duration) pairs are fully shuffled in each monkey**, with a uniform random permutation.

Shuffling both values **together**, as an indivisible pair, is what makes:

* **The order completely random.** The sequence of longs and shorts through time differs in every monkey and bears no relation to the original strategy's.
* **The counts preserved.** A permutation cannot change how many elements of each kind there are: the number of long and short trades is exactly the strategy's.
* **Each direction's exposure preserved.** Since every duration travels attached to the direction of the pool that generated it, the total bars in long and the total in short remain the same however much the order changes.

### 2.9. Places entries without overlap

The N entries are distributed within the period window by randomly allocating the remaining slack into gaps between trades. This guarantees:

* **Zero overlap**: the minimum separation between consecutive entries is the real duration of the previous trade.
* **Everything inside the period**: the last trade ends within the evaluated window.
* **The same number of trades** as the original strategy, always.

### 2.10. Percentile-based statistical evaluation

It compares `edgeReal` against the distribution of the N monkeys. If it beats the defined percentile threshold, the strategy passes. The mean, standard deviation (n−1), Z-Score, rank percentile and median of the monkey distribution are computed.

---

## 3. How to Use It and Input Arguments

### Setup in StrategyQuant X

#### 1. Custom Analysis task

1. Add a **Custom Analysis** task to your project.
2. Under **Analysis type**, select **Per Strategy Analysis** (this enables multi-threaded computation using all available CPU cores).
3. Select **MonkeyTest_ATR_v1_02** as the analysis method in the dropdown.
4. In the **Input Args** field, configure your parameters as a comma-separated string: `numMonkeys,percentile,period`, plus any optional keywords you need.

#### 2. Builder Ranking and Retests tabs

Since the snippet uses the `Per Strategy Analysis` signature, you can also select **MonkeyTest_ATR_v1_02** in the **Custom Analysis** filter dropdown in:

* The **Ranking** tab of the Builder/Genetic configuration (to discard strategies automatically during generation).
* The **Retests** configuration (to discard strategies after retesting them on new data).

### Input Arguments

| Parameter | Default | Description | Example |
| :--- | :--- | :--- | :--- |
| **numMonkeys** | `500` | The number of randomised monkey simulations to run per strategy. | `1000` |
| **percentile** | `95.0` | The statistical confidence threshold. The strategy must beat this percentage of monkey runs to pass. | `70.0` |
| **period** | `FULL` | Sample window where the test runs: `FULL` (full backtest — **and additionally each period separately**), `IS`, `OOS`, `ISV`, or numbered sub-periods (`OOS1`..`OOS10`, `ISV1`..`ISV10`). This value also decides which period determines the PASSED/FAILED verdict. If the strategy has a single OOS segment, `OOS` and `OOS1` are interchangeable: whichever you ask for, the result shows up in both columns ([2.2](#22-filters-by-sample-period)). | `OOS2` |
| **ATRPeriod=N** | `14` | Optional, non-positional keyword. The ATR period used for normalising. It does not need touching in normal use: it exists so you can check that the ranking does not depend critically on the chosen period. Must be ≥ 2. | `500,70,OOS2,ATRPeriod=50` |
| **AutoDiscard** | *(absent)* | Optional keyword, detected as a case-insensitive substring anywhere in the string. Controls whether `filterStrategy` may tell the SQX engine to exclude the strategy when the test fails. **Absent by default: no strategy is ever excluded**, whether PASSED or FAILED. | `500,70,OOS2,AutoDiscard` |
| **Precision=M1** / **M1** | *(absent: main timeframe)* | Optional keyword. Runs the simulation on 1-minute candle data, which increases the resolution at which duration is replicated. **It does not affect the ATR scale** ([2.6](#26-precision-and-atr-scale)). If 1-minute data is unavailable, a warning is emitted and it falls back to the main timeframe. | `500,70,FULL,Precision=M1` |
| **SegmentDuration=N** | *(absent: off)* | Optional keyword (e.g. `SegmentDuration=300`). Splits the analysed period into continuous sub-segments sized to N days and runs the test on each one, additionally and independently of the main test. | `500,70,FULL,SegmentDuration=300` |
| **Debug** | *(absent)* | Optional keyword, same detection rules. Writes a diagnostic dump to `user/extend/Snippets/SQ/CustomAnalysis/MonkeyTest_ATR_v1_debug.log` (see [section 4](#diagnostic-dump-debug)). It dumps only the **first monkey** of each period, and never the `SegmentDuration` sub-segments. | `500,70,OOS2,Debug` |

> **Arguments this test does not accept:** `ResultsPluginCache` (see [section 4](#why-there-is-no-resultsplugin-cache)), `replicationMode` (`SLTP` / `AvgBars` / `IndivBars`) and `shiftingMode` (`Constant` / `Random`). All three belong to other Monkey Tests in this project. If you reuse the configuration of a task that passed them, the snippet detects them, ignores them, and emits a warning in the log explaining that they do not apply here.

*Input Args example:* `500,70,OOS2` runs 500 monkeys over the OOS2 trades with a 70% threshold. To test sensitivity to the ATR period: `500,70,OOS2,ATRPeriod=200`.

> **Note on Data Precision:** the precision option selected in SQX's general backtest configuration does **not** influence the data source this Custom Analysis reads. To force it to read the 1-minute data, it is **essential** to explicitly include the `Precision=M1` or `M1` keyword.

---

## 4. Expected Outputs

### Requirement: Install the Databank Columns

The Custom Analysis snippet only writes results into the strategy's metadata. To **display** those results as columns in the SQX databank, you must also install and enable the companion **Databank Column** snippets:

| File | Column in SQX | What it shows |
| :--- | :--- | :--- |
| `SQ/Columns/Databanks/MonkeyTestColumn.java` | `Monkey Test` (Text) | Percentile achieved or failure status |
| `SQ/Columns/Databanks/MonkeyTestZScoreColumn.java` | `Monkey Z-Score` (Decimal2) | Z-Score against the monkey distribution |
| `SQ/Columns/Databanks/MonkeyATRNormPipsProfit.java` | `Monkey ATR Normalized Pips Profit` (Decimal2) | The sum the verdict is issued on |
| `SQ/Columns/Databanks/MonkeyATREdgePerTrade.java` | `Monkey ATR Edge Per Trade` (Decimal2) | That sum divided by the number of trades |

**Installation steps:**

1. Make sure the column files are present in `user/extend/Snippets/SQ/Columns/Databanks/`.
2. Restart SQX (or force a snippet recompile) so the columns get registered.
3. In the Databank view, open the column selector and add the corresponding columns.

> Without the Databank Columns installed, the test still runs and filters strategies through the `FiltersResult` column, but individual results will not be visible in the databank grid.

### Why two of the columns have generic names

This project includes a second Monkey Test, `MonkeyTest_v2_00`, which measures the same thing but **in money** (see [section 6](#6-relationship-with-the-companion-monetary-test)). The `Monkey Test` and `Monkey Z-Score` columns **serve both**: the percentile and the Z-Score mean exactly the same thing in both — the strategy's position against the monkey distribution — and only the underlying magnitude that was compared changes, so duplicating them would add nothing.

**If you have run both tests on the same strategy, this one's result wins.** Neither clears the other's keys, so a databank may carry old results from the monetary test; with the reverse precedence, a freshly computed result would be hidden behind a stale one.

There is also a third column, `MonkeyMedianProfit`, which belongs to the monetary test **only** because it is in euros. It will show `N/A` if you have run only this test.

### Why both edge columns are needed

The number of trades varies a lot between strategies, and that makes the two figures tell different stories:

* **`Monkey ATR Normalized Pips Profit`** (the sum) is what maps to phase 2's money — with ATR-based sizing, total money is proportional to this sum. But it mixes edge quality with trading frequency: a mediocre rule with 233 trades can beat an excellent one with 37 on the total.
* **`Monkey ATR Edge Per Trade`** (the average) isolates edge quality per unit of exposure, so a good low-frequency rule is not buried. A `0.42` means each trade captured, on average, 0.42 times the volatility prevailing at its entry.

Read together they reveal where the edge comes from, which is exactly what is needed to decide which rules advance to phase 2.

### Published keys

Results are stored **per period**, using one key per suffix, so that several runs over different periods can coexist on the same strategy without overwriting each other:

| Key | Content |
| :--- | :--- |
| `MonkeyATRResult<suffix>` | Result for that period (see status list below). |
| `MonkeyATRPercentile<suffix>` | Rank percentile achieved against the monkey distribution, e.g. `85.20%`. |
| `MonkeyATRZScore<suffix>` | Z-Score of the real edge against the monkeys' mean/standard deviation. |
| `MonkeyATRNormPipsProfit<suffix>` | Sum of the ATR-normalized displacements — the verdict's magnitude. |
| `MonkeyATREdgePerTrade<suffix>` | That sum divided by the number of trades. |
| `MonkeyATRMedianNormPips<suffix>` | Median edge obtained by the monkeys in that period. |
| `MonkeyATRSpread<suffix>` | Spread effectively applied, in points. |
| `MonkeyATRExposureRatio<suffix>` | Total monkey exposure against the original strategy's (should be very close to 1). |
| `MonkeyATRPeriod<suffix>` | ATR period used in the calculation. |
| `MonkeyTest_SegCount_<PERIOD>` | Metadata: total number of sub-segments created for the period. |
| `MonkeyTest_SegDays_<PERIOD>` | Metadata: actual average duration in days of each sub-segment. |
| `MonkeyTest_SegTargetDays` | Metadata: target duration in days requested in Input Args. |

Valid suffixes: `_IS`, `_OOS`, `_ISV`, `_OOS1`..`_OOS10`, `_ISV1`..`_ISV10`, `_Full`, as well as the segmented suffixes `_Seg_<LABEL>_<J>`.

The columns resolve the suffix automatically from the **Databank sample type selector**. Resolution is **strict**: if a period has not been evaluated, the column shows `N/A` instead of falling back to another period's value. Numeric keys are only written when the test completed, so a `LOW TRADES` shows `N/A` and never a misleading `0.00`.

> With a single OOS segment you will see the same result duplicated under `_OOS` and `_OOS1`. **This is not an error**: they are the same stretch, and publishing it under both suffixes is what keeps one of the two columns from sitting at `N/A` ([2.2](#22-filters-by-sample-period)).

> **Absolute values are not comparable between runs with a different `ATRPeriod`**: a longer ATR is typically larger, so the same displacement produces fewer "captured ATRs". The percentile and the Z-Score do remain comparable, because both sides of the comparison use the same denominator. That is why the period used is published as a key.

### Monkey Test column statuses

* `PASSED`: the strategy's edge beat the defined percentile of the randomised runs.
* `FAILED`: the strategy did not beat the percentile threshold.
* `LOW TRADES`: the strategy has fewer than 20 trades in that period. It also appears when the period contains zero trades, which usually means the backtest was not configured with that sample period.
* `FAILED (INVALID PERIOD)`: a numbered segment was requested that does not exist on the strategy.
* `INSUFFICIENT SPACE`: the trades do not fit in the period without overlapping. Theoretically unreachable if the original strategy does not overlap trades; it indicates corrupt data.
* `FAILED (NO DATA)`: the symbol/timeframe `.dat` history file was missing.
* `ERROR`: an unexpected error, or degenerate data — for example a median ATR at the one-tick floor, which indicates padded or corrupt candles.

### Filters Result column

* Draws a **green PASSED** if the test passes (and no other filter failed).
* Draws a **red FAILED** if the strategy fails.
* **The verdict comes solely from the period requested in Input Args.** When `FULL` also computes the other periods, those additional results are published for inspection but never affect the verdict.

### Strategy exclusion (`AutoDiscard`)

Flagging a strategy as FAILED is purely visual — it never deletes anything by itself. Whether the SQX engine actually receives the instruction to exclude a failed strategy depends on the `AutoDiscard` keyword:

* **`AutoDiscard` absent (default): no strategy is ever excluded.** `filterStrategy` always returns `true` to the SQX engine. Every processed strategy stays where the task would have put it anyway, marked with its real result.
* **`AutoDiscard` present:** `filterStrategy` returns the real verdict, letting the SQX engine act accordingly.

This matters because SQX has **two independent mechanisms** that can exclude a strategy, and only one is affected by `AutoDiscard`:

1. **Copying between two different databanks**: the engine only copies to the output databank those strategies for which `filterStrategy` returned `true`.
2. **The native "Filter by results of custom analysis" checkbox**: only relevant when the input and output databanks are the **same**. It deletes failed strategies from that databank, but **only if `AutoDiscard` also makes `filterStrategy` return `false`**.

### Layout invariants (always on)

Four invariants on the entry layout are checked for every monkey. **There is nothing to enable**: they always run, silently, and only emit a `WARN` in the SQX log if one is violated.

| Invariant | What it guarantees |
| :--- | :--- |
| **A1** | Zero overlap: `entry[k] ≥ entry[k-1] + duration[k-1]` |
| **A2** | Everything inside the window: the first entry does not fall before `idxMin` and the last exit does not pass `idxMax` |
| **A3** | The dithering distributed exactly the planned bars **in each direction**: the sum of durations over the long trades matches what was planned for longs, and likewise for shorts |
| **A4** | The permutation preserved **how many** trades go in each direction |

**A3 and A4 are the ones watching that the shuffle breaks nothing**: A4 checks that there are still the same number of trades in each direction, and A3 that each direction still occupies the same market bars. Being checked per direction, A3 subsumes the global check: if both pools match, so does the total.

A `WARN` from A1 through A4 is **always a bug in the layout algorithm, never a market condition or an odd piece of data**. If one appears, that period's results are not trustworthy. Only the first violation per period is reported so as not to flood the log.

They run on every monkey and not only with `Debug` enabled, deliberately: if they were only checked in diagnostic mode, a violation in production would go unnoticed — which is exactly the scenario worth detecting.

### Diagnostic dump (`Debug`)

With the `Debug` keyword, `user/extend/Snippets/SQ/CustomAnalysis/MonkeyTest_ATR_v1_02_debug.log` is written, in two blocks per period:

1. **`ATR STATS`** — where the edge comes from and in what volatility regime: number of trades, total and per-trade edge, sum of absolute values, minimum/median/maximum ATR at the entries, **how many entries hit the one-tick floor** (non-zero means padded or corrupt candles), the spread applied, and the **correlation between the entry ATR and the normalised displacement** over the real trades. That correlation is diagnostic: a high absolute value warns that the edge is concentrated in a specific volatility regime, which is exactly the case where extrapolating to phase 2 is least reliable.

   It also includes **a `LONG` line and a `SHORT` line** with each direction's breakdown: how many trades it has, how many bars it actually occupied, its average and base duration, and its own `exposureRatio`. That ratio must come out very close to `1.0000` in both: it is the check that each direction's exposure was replicated. If a direction is flagged `[CLAMPED to 1 bar]`, its average duration fell below one bar and its exposure is inflated — the cue to consider `Precision=M1`.
2. **`LAYOUT (monkey #0)`** — the complete layout of the first monkey, one row per trade with `k`, **`dir`** (`L`/`S`), entry, duration, exit and **`gapToPrev`**. All trades are dumped rather than a sample, because the point is to audit two things by hand: the non-overlap (**any negative `gapToPrev` gives it away**) and the direction sequence, which must come out different on every run. The header summarises the counts and bars of each direction — which must match the real strategy's — and states whether A1 through A4 passed.

The dump goes to its own file rather than the SQX log because the latter reaches hundreds of MB per day and would become unusable. The writer is synchronised, since `Per Strategy Analysis` runs multi-threaded; each line carries the thread name, which is the same identifier that appears in the SQX log and lets you correlate the two.

> The file is opened in **append** mode and is neither rotated nor cleared automatically. Delete it between runs so diagnostics from different passes do not get mixed.

### Why there is no ResultsPlugin cache

This project includes a visualisation plugin, `DatabankMonkeyTest`, which draws the monkey distribution and its curves from a set of cache files. **This test does not write them**, for two reasons:

1. That plugin lives in `user/extend/ResultsPlugins/`, outside the Snippets folder and therefore outside the modifiable scope set by the project's Rule 1.
2. It is built entirely on monetary concepts: it reads an initial balance, draws equity curves and labels the axes as money. Feeding it ATR multiples would show actively misleading charts.

If visualisation for this test is wanted later, a new plugin would be needed — a separate task with its own authorisation.

---

## 5. Design Decisions and Why

This section documents why certain seemingly better alternatives were deliberately discarded. Without these reasons, it is foreseeable that someone will "fix" them in the future, breaking the methodology.

### 5.1. The ATR is causal: never the entry bar's

The ATR of the last bar **completed** before the entry is used, not that of the bar containing the entry instant. That bar includes its own high and low, which are not yet known at its open — using it would be lookahead.

It is not a minor detail: the displacement the trade is going to capture is correlated with that same bar's range, so normalising by it would put part of the answer into the denominator. And since the bias would affect the strategy and the monkeys differently (their entries fall on different bars), the comparison would be invalidated.

### 5.2. The exact SQX ATR indicator algorithm is replicated, not a textbook formula

There are several reasonable ways to compute a Wilder-smoothed ATR, and they all differ only in how they start up, during the first `P` candles, before enough True Range history has accumulated. A simple average of the first `P` candles used as a seed, for instance, gives a number close to what SQX produces but not bit-identical during that start-up; the difference decays exponentially with every new candle and becomes negligible after a few hundred bars, but it remains a real difference while it lasts.

To remove that doubt at the root instead of arguing that it converges, this Custom Analysis reimplements the exact algorithm of SQX's internal ATR indicator, including its particular way of starting up (see [2.3](#23-computes-the-atr)). The result is identical, bar for bar, to what SQX would compute with that same indicator over the same candles — not an approximation that gets closer over time, but the same calculation from the very first bar. That is a stronger guarantee than "checkable against the SQX chart after a few warm-up bars": it is the certainty that both numbers are, by construction, the same number on any bar, not only past some point.

### 5.3. The ATR always from the main timeframe, even when simulating at M1

It is tempting to compute the ATR over the same candles used for simulating. **That would be a mistake.** An ATR(14) on one-minute candles measures the volatility of the last 14 minutes; an H4 strategy does not operate at that scale, and normalising by that value would produce numbers unrelated to the edge being measured.

It would also make the same strategy give non-comparable figures depending on whether the test had been launched with `Precision=M1` or without it, when that option should only affect the resolution of the durations.

### 5.4. A one-tick floor, and why extreme values are NOT clipped

For an ATR of 14 candles to be exactly zero would require 14 consecutive candles with high equal to low and no gaps — it does not happen in real data. The practical risk is an **absurdly small** ATR from padded or corrupt data (some providers fill gaps with flat candles).

It matters because **each trade is divided by its own local ATR**. Unlike a global divisor — where an isolated extreme value gets diluted among all the others — here a single degenerate denominator can dominate the whole period's sum.

The defence has three levels: a hard floor at `tickSize` (below one tick that is not volatility, it is an artifact), a visible counter of how many entries hit it, and `ERROR` for the period if the **median** ATR is at the floor, because then the problem is systemic.

> **Deliberately discarded: winsorising or clipping extreme normalised displacements.** A low ATR followed by a large move **is not an error, it is information** — it is exactly the kind of trade the test should reward, because it captures a large displacement relative to the prevailing volatility. Clipping it would destroy the signal being measured. The floor removes only what is not volatility at all.

### 5.5. The spread applies even though the test handles no money

It might seem that, since no monetary amounts are involved, the spread has no place here either. **It does**: the spread is a price displacement, not a monetary amount, and in ATR units it is perfectly expressible.

And it must be kept for fairness: SQX's real order prices already incorporate the spread, whereas the `.dat` candles are raw. If it were not subtracted from the monkeys, they would trade frictionlessly while the real strategy did pay it, artificially biasing the verdict towards FAILED.

**Commissions and swaps, by contrast, are left out of the test**, and there the difference is real: they are monetary amounts, not price displacements, and have no representation in ATR units.

### 5.6. The spread must be applied explicitly

A Custom Analysis that instantiates SQX's `BacktestEngine` with a `ChartSetup` inherits spread handling for free, because the engine does it. This snippet does not: it runs its own simulation over the raw candles of the `.dat` file, and **nothing applies the spread unless the code itself does.** Worth keeping in mind so as not to end up counting it twice, or not at all.

### 5.7. Fixed spread from the XML, never the real per-candle spread

SQX data files can store the historical spread bar by bar, and the structure the snippet already uses to read candles exposes it. It would be a richer datum.

**It is not used.** The original strategy was backtested with the fixed value configured in the XML, which is the cost it actually incurred. Applying a different spread to the monkeys would put them trading under conditions the reference never had, breaking precisely the comparability this test needs to preserve.

### 5.8. Why ATR and not percentage

Percentage corrects the price level, which is half the problem. But two entries at the same price can have very different local volatilities — one in a consolidation, one in an expansion — and percentage treats them identically even though the expected displacement is not.

The ATR captures both at once, because it scales with the price level **and** with the prevailing volatility regime. Percentage would be an approximation; the ATR is the quantity that actually determines how much movement to expect.

### 5.9. The ATR period is fixed, not tied to the future SL's

In phase 1 no SL exists yet, so there is no "this strategy's period" to match. And even if there were, normalising each strategy by its own period would make the metric non-comparable between strategies in the same databank — which is exactly what it is for.

The period is left configurable via `ATRPeriod=N` not for daily use, but so you can check that the ranking stays stable when it changes, given that phase 2's SL will use a different one, unknown in advance.

### 5.10. The verdict is issued on the sum, not the average

Both are **mathematically equivalent** for the verdict, because each monkey executes exactly the same number of trades as the strategy: dividing every value by the same `n` alters neither the percentile nor the Z-Score.

The sum is used because it is the test's natural magnitude, and the average is published only for display. The distinction matters solely when comparing strategies against each other in the Databank, where the number of trades does vary.

### 5.11. Duration is measured as a fractional position on the bar axis

There are two wrong ways to measure a trade's duration, and both produce real biases:

* **Calendar time divided by the bar duration**: an H4 trade opened on Friday at 20:00 and closed on Monday at 04:00 is 56 hours, which that formula converts into 14 bars when only about 2 exist in the market. Weekends and holidays are counted as non-existent bars and inflate the target duration.
* **Difference of integer bar indices**: this quantises each trade separately before averaging. With an H4 strategy backtested at M1 precision, a 6h15m trade and a 7h50m trade would both give 1 bar; the average would come out at exactly 1.0 and **the dithering would have nothing to distribute**.

Quantisation must happen only once, on the average, never on each trade.

### 5.12. Monkeys do not close on Fridays

The original strategy may have a forced weekend close, and its trades already come shortened by it. Since the target average duration is computed over those trades, that effect is **already incorporated**. Reapplying the cut to the monkeys would penalise them a second time.

Furthermore, measuring in bars makes the weekend disappear from the computation: a trade of N bars is N market bars, whether or not it crosses Saturday in the calendar.

### 5.13. The dithering uses a random permutation with a fixed count

The extra bars are distributed at random among different trades in each monkey, instead of always falling on the first ones in the list. Assigning them by index would invariably cluster the longer-duration trades in the same area, and identically across all monkeys.

**It must not be replaced by an independent probability per trade.** It looks more random, but how many trades receive the extra bar would then follow a binomial distribution and total time exposure would vary from one monkey to another, losing the property that makes them comparable to each other and to the strategy.

> **Terminology note**: throughout this documentation "long" and "short" **always refer to the direction** of the trade. To talk about how much time it lasts the word is "duration" — never "long trade", which would be ambiguous.

### 5.14. The separation between entries is each trade's real duration

This is what mathematically guarantees the N trades fit within the period. If the original strategy does not overlap trades, the sum of their durations is less than the period length — but that guarantee is only inherited if the minimum separation is not rounded up uniformly.

### 5.15. The direction sequence is shuffled in every monkey

A monkey could be built preserving the order in which the strategy alternated longs and shorts, randomising only *when* each trade happens. **That is not what is done, and the difference matters.**

If the order were preserved, all 500 monkeys would share exactly the same directional sequence — always "long, long, short, long…" — differing only in the spacing. That makes them **non-independent** draws in the directional dimension: a real source of variation is frozen, the null distribution comes out artificially narrow, and both the percentile and the Z-Score turn out more lenient than they should be.

Shuffling the sequence in every monkey returns that variation to the experiment. The cost is nil (a permutation is O(n)) and what is gained is that the reference distribution genuinely represents "what would have happened with no skill at all", rather than "what would have happened while preserving the strategy's alternation pattern".

### 5.16. Exposure is replicated per direction, not just in total

The average duration is computed **separately** for long trades and for short ones, and each pool feeds only the trades of its own direction. Averaging them together would be simpler, but it opens up a serious false positive.

Consider a strategy with 50 long trades of 5 bars (250 bars) and 50 short trades of 20 bars (1,000 bars), over a market that fell during the period. With a global average of 12.5 bars, each monkey would end up with 625 bars of exposure in each direction. The real strategy captures the downward drift over 1,000 bars of short exposure; the monkeys, only over 625. The strategy comes out with "edge" when in reality it **was merely short for longer while the market fell**.

That is exactly the kind of market-regime-dependent advantage — not timing-dependent — that the Monkey Test declares it exists to detect. By replicating each direction's exposure separately, the monkey inherits the same directional asymmetry the strategy had, and the comparison goes back to isolating the only thing meant to be measured.

> A side effect worth knowing: by separating the pools, a direction with very brief trades can clamp its base duration to one bar and inflate its exposure, even when the global average would not have. The `Debug` dump flags it as `[CLAMPED to 1 bar]` on that direction's line.

### 5.17. The column never falls back to another period; the OOS ≡ OOS1 alias is the only exception, and it is not a fallback

When a period has not been evaluated, the column shows `N/A` **on purpose**. It might seem more convenient for it to show a nearby period's value, but that made every column end up showing the same number with nothing signalling it — an IS result presented as if it were OOS. Strict resolution is what guarantees that what you see in a column was computed on that period and not on another.

The only admitted equivalence is different in nature: **when the strategy has a single OOS segment, the aggregate OOS and OOS1 are not two similar periods, they are literally the same stretch** — SQX copies the stats of one onto the other. Nothing is being substituted there: one and the same result is published under the two names that same stretch goes by. If the strategy has several segments, the equivalence stops holding and the alias is not applied.

**Why it also applies when asking for the period directly**: the check lives where the periods to compute are decided, not inside the `FULL` branch. Previously it only ran when asking for `FULL`, so running with `OOS` left the OOS1 column at `N/A` despite that exact stretch having been computed — an `N/A` that looked like a test failure when it was really a key that was never written.

**Why it is not extended to ISV**: the `ISV` / `ISV1..10` family has the same shape, but whether SQX copies its stats the same way as with OOS is an assumption that has not been verified. Publishing a result under a suffix on the strength of an unverified equivalence is exactly what the paragraph above forbids, so it is left out until it can be confirmed on a project with real ISV.

---

## 6. Relationship with the companion monetary test

This project includes a second Monkey Test, `MonkeyTest_v2_00`, which answers a different question: **how much money would this strategy have made against randomness, with the money management it actually has?** It shares much of the simulation machinery with this one — duration measurement, dithering, non-overlapping entry placement — although, besides measuring in money, it builds its monkeys more conservatively: it averages duration without separating by direction, and it preserves the strategy's directional sequence instead of shuffling it.

The two coexist and are complementary: this one for phase 1 (pure rules, where the lot size is a technical artifact), the monetary one for validating complete strategies once they have their real risk management.

| Aspect | Monetary test (`MonkeyTest_v2_00`) | This test (`MonkeyTest_ATR_v1_02`) |
| :--- | :--- | :--- |
| Magnitude measured | Profit in money | ATR-normalized displacement (dimensionless) |
| Money management handling | Two different formulas, depending on whether the lot is fixed or variable | Just one: the money management plays no part |
| Calibration | Ratio `K` (euros per unit of displacement) | None: the magnitude is direct |
| Price-level bias | Present on instruments with drift | Corrected |
| Volatility regime | Not considered | Normalized trade by trade |
| Commissions and swaps | Subtracted | Not applicable (there is no money) |
| Spread | Subtracted from each simulated trade | Same, in price units before normalizing |
| ResultsPlugin cache | Available with `ResultsPluginCache` | Does not exist |
| `ATRPeriod` | Not applicable | Configurable, 14 by default |
| Percentile and Z-Score columns | Shared | Shared (the ATR key takes precedence) |
| Magnitude columns | `MonkeyMedianProfit` (euros) | `Monkey ATR Normalized Pips Profit` and `Monkey ATR Edge Per Trade` |
| Monkey durations | One global average for all trades | One average per direction, replicating each direction's exposure |
| Monkey direction sequence | The strategy's is preserved | Randomly shuffled in every monkey |
| Overlap-free layout and A1/A2 invariants | Identical | Identical (plus A3 per direction and A4) |
