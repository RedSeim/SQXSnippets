# Monkey Test v2.00 - Custom Analysis Snippet

A Monte Carlo permutation test designed for StrategyQuant X (SQX) that evaluates the robustness of a trading strategy's entry and exit edge against random market timing.

---

## 1. Summary and Purpose

The **Monkey Test** is a statistical validation method used to determine whether a strategy's historical performance is the result of a genuine edge (precise entries and exits) or simply statistical luck (e.g., trading during a strong, sustained trend where any random entry would have made money).

It simulates "monkeys" that execute the same number of trades as the original strategy, with the same average duration per trade and the same direction, but with **entries placed at random** throughout the analysed period. If the real strategy beats a high percentile of these randomised runs, it passes the test.

Version 2.00 replaces v1.00's trade-by-trade calculation with a **single calibration per period**: it measures how much one unit of price displacement is worth monetarily in the original strategy, and each monkey's profit is obtained by multiplying that factor by its total accumulated displacement. The result is a simpler, faster calculation with less surface for error.

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
* **`FULL` also evaluates each period separately.** In addition to the aggregate, it probes `IS`, `OOS`, `ISV` and every existing `OOS1..10` / `ISV1..10` segment, running the test independently on each one. This does **not** multiply the cost by the number of periods: each period only simulates its own trades. When the strategy has a single OOS segment, `OOS` and `OOS1` are the same period (SQX copies its stats), so it is simulated once and published under both suffixes.

### 2.3. Determines the calculation mode from the Money Management

The snippet works in two distinct ways depending on the money management method declared in the original strategy:

| Mode | When it applies | Unit of measure |
| :--- | :--- | :--- |
| **A** | Money management is **Fixed Size** (constant lot size) | Absolute price displacement |
| **B** | Any other money management (**Fixed Amount**, Risk %, etc.: variable lot size) | Percentage displacement relative to the entry price |

The mode is read from the strategy's configuration XML (`RiskMoneyManagement > MoneyManagement > Method[@use="true"]`). **The declared money management rules, with no degradation**: if `FixedSize` is declared, MODE A is used even if the observed lot sizes vary. Only if the XML cannot be parsed does it fall back to empirically checking whether all lot sizes are equal.

### 2.4. Calibrates the monetary ratio (the heart of v2.00)

The strategy's real trade set is traversed once and the following is computed:

```
K = sum(|gross profit of each trade|) / sum(|displacement of each trade|)
```

Where gross profit is the P&L **without commissions or swaps**, and displacement is:

* In **MODE A**: the signed price difference, `(close price - open price) x direction`.
* In **MODE B**: that same difference expressed as a percentage of the trade's entry price. This is not the percentage profit relative to account capital, but the percentage units of the asset's price displacement on each trade.

`K` is therefore the **average gross monetary value per unit of displacement** (per pip in MODE A, per 1% in MODE B). It is a **single constant per period**, applied equally to all monkeys and all their trades.

### 2.5. Spread application

The original strategy's order prices already incorporate the spread (SQX opens at ask and closes at bid), whereas the candles in the `.dat` file are raw prices. To make both sides comparable, **the spread is subtracted from each simulated trade's displacement**. Mathematically this is equivalent to entering at ask and exiting at bid, but in a single subtraction.

The spread is always read from the strategy's configuration XML (`Data > Setups > Setup > Chart[@spread]`), in *points*, and converted to price by multiplying it by the instrument's `tickSize`. When there are several `Setup` elements (cross-checks on additional markets), it is matched by the `@symbol` attribute so as not to pick up the wrong market's spread.

### 2.6. Computes the duration of the monkeys' trades

Duration is measured as a **fractional position on the bar axis**, not in calendar time:

```
position(t) = index of the bar containing t + fraction elapsed within that bar
duration    = position(close) - position(open)
```

By relying on the bar index, weekends and holidays do not count (indices are contiguous even when the calendar jumps), while the fractional term preserves the sub-bar resolution of the original backtest.

