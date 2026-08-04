# Monkey Test - Custom Analysis Snippet

A Monte Carlo Permutation test designed for StrategyQuant X (SQX) to evaluate the robustness of a trading strategy's entry and exit edge against random market timing.

---

## 1. Overview & Purpose
The **Monkey Test** is a statistical validation method used to determine whether a strategy's historical performance is a result of a genuine edge (precise entries and exits) or just statistical luck (e.g., trading during a strong, prolonged trend where any random entry would make money).

It simulates "monkeys" trading the strategy by taking the exact trade sequence (holding times, directions, and risk profiles) and applying **random circular time-shifts** across the historical candle database. If the real strategy outperforms a high percentile of these randomized runs, it passes the test.

---

## 2. Core Logic & How It Works
For each strategy in the databank, the snippet:
1. **Reads Native History Data**: Locates the symbol connection and timeframe from the main backtest and parses the native StrategyQuant BDF database file (`.dat`) dynamically.
2. **Filters by Sample Period**: Applies the selected period (`FULL`, `IS`, `OOS`, `ISV`, or numbered segments such as `OOS1`, `OOS2`, `ISV1`, etc.) to restrict both the real strategy's trade set and the monkey simulations to that window. Orders are filtered directly by their SQX sample type, so each numbered segment is genuinely isolated — a period is never silently substituted by another one's trades. The circular shift range is automatically bounded to the bars covered by the filtered trades. If the selected period contains no trades (e.g. the backtest was run without an OOS period configured), the strategy is marked as `LOW TRADES`. If a non-existent numbered segment is specified (e.g., `OOS3` when only 2 OOS segments exist), it is marked as `FAILED (INVALID PERIOD)`.
   * **`FULL` also evaluates every period separately.** Besides the aggregate, it probes `IS`, `OOS`, `ISV` and every existing `OOS1..10` / `ISV1..10` segment, running the test independently on each one so their results can be inspected in isolation from the Databank. This does **not** multiply the cost by the number of periods: each period only simulates its own trades (total ≈2-3× the aggregate alone). When the strategy has a single OOS segment, `OOS` and `OOS1` are the same period (SQX copies its stats), so it is simulated once and published under both.
3. **Performs Circular Shifts**: Generates $N$ randomized runs. For each run (monkey), all trades are shifted forward in time by a random offset, wrapping around the history boundary.
4. **Simulates Path Evaluation**:
   * **Entries**: Opened at the shifted bar's Open price.
   * **Exits**: Evaluated bar-by-bar to check if the Stop Loss (SL) or Profit Target (PT) is hit first. If the trade originally had no SL/TP, it uses the number of bars as a hard exit limit.
   * **Friday Exit**: Automatically closes trades at the Friday exit threshold if defined.
   * **Risk Equalization**: Adjusts the simulated position size (lots) proportionally if the entry price differs from the original entry price, keeping the monetary risk of the Stop Loss identical.
5. **Statistical Percentile Evaluation**: Compares the net profit of the original strategy against the distribution of the $N$ monkeys. If the original profit is greater than the defined percentile threshold of the monkeys' profits, the strategy passes.

---

## 3. How to Use & Input Arguments

### Setup in StrategyQuant X