When the resulting average duration is not a whole number of bars, **deterministic dithering** is applied: if the average is 1.5625 H4 bars (6h15m), impossible to replicate with integer durations, then with 16 trades **9 trades of 2 bars and 7 of 1 bar** are assigned, summing to exactly 25 bars = 100 hours. Rounding to 2 bars would give 32 bars (+28% over-exposure); truncating to 1 would give 16 (-36%).

The trades that receive the extra bar are chosen **at random and without repetition in each monkey**, so that long trades are spread across the whole period instead of clustering in one area. The number of long trades is always exactly the same, so total exposure is identical across all monkeys and differs from the original strategy's by **at most half a bar over the entire period**.

### 2.7. Places entries without overlap

The N entries are distributed within the period window by randomly allocating the remaining slack into gaps between trades. This guarantees:

* **Zero overlap**: the minimum separation between consecutive entries is the real duration of the previous trade.
* **Everything inside the period**: the last trade ends within the evaluated window.
* **The same number of trades** as the original strategy, always.

### 2.8. Computes each monkey's profit

```
gross profit = K x (monkey's total accumulated displacement)
net profit   = gross profit - the original strategy's total commissions and swaps
```

Since every monkey executes the same number of trades as the original strategy, and the purpose of the test is to measure the edge against randomness without costs influencing the result, each monkey's commissions and swaps are assumed to be exactly the same as the original strategy's.

### 2.9. Percentile-based statistical evaluation

It compares the original strategy's net profit against the distribution of the N monkeys. If the original profit is greater than the defined percentile threshold, the strategy passes.

The original strategy's profit is **reconstructed with the same formula** as the monkeys (`K x total displacement - commissions`), so that any approximation error affects both sides equally. In MODE A that reconstructed value matches SQX's real Net Profit to the cent.

---

## 3. How to Use It and Input Arguments

### Setup in StrategyQuant X

#### 1. Custom Analysis task

1. Add a **Custom Analysis** task to your project.
2. Under **Analysis type**, select **Per Strategy Analysis** (this enables multi-threaded computation using all available CPU cores).
3. Select **MonkeyTest_v2_00** as the analysis method in the dropdown.
4. In the **Input Args** field, configure your parameters as a comma-separated string: `numMonkeys,percentile,period`, plus any optional keywords you need.

#### 2. Builder Ranking and Retests tabs

Since the snippet uses the `Per Strategy Analysis` signature, you can also select **MonkeyTest_v2_00** in the **Custom Analysis** filter dropdown in:

* The **Ranking** tab of the Builder/Genetic configuration (to discard strategies automatically during generation).
* The **Retests** configuration (to discard strategies after retesting them on new data).

### Input Arguments