#### 1. Custom Analysis Task
1. Add a **Custom Analysis** task to your project.
2. Under **Analysis type**, select **Per Strategy Analysis** (this enables multithreaded computation using all available CPU cores).
3. Select **MonkeyTest** as the analysis method from the dropdown.
4. In the **Input Args** field, configure your parameters as a comma-separated string: `numMonkeys,percentile,period,replicationMode,shiftingMode`. Optionally, append the keyword `ResultsPluginCache` anywhere in that same string to also write the cache artifacts consumed by the Databank Monkey Test ResultsPlugin (see [section 4](#4-expected-outputs) below).

#### 2. Builder Ranking & Retests Tabs
Since the snippet uses the `Per Strategy Analysis` signature, you can also select **MonkeyTest** from the **Custom Analysis** filter dropdown in the:
* **Ranking** tab of the Builder/Genetic settings (to filter out strategies automatically during generation).
* **Retests** settings (to filter out strategies after retesting them on new data).

### Input Arguments
| Parameter | Default Value | Description | Example |
| :--- | :--- | :--- | :--- |
| **numMonkeys** | `500` | The number of randomized monkey simulations to run per strategy. | `1000` |
| **percentile** | `95.0` | The statistical confidence threshold. The strategy must beat this percentage of monkey runs to pass. | `99.0` |
| **period** | `FULL` | Sample window where the test runs: `FULL` (entire backtest — **and additionally every other period separately**, see [section 2](#2-core-logic--how-it-works)), `IS` (In-Sample only), `OOS` (combined Out-of-Sample), `ISV` (In-Sample Validation), or specific numbered sub-periods (`OOS1`..`OOS10`, `ISV1`..`ISV10`). This value also decides which period drives the PASSED/FAILED verdict. | `OOS2` |
| **replicationMode**| `IndivBars` | Operation exit simulation mode: `SLTP` (SL & TP Distance), `AvgBars` (Fixed Average Exposure), or `IndivBars` (Individual Trade Exposure). | `SLTP` |
| **shiftingMode**   | `Random` | Circular time shifting mode: `Constant` (Constant Global Shift) or `Random` (Per-Trade Random Shift). | `Constant` |
| **ResultsPluginCache** | *(absent)* | Optional keyword, not positional — it is detected as a case-insensitive substring anywhere in the Input Args string, so it can be appended after any of the 5 parameters above. When present, the snippet writes the cache artifacts (CSV + meta.json) described in [section 4](#4-expected-outputs). When absent (default), **no cache files are written**, regardless of the test outcome. | `500,95,OOS2,IndivBars,Random,ResultsPluginCache` |
| **AutoDiscard** | *(absent)* | Optional keyword, same detection rules as `ResultsPluginCache` (case-insensitive substring, can be combined with it). Controls whether `filterStrategy` is allowed to signal SQX's engine to exclude the strategy when the test fails — see [section 4](#4-expected-outputs) for the full explanation. **Absent by default: strategies are never excluded**, regardless of PASSED/FAILED. | `500,95,OOS2,IndivBars,Random,AutoDiscard` |

*Example Input Args:* `500,95,OOS2,IndivBars,Random` (Runs 500 monkeys on OOS2 trades, using individual bar exposure exits and per-trade random shifting; no cache files written). Omitting replicationMode and shiftingMode automatically defaults to `IndivBars` and `Random`. Add `ResultsPluginCache` anywhere in the string, e.g. `500,95,OOS2,IndivBars,Random,ResultsPluginCache`, to also write the cache files for the ResultsPlugin.


---

## 4. Expected Outputs

### Required: Install the Monkey Test Databank Column

The **MonkeyTest** Custom Analysis snippet only writes results into the strategy metadata. To **display** these results as columns in the SQX databank, you must also install and activate the companion **Databank Column** snippets:

- **Files**: `SQ/Columns/Databanks/MonkeyTestColumn.java` and `SQ/Columns/Databanks/MonkeyTestZScoreColumn.java` (located alongside this snippet under `user/extend/Snippets/`)
- **Column names in SQX**: `Monkey Test` (type: Text) and `Monkey Z-Score` (type: Decimal2)

**Installation steps:**
1. Ensure both column files are present in `user/extend/Snippets/SQ/Columns/Databanks/`.
2. Restart SQX (or trigger snippet recompilation) so the columns are registered.
3. In the Databank view, open the column selector and add the **"Monkey Test"** / **"Monkey Z-Score"** columns.

> **Important:** both columns must be **recompiled** after updating this Custom Analysis. Earlier versions ignored the Databank sample-type selector and showed the same stored value in every period column.

> Without the Databank Columns installed, the test still runs and filters strategies via the `FiltersResult` column, but the individual outcomes (`PASSED`, `FAILED`, `LOW TRADES`, etc.) will not be visible in the databank grid.

These snippets (Custom Analysis + Databank Columns) are designed to work together and should all be installed for the full experience.

### Databank Columns
Results are stored **per period**, using one key per period suffix, so several runs on different periods can coexist on the same strategy without overwriting each other:

| Key | Written for |
| :--- | :--- |
| `MonkeyTestResult<suffix>` | Outcome of that period (see status list below). |
| `MonkeyTestPercentile<suffix>` | Rank percentile achieved against the monkey distribution, e.g. `85.20%`. |
| `MonkeyTestZScore<suffix>` | Z-Score of the real profit vs. the monkey mean/stdev. |

Valid suffixes: `_IS`, `_OOS`, `_ISV`, `_OOS1`..`_OOS10`, `_ISV1`..`_ISV10`, `_Full`.

The two columns resolve the suffix automatically from the **sample type selector of the Databank** — exactly like the `Synth*` columns of `CVSintetica` — so selecting *OOS2* in that selector shows the `_OOS2` values. Resolution is **strict**: if a period has not been evaluated, the column shows `N/A` instead of falling back to another period's value. The legacy unsuffixed keys (written by earlier versions) are still accepted, but only under *Full Sample*.

The unsuffixed keys are written **only when the requested period is `FULL`**, where they legitimately represent the aggregate; for any other requested period they are cleared, so they can never show a single period's value labelled as the total.

* **Monkey Test Column** statuses (`MonkeyTestResult<suffix>` key):
  * `PASSED`: The strategy's net profit beat the defined percentile of the randomized monkey runs.
  * `FAILED`: The strategy did not beat the percentile threshold.
  * `LOW TRADES`: The strategy has fewer than 20 trades in that period (too few to perform a reliable statistical analysis). Also shown when the period contains zero trades, which typically means the backtest was not configured with that sample period.
  * `FAILED (INVALID PERIOD)`: A numbered segment was requested (e.g. `OOS3`) that does not exist on the strategy (e.g. strategy only has 2 OOS segments).
  * `FAILED (NO DATA)`: The historical `.dat` file for the symbol/timeframe was missing in the SQX history folders.
  * `ERROR`: An unexpected execution error occurred.
* **Filters Result Column** (`FiltersResultFailedReason` / `FilterResult` keys):
  * Draws a **green PASSED** (`Passed`) if the test passes (and no other filters failed).
  * Draws a **red FAILED** (`Failed Monkey Test` or `Failed Monkey Test (Invalid Period)`) if the strategy fails.
  * **The verdict comes only from the period requested in Input Args.** When `FULL` computes the other periods as well, those extra results are published for inspection but never affect the verdict.

### Strategy exclusion (`AutoDiscard`)

Marking a strategy FAILED (above) is purely visual — it never removes anything by itself. Whether SQX's engine is actually told to exclude a failed strategy depends on the `AutoDiscard` keyword ([Input Arguments](#input-arguments)):

* **`AutoDiscard` absent (default): no strategy is ever excluded.** `filterStrategy` always returns `true` to SQX's engine, exactly like `CVSintetica`. Every strategy processed — PASSED or FAILED — stays wherever the task would otherwise put it, fully marked with its real result.
* **`AutoDiscard` present:** `filterStrategy` returns the real PASSED/FAILED verdict, letting SQX's engine act on it.

This matters because SQX has **two independent, unrelated mechanisms** that can end up excluding a strategy, and only one of them is affected by `AutoDiscard`:

1. **Copying between two different databanks** (a Custom Analysis task with a different Input/Output databank): SQX's engine only copies to the Output databank the strategies for which `filterStrategy` returned `true`. The failed ones are **never copied**, regardless of any UI setting — this is the exact scenario that motivated adding `AutoDiscard`: without it, a task copying e.g. `SynthTestFiltered - IS` → `MonkeyTest - OOS` would silently drop every failing strategy, leaving only PASSED ones in the output databank with no way to know how many were dropped or why.
2. **The native "Filter by results of custom analysis" checkbox** (task settings, *"If true strategies that don't pass will be removed"*): only relevant when Input and Output databank are the **same** (in-place analysis). It deletes failed strategies from that databank, but **only if `AutoDiscard` also causes `filterStrategy` to return `false`** — with `AutoDiscard` absent, this checkbox has no effect at all, since `filterStrategy` never reports a failure to the engine.

In short: `AutoDiscard` is the single switch that decides whether *either* mechanism can ever remove a strategy. Leave it out to always keep every strategy, marked with its real result, in every task configuration.

### Cache Files for the Databank Monkey Test ResultsPlugin (v3)
To let the **Databank Monkey Test** ResultsPlugin auto-display the Gaussian bell curve and equity comparison charts without recalculating, the snippet can write two cache artifacts per strategy into:
`user/extend/ResultsPlugins/DatabankMonkeyTest/cache/`

> **Opt-in via `ResultsPluginCache`:** these two files are only written when the `ResultsPluginCache` keyword is present in the Input Args (see the [Input Arguments table](#input-arguments)). By default (keyword absent), the snippet still computes and stores the per-period `MonkeyTestResult<suffix>`, `MonkeyTestPercentile<suffix>` and `MonkeyTestZScore<suffix>` databank values, but **skips writing these cache files entirely** — nothing is created or updated on disk. Add the keyword only if you intend to inspect that strategy's result in the Databank Monkey Test ResultsPlugin, to avoid accumulating cache files for strategies you don't plan to review.

> **One cache pair per strategy — for the requested period only.** The ResultsPlugin locates these files by strategy name alone (the period is not part of the filename), so only one pair can exist per strategy. When `FULL` evaluates every period, the cache is written **exclusively for the period requested in Input Args**; the other periods are still published as Databank values but are not cached. The `period` field inside `meta.json` always identifies which period the cached data belongs to.

* **`[StrategyName]_monkey_simulation_data.csv`** — a compact "wide" CSV with up to 50 representative monkey equity curves (not a full trade-level dump). Each row is one monkey's full balance path: `monkey_id;b0;b1;...;bT` (semicolon-separated, dot decimals, no quotes, UTF-8 without BOM). Rows are selected from the full distribution of monkey profits — the lowest (`min`), the highest (`max`), and up to 48 intermediate curves spaced evenly by percentile rank — so the plugin can plot a representative "spaghetti" of equity curves against the real strategy's equity, sourced separately from `GET_ORDERS`.
* **`[StrategyName]_monkey_simulation_data.meta.json`** — all the scalar KPIs plus the full array of monkey profits, schema version 3:

  | Field | Description |
  | :--- | :--- |
  | `schemaVersion` | Always `3`. Marks this as the current cache format. |
  | `strategyName`, `period` | Strategy name and sample period used for the cached test (`FULL`, `IS`, `OOS`, `ISV`, `OOS1`..`OOS10`, `ISV1`..`ISV10`) — always the period requested in Input Args. |
  | `tradeFromMs`, `tradeToMs` | Epoch ms (UTC) range of the real trades used — lets the plugin verify the cache matches the strategy currently loaded before trusting it. |
  | `numTrades`, `numMonkeys`, `percentile` | Test configuration actually used. |
  | `replicationMode` | Operation exit simulation mode used: `SLTP`, `AvgBars`, or `IndivBars`. |
  | `shiftingMode` | Circular time shifting mode used: `Constant` or `Random`. |
  | `initialBalance` | Starting balance, equal to `b0` in every CSV row. |
  | `realProfit`, `monkeyThreshold`, `meanMonkey`, `stdMonkey`, `zScore`, `rankPercentile` | Statistics comparing the real strategy against the full N-monkey distribution. |
  | `status` | `"PASSED"`, `"FAILED"`, or `"LOW TRADES"` — exact strings, used directly by the plugin's badges. |
  | `meanHoldingPeriod` | The average trade duration in bars. |
  | `monkeyProfits` | The full array of N monkey profits, sorted ascending — drives the Gaussian histogram. |
  | `generatedAtUtc`, `source` | Cache freshness and origin (`"CustomAnalysis"` here; the plugin can also write its own cache with `"Plugin"` when the user runs a live calculation from its own "Run Monkey Test" button). |

> **Integration with the ResultsPlugin:** when a strategy is double-clicked in the databank, the "Databank Monkey Test" Results tab automatically loads these cache files and renders the charts without requiring the user to re-run the simulation. The full v3 cache contract — including exact field formats, the curve-selection algorithm, and how each UI element consumes these fields — is the authoritative specification in:
> `user/extend/ResultsPlugins/DatabankMonkeyTest/MTCustomAnalysisImprovementPlan.md`

> As with v1, the cache files are only written when the `ResultsPluginCache` keyword is present in Input Args **and** the test fully runs (i.e. not for `LOW TRADES`, `FAILED (NO DATA)`, or `ERROR` outcomes); the plugin falls back to a live recalculation when no matching cache is found.