| Parameter | Default | Description | Example |
| :--- | :--- | :--- | :--- |
| **numMonkeys** | `500` | The number of randomised monkey simulations to run per strategy. | `1000` |
| **percentile** | `95.0` | The statistical confidence threshold. The strategy must beat this percentage of monkey runs to pass. | `99.0` |
| **period** | `FULL` | Sample window where the test runs: `FULL` (full backtest — **and additionally each period separately**), `IS`, `OOS`, `ISV`, or numbered sub-periods (`OOS1`..`OOS10`, `ISV1`..`ISV10`). This value also decides which period determines the PASSED/FAILED verdict. | `OOS2` |
| **ResultsPluginCache** | *(absent)* | Optional, non-positional keyword — detected as a case-insensitive substring anywhere in the string. When present, the snippet writes the cache artifacts (CSV + meta.json) described in [section 4](#4-expected-outputs). When absent, **no cache file is written**. | `500,95,OOS2,ResultsPluginCache` |
| **AutoDiscard** | *(absent)* | Optional keyword, same detection rules. Controls whether `filterStrategy` may tell the SQX engine to exclude the strategy when the test fails. **Absent by default: no strategy is ever excluded**, whether PASSED or FAILED. | `500,95,OOS2,AutoDiscard` |
| **Precision=M1** / **M1** | *(absent: main timeframe)* | Optional keyword. Runs the simulation on the 1-minute historical candle data (`SYMBOL_M1.dat` or `SYMBOL_1M.dat`), which increases the resolution at which trade durations are replicated. If 1-minute data is unavailable, a warning is emitted and it automatically falls back to the main timeframe. | `500,95,FULL,Precision=M1` |
| **SegmentDuration=N** | *(absent: off)* | Optional keyword (e.g. `SegmentDuration=300`). Splits the analysed period into continuous sub-segments sized to N days and runs the test on each one, additionally and independently of the main test. | `500,95,FULL,SegmentDuration=300` |

> **Arguments removed relative to v1.00:** `replicationMode` (`SLTP` / `AvgBars` / `IndivBars`) and `shiftingMode` (`Constant` / `Random`) **no longer exist**. v2.00 always simulates with dithered average duration and random non-overlapping entries. If a task inherited from v1.00 still passes them in positions 4 and 5, the snippet detects them, ignores them, and emits a warning in the log explaining that they no longer apply.

*Input Args example:* `500,95,OOS2` runs 500 monkeys over the OOS2 trades. To run the test with 1-minute intrabar precision: `500,95,FULL,Precision=M1`. To enable the time-segmented test: `500,95,FULL,SegmentDuration=300`.

> **Note on Data Precision:** the precision option selected in SQX's general backtest configuration does **not** influence the data source this Custom Analysis reads. By default it will always read the strategy's main timeframe file. To force it to read the 1-minute data, it is **essential** to explicitly include the `Precision=M1` or `M1` keyword.

---

## 4. Expected Outputs

### Requirement: Install the Monkey Test Databank Columns

The Custom Analysis snippet only writes results into the strategy's metadata. To **display** those results as columns in the SQX databank, you must also install and enable the companion **Databank Column** snippets:

- **Files**: `SQ/Columns/Databanks/MonkeyTestColumn.java`, `MonkeyTestZScoreColumn.java` and `MonkeyMedianProfit.java`
- **Column names in SQX**: `Monkey Test` (Text), `Monkey Z-Score` (Decimal2) and `MonkeyMedianProfit` (Decimal2)

**Installation steps:**

1. Make sure the column files are present in `user/extend/Snippets/SQ/Columns/Databanks/`.
2. Restart SQX (or force a snippet recompile) so the columns get registered.
3. In the Databank view, open the column selector and add the corresponding columns.

> Without the Databank Columns installed, the test still runs and filters strategies through the `FiltersResult` column, but individual results will not be visible in the databank grid.

### Published keys

Results are stored **per period**, using one key per period suffix, so that several runs over different periods can coexist on the same strategy without overwriting each other:

| Key | Content |
| :--- | :--- |
| `MonkeyTestResult<suffix>` | Result for that period (see status list below). |
| `MonkeyTestPercentile<suffix>` | Rank percentile achieved against the monkey distribution, e.g. `85.20%`. |
| `MonkeyTestZScore<suffix>` | Z-Score of the real profit against the monkeys' mean/standard deviation. |
| `MonkeyTestMedianProfit<suffix>` | Median Net Profit obtained by the monkeys in that period. |
| `MonkeyTestRealProfit<suffix>` | The strategy's profit reconstructed with the same formula as the monkeys. |
| `MonkeyTestMode<suffix>` | Calculation mode applied: `A (FixedSize)` or `B (Variable)`. |
| `MonkeyTestSpread<suffix>` | Spread effectively applied, in points. |
| `MonkeyTestExposureRatio<suffix>` | Total monkey exposure against the original strategy's (should be very close to 1). |
| `MonkeyTest_SegCount_<PERIOD>` | Metadata: total number of sub-segments created for the period. |
| `MonkeyTest_SegDays_<PERIOD>` | Metadata: actual average duration in days of each sub-segment. |
| `MonkeyTest_SegTargetDays` | Metadata: target duration in days requested in Input Args. |

Valid suffixes: `_IS`, `_OOS`, `_ISV`, `_OOS1`..`_OOS10`, `_ISV1`..`_ISV10`, `_Full`, as well as the segmented suffixes `_Seg_<LABEL>_<J>`.

The columns resolve the suffix automatically from the **Databank sample type selector**. Resolution is **strict**: if a period has not been evaluated, the column shows `N/A` instead of falling back to another period's value. Numeric keys are only written when the test completed, so a `LOW TRADES` shows `N/A` and never a misleading `0.00`.

> **Auditing the calculation**: no key with SQX's Net Profit is published because the native **Net Profit** column already responds to the sample type selector. Placing it next to `MonkeyTestRealProfit` directly verifies the quality of the reconstruction: in MODE A both must match to the cent.

### Monkey Test column statuses

* `PASSED`: the strategy's net profit beat the defined percentile of the randomised runs.
* `FAILED`: the strategy did not beat the percentile threshold.
* `LOW TRADES`: the strategy has fewer than 20 trades in that period. It also appears when the period contains zero trades, which usually means the backtest was not configured with that sample period.
* `FAILED (INVALID PERIOD)`: a numbered segment was requested that does not exist on the strategy.
* `INSUFFICIENT SPACE`: the trades do not fit in the period without overlapping. Theoretically unreachable if the original strategy does not overlap trades; it indicates corrupt data.
* `FAILED (NO DATA)`: the symbol/timeframe `.dat` history file was missing.
* `ERROR`: an unexpected runtime error occurred.

### Filters Result column

* Draws a **green PASSED** if the test passes (and no other filter failed).
* Draws a **red FAILED** if the strategy fails.
* **The verdict comes solely from the period requested in Input Args.** When `FULL` also computes the other periods, those additional results are published for inspection but never affect the verdict.

### Strategy exclusion (`AutoDiscard`)

Flagging a strategy as FAILED is purely visual — it never deletes anything by itself. Whether the SQX engine actually receives the instruction to exclude a failed strategy depends on the `AutoDiscard` keyword:

* **`AutoDiscard` absent (default): no strategy is ever excluded.** `filterStrategy` always returns `true` to the SQX engine. Every processed strategy stays where the task would have put it anyway, marked with its real result.
* **`AutoDiscard` present:** `filterStrategy` returns the real verdict, letting the SQX engine act accordingly.

This matters because SQX has **two independent mechanisms** that can exclude a strategy, and only one is affected by `AutoDiscard`:

1. **Copying between two different databanks**: the engine only copies to the output databank those strategies for which `filterStrategy` returned `true`. Without `AutoDiscard`, a task copying between different databanks would keep every strategy, marked with its result.
2. **The native "Filter by results of custom analysis" checkbox**: only relevant when the input and output databanks are the **same**. It deletes failed strategies from that databank, but **only if `AutoDiscard` also makes `filterStrategy` return `false`**.

### ResultsPlugin Cache Files (schema v4)

When the `ResultsPluginCache` keyword is present, the snippet writes two artifacts per strategy to `user/extend/ResultsPlugins/DatabankMonkeyTest/cache/`:

* **`[StrategyName]_monkey_simulation_data.csv`** — up to 50 representative monkey equity curves, selected from the full distribution (the lowest, the highest, and up to 48 intermediate ones evenly spaced by percentile). Each row is a monkey's complete balance path: `monkey_id;b0;b1;...;bT`.
* **`[StrategyName]_monkey_simulation_data.meta.json`** — all the scalar KPIs plus the complete array of monkey profits.

Fields added in schema v4 relative to v3: `mode` (calculation mode applied), `ratioPerUnit` (the value of `K`), `spreadPoints` and `exposureRatio`. All v3 fields are retained so the plugin keeps reading the cache.

> **One cache pair per strategy — only for the requested period.** The ResultsPlugin locates these files solely by the strategy name, so only one pair per strategy can exist. When `FULL` evaluates all periods, the cache is written **exclusively for the period requested in Input Args**. The `period` field inside `meta.json` always identifies which one it corresponds to.

---

## 5. Design Decisions and Why

This section documents why certain seemingly better alternatives were deliberately discarded. Without these reasons, it is foreseeable that someone will "fix" them in the future, breaking the methodology.

### 5.1. K is a single constant, not a ratio inherited per trade

It is technically feasible to value monkey trade `k` with the ratio of the original strategy's trade `k` (`sum(pk x Kk)` instead of `K x sum(pk)`), at the same computational cost, and that would reduce the approximation error in MODE B.

**It is deliberately discarded.** It would make two identical displacements worth different amounts depending on which original trade they happened to be paired with, so that a monkey landing on a trade with an abnormally distant stop loss would see its result dominated by it. That injects variance originating from the strategy's risk management into the distribution, when what the test measures is exclusively the quality of the *timing*. With a global `K`, every trade of every monkey carries the same monetary weight across the period, and the dispersion reflects only the randomness of the entries.

### 5.2. The calibration is weighted by magnitude, not by the net

`K` could be computed as `total gross profit / total displacement`. When the lot size is fixed, that formula and the magnitude-weighted one give **exactly the same number**, because for each trade `profit = K x displacement` holds with the same `K` for winners and losers.

Example with a fixed lot and a real `K` of 10 EUR/pip:

| Trade | Pips | Gross EUR |
| :--- | :--- | :--- |
| 1 | +50 | +500 |
| 2 | -30 | -300 |
| 3 | +20 | +200 |
| 4 | -38 | -380 |
| **Net** | **+2** | **+20** |

Net over net: `20 / 2` = 10 EUR/pip. Magnitude-weighted: `1380 / 138` = 10 EUR/pip. Identical.

The difference appears when the net displacement approaches zero. If trade 4 loses 40 pips instead of 38, the net in pips is 0 and so is the net in euros: the net-over-net formula would give `0/0` and the strategy would be unevaluable. The magnitude-weighted one gives `1400 / 140` = 10 EUR/pip, correct as always.

**Each trade's sign is not lost**: it lives in each monkey's accumulated displacement, not in `K`. `K` is a unit conversion factor and is positive by nature; a monkey accumulating negative displacement obtains a negative profit.

### 5.3. The declared money management rules, with no degradation to MODE B

If the XML declares `FixedSize` but the observed lot sizes vary, the snippet logs a warning but **keeps MODE A**. Choosing the wrong mode is not neutral.

MODE B normalises each displacement by the entry price, so on a fixed-lot strategy it over-weights trades opened at low prices. Example with a fixed lot at 10 EUR/point:

| Trade | Entry price | Displacement | EUR | % |
| :--- | :--- | :--- | :--- | :--- |
| 1 | 10,000 | +100 pts | +1,000 | 1.0% |
| 2 | 20,000 | +100 pts | +1,000 | 0.5% |

`K` in MODE A = 2000/200 = 10 EUR/point. `K` in MODE B = 2000/1.5 = 1,333.33 EUR/%.

A monkey placing both its trades near 10,000, both +100 points, would obtain **2,000 EUR** in MODE A (correct) and **2,666.67 EUR** in MODE B: a 33% overestimate. The divergence grows with how much the asset's price has drifted during the period.

### 5.4. The spread must be applied explicitly

Unlike the Synthetic CV Custom Analysis — which instantiates SQX's `BacktestEngine` with a `ChartSetup` and therefore inherits the spread for free — this snippet runs its own simulation over the raw candles of the `.dat` file. **Nothing applies the spread unless the code itself does.** Worth keeping in mind so as not to end up counting it twice, or not at all.

### 5.5. The spread is subtracted from the monkey, not added to the original strategy

SQX order prices already incorporate the spread; the `.dat` candles do not. Levelling both sides admits two symmetric solutions: subtract it from the monkey, or give it back to the original strategy.

It is subtracted from the monkey because that replicates the real cost of trading. Adding it to the original would measure a gross edge that no trader can capture in practice.

### 5.6. Fixed spread from the XML, never the real per-candle spread

SQX data files can store the historical spread bar by bar, and the structure the snippet already uses to read candles exposes it. It would be a richer datum.

**It is not used.** The original strategy was backtested with the fixed value configured in the XML, which is the cost it actually incurred. Applying a different spread to the monkeys would put them trading under conditions the reference never had, breaking precisely the comparability this test needs to preserve. The richer datum would here be the wrong datum.

### 5.7. The verdict is issued on the reconstructed profit

The original strategy's profit is recomputed with the same formula as the monkeys', instead of taking the Net Profit SQX reports. That way, any approximation error affects both sides of the comparison equally.

In MODE A the reconstructed value matches the real Net Profit to the cent, because `K` is exact when the lot size is constant. Placing `MonkeyTestRealProfit` next to the native **Net Profit** column allows auditing it at a glance: a divergence in MODE A reveals a misdeclared money management.

### 5.8. Duration is measured as a fractional position on the bar axis

There are two wrong ways to measure a trade's duration, and both produce real biases:

* **Calendar time divided by the bar duration**: an H4 trade opened on Friday at 20:00 and closed on Monday at 04:00 is 56 hours, which that formula converts into 14 bars when only about 2 exist in the market. Weekends and holidays are counted as non-existent bars and inflate the target duration.
* **Difference of integer bar indices**: this quantises each trade separately before averaging. With an H4 strategy backtested at M1 precision — the usual case when `Precision=M1` is not passed to the test — a 6h15m trade and a 7h50m trade would both give 1 bar; the average would come out at exactly 1.0 and **the dithering would have nothing to distribute**, leaving the monkey exposed 4 hours per trade against the real 6h15m.

Quantisation must happen only once, on the average, never on each trade.

### 5.9. Monkeys do not close on Fridays

The original strategy may have a forced weekend close, and its trades already come shortened by it. Since the target average duration is computed over those trades, that effect is **already incorporated**. Reapplying the cut to the monkeys would penalise them a second time and leave them systematically less exposed than the reference.

Furthermore, measuring in bars makes the weekend disappear from the computation: a trade of N bars is N market bars, whether or not it crosses Saturday in the calendar. **Market** exposure is replicated without needing to cut anything.

### 5.10. The dithering uses a random permutation with a fixed count

The extra bars are distributed at random among different trades in each monkey, instead of always falling on the first ones. Since trades run in chronological order, assigning them by index would invariably cluster the long trades at the start of the period, and identically across all monkeys.

**It must not be replaced by an independent probability per trade.** It looks more random, but the number of long trades would then follow a binomial distribution and total time exposure would vary from one monkey to another, losing the property that makes them comparable to each other and to the strategy.

### 5.11. The separation between entries is each trade's real duration

This is what mathematically guarantees the N trades fit within the period. If the original strategy does not overlap trades, the sum of their durations is less than or equal to the period length — but that guarantee is only inherited if the minimum separation is not rounded up uniformly.

---

## 6. Differences from v1.00

Version 1.00 is preserved as `MonkeyTest_v1_00.java` and still appears in the SQX dropdown, which allows running both over the same databank and comparing results.

| Aspect | v1.00 | v2.00 |
| :--- | :--- | :--- |
| P&L calculation | Trade by trade, reconstructing the pip value and scaling the lot size | Single calibration per period (`K`) |
| `replicationMode` | `SLTP` / `AvgBars` / `IndivBars` | Removed: always dithered average duration |
| `shiftingMode` | `Constant` / `Random` | Removed: always random without overlap |
| Overlap between trades | Possible when the fractional part of the average duration was below 0.5 | Impossible by construction |
| Period window | Trades could end outside the evaluated window | Always inside |
| Number of trades | Could be trimmed, comparing against the strategy's first N | Always the same as the strategy |
| Trade duration | Calendar time over bar duration (counted weekends) | Fractional position on the bar axis |
| Dithering distribution | Always to the first trades, identical across monkeys | Random permutation, different in each monkey |
| Friday close | Applied to the monkeys (double penalty) | Removed |
| Spread | Not applied | Subtracted from each simulated trade |
| Commissions and swaps | Prorated per trade, always subtracted | Strategy totals, respecting `CommSwapApplied` |
| Calculation mode | Heuristic over the XML and the lot sizes | Declared money management, via the SQX API |
| Failed numeric values | `0.00` was published on `LOW TRADES` / `ERROR` | `N/A` is published |
