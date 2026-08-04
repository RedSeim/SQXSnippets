# Documentation: Synthetic Robustness Custom Analysis (CVSintetica)

The `CVSintetica_V08` script (and its predecessor `CVSintetica_V07`) is a Custom Analysis designed for **StrategyQuant X** (SQX) aimed at evaluating the robustness and ergodicity of trading strategies against variations of market data (synthetic data).

The **`CVSintetica_V08`** version includes parallel multi-thread execution support, drastically reducing processing times on multi-core processors.

It also supports **numbered Out-of-Sample periods**: if your project defines more than one OOS, you can run the test in isolation on a specific one (`OOS2`, for example), and `FULL` mode automatically generates the metrics for each OOS part separately.

---

## 1. What is this Custom Analysis?
It is a statistical stress test. It takes your strategy's logic and subjects it to a repeated backtest across **synthetic data variations**: variations of the original data, designed to simulate the strategy's behavior across a given number of parallel universes, generated in compliance with a set of criteria established by subject-matter expert Alan Tomillero — a topic that is not covered in depth in this documentation. By default it runs **100 simulations**, but this number is fully configurable via the task arguments.

The analysis calculates how likely the strategy is to survive these variations (Pass Rate/Survival Rate) and evaluates whether the strategy is overfit to the original history or whether its results are consistent across parallel synthetic universes.

---

## 2. Functional Overview
The Custom Analysis execution flow consists of the following steps:

1.  **Original Parameter Resolution:** Identifies the symbol, timeframe, date range, and original history of the strategy being analyzed.
2.  **Control Backtest (Original):** Runs a backtest on the original symbol, inheriting the full original configuration, and segments profit and trade count by period:
    *   **In-Sample (IS)** (Optimization)
    *   **Out-of-Sample (OOS)** (Validation). If the project defines several OOS parts, this period is the **aggregate** of all of them.
    *   **Numbered OOS parts (OOS1, OOS2, ... up to OOS10)**, each computed separately, when the project defines more than one Out-of-Sample period.
    *   **In-Sample Validation (ISV)** (Cross-validation)
    *   **Full Sample (Full)** (Entire history)

    This control backtest inherits from the original backtest:
    *   Money Management, Trading Options (incl. Realistic Gaps), Commissions, and Swap, always reading the **main** `<Setup>` from the strategy's configuration XML (not just any `<Setup>` that happens to appear first in the tree — if the project has additional Retest/Cross-Check Setups with different commission, swap, or options, they are not confused with the main one).
    *   Dates, timeframe, session, spread, slippage, minimum distance, and test precision.
    *   The same engine as the original strategy (MetaTrader5 Hedged/Netted, MetaTrader4, Tradestation, NinjaTrader, JForex, or Stockpicker), resolved individually for each strategy from its own XML.

    For the **MetaTrader5 (Hedged and Netted)** engines, the simulator reproduces the original backtest with **trade-by-trade** fidelity (empirically verified against the Databank, with no price or trade differences), correcting an internal simulator parameter that previously caused a small number of borderline trades (entries that "graze" the level without clearly triggering) to not be reproduced in the retest. This does not affect other engines (MT4, Tradestation, NinjaTrader, JForex, Stockpicker), which do not have that parameter.
3.  **Parallel Synthetic Simulations (V08):** Runs **N concurrent backtests** (100 by default), replacing the original symbol with the synthetic symbols in parallel, using all of your CPU's free threads to speed up processing.
4.  **Metrics Calculation:** For each simulation and active period, the net profit and trade count are extracted. Finally, the following key statistical metrics are calculated:
    *   **Pass Rate (Survival Rate):** The percentage of synthetic simulations where the strategy ended with strict profit and traded at least once:
        $$\text{Pass Rate} = \frac{\text{Winning Synthetic Simulations with Trades}}{\text{Total Simulations (N)}}$$
    *   **Synthetic Ratio (Ergodicity Ratio):** Evaluates the stability and consistency of the average synthetic returns relative to the volatility or dispersion among them:
        $$\text{Synthetic Ratio} = \frac{\text{Mean Synthetic Profit}}{\text{Standard Deviation of Synthetic Profit}}$$
    *   **Overfitting Ratio (Signed Z-Score):** Measures how many standard deviations of distance there are between the original profit and the mean of the synthetic profits. An extremely high Z-Score (> 2.0) suggests possible overfitting:
        $$\text{Overfitting Ratio} = \frac{\text{Original Profit} - \text{Synthetic Mean}}{\text{Synthetic Standard Deviation}}$$
5.  **Storage:** Results are written directly into the strategy's variable map (`SpecialValuesMap`) so they can be read from SQX's Databank columns or from external analysis tools.

---

## 3. Usage Instructions

### Setup in the Code Editor
1.  Open **StrategyQuant X**.
2.  Go to the **Code Editor** menu.
3.  Navigate to the `Snippets/SQ/CustomAnalysis/` folder and double-click `CVSintetica_V08.java`.
4.  Click the **Compile** button in the top toolbar so SQX loads the new logic.

### Argument Setup in Tasks (Projects / Builder / Optimizer)
To use this Custom Analysis in your optimization workflows, add the custom analysis task and configure the input arguments in the following format:

```text
syntheticDataName, period, [simulation_count], [Debug]
```

#### Argument 1: `syntheticDataName` (Synthetic Data Prefix)
This is the prefix of the synthetic symbol names imported into your SQX database.
*   *Default:* `XAUUSD_Darwinex_sim` (if not provided).
*   *Example:* `EURUSD_H1_ftmo_SYN_` (the script will look up the corresponding symbols sequentially).

#### Argument 2: Target Period
Indicates which part of the data history the test will run on. This argument supports 4 basic options:

| Option | Description | Behavior in Special Values |
| :--- | :--- | :--- |
| **`FULL`** | Runs the test on **all** periods simultaneously and independently (IS, ISV, OOS, Full) **plus every numbered OOS part** that exists. *This is the recommended option.* | Stores independent data for every suffixed key (`_IS`, `_OOS`, `_ISV`, `_Full`, and `_OOS1`...`_OOSN`) and global variables. |
| **`IS`** | Runs the test **only** on the strategy's **In-Sample** period. | Stores information only for the In-Sample period (`_IS`). The `OOS` and `ISV` periods are left clean (empty/NaN). |
| **`OOS`** (or `IIS`) | Runs the test **only** on the **Out-of-Sample** period. If there are several OOS parts, this is the **aggregate** of all of them. | Stores information only for the Out-of-Sample period (`_OOS`). The `IS` and `ISV` periods are left empty. |
| **`OOS1`** ... **`OOS10`** | Runs the test **only** on a **specific** Out-of-Sample part. This is the way to isolate, for example, only OOS2 when the project defines several OOS parts. | Stores information only for that part (`_OOS2`, etc.). All other periods are left empty. |
| **`ISV`** | Runs the test **only** on the **In-Sample Validation** period. | Stores information only for the In-Sample Validation period (`_ISV`). The `IS` and `OOS` periods are left empty. |

> **Numbering of OOS parts.** SQX numbers Out-of-Sample periods **in chronological order of definition**: the project's first OOS range is `OOS1`, the second is `OOS2`, and so on (up to a maximum of 10). That is exactly the number you should use in the argument.

> **With a single OOS period**, `FULL` does **not** emit `_OOS1`: SQX makes the stats for OOS1 and the aggregate OOS identical, so they would be duplicate columns. In that case just use `_OOS`.

> **Out-of-range values** (`OOS0`, `OOS11`, `OOSX`...) do not produce an error: they behave like `FULL`, just like any other unrecognized token, and are noted in the debug log.

#### Argument 3: Simulation Count (Optional)
Defines the exact number of synthetic data variations the test will run over.
*   *Default:* `100` (if not specified, or if a non-numeric or non-positive value is entered).
*   *Example:* `150` (the loop will run from index 1 through 150).

#### Argument 4: `Debug` (Optional)
Enables detailed trade-by-trade dumping to diagnose Net Profit differences between the Databank's original backtest and its re-execution (retest) within the Custom Analysis.

*   If the 4th argument is literally the word `Debug` (case-insensitive), the file `CVSintetica_trades_compare.log` is generated/updated (see *Log Location* below) with one block per backtest performed (the control run on the original symbol, and each of the synthetic simulations), delimited by `--- START COMPARE FOR <strategy> [symbol=<symbol>, control=<true|false>] ---` / `--- END COMPARE FOR <strategy> ---`. Inside each block, one line per trade with the format `ORIGINAL|RETEST;strategy;symbol;Trade#N;LONG|SHORT;OpenTime=...;CloseTime=...;OpenPrice=...;ClosePrice=...;Size=...;GrossPL=...;CommSwap=...;NetPL=...;CloseType=...`, plus a `=== TOTAL TRADES ... ===` line with the count. The `ORIGINAL` block is only included in the control run (`control=true`), not repeated for each synthetic run. Each block is written in a single atomic synchronized operation, so it never interleaves with those of other threads even when running in parallel.
*   *Default* (if this argument is omitted or set to anything else): **this log is not generated**, to avoid consuming unnecessary disk space in runs with many strategies.
*   *Example:* `EURUSD_H1_ftmo_SYN_, OOS, 100, Debug`.

### Example Argument Strings:
*   `EURUSD_H1_ftmo_SYN_, FULL` $\rightarrow$ Runs the analysis on all periods using the default **100** simulations.
*   `EURUSD_H1_ftmo_SYN_, FULL, 150` $\rightarrow$ Runs the analysis on all periods using exactly **150** simulations (run in parallel).
*   `EURUSD_H1_ftmo_SYN_, IS, 50` $\rightarrow$ Analyzes and stores only the In-Sample period's robustness using **50** simulations.
*   `EURUSD_H1_ftmo_SYN_, OOS, 120` $\rightarrow$ Analyzes and stores only the (aggregate) Out-of-Sample period's robustness using **120** simulations.
*   `EURUSD_H1_ftmo_SYN_, OOS2, 100` $\rightarrow$ Analyzes and stores only the **second** Out-of-Sample period's robustness, in isolation, using **100** simulations.
*   `EURUSD_H1_ftmo_SYN_, OOS, 100, Debug` $\rightarrow$ Same as above with **100** simulations, and additionally generates `CVSintetica_trades_compare.log` with the trade-by-trade detail of the control run and each synthetic simulation.

---

## 4. Requirements and Behaviors to Keep in Mind

### Log Location
Both diagnostic files (`CVSintetica_debug.log` and, with the `Debug` argument, `CVSintetica_trades_compare.log`) are written **right next to the snippet itself**, i.e. in:

```text
user/extend/Snippets/SQ/CustomAnalysis/
```

This is a **path relative to the SQX installation root**, so it stays valid if you reinstall SQX, move the installation folder, or clone the configuration to another computer. The folder is created automatically if it doesn't exist.

### `ComputeSeparateMetrics` must be enabled
For SQX to compute the statistics of each OOS part separately, the global setting **"Compute metrics separate for every data part of the same type"** (`ComputeSeparateMetrics`, in the Performance dialog) must be **enabled** — it is by default. If disabled, numbered parts stop being computed: the Custom Analysis detects this, notes it in the log, and sets `CA_SynthSeparateMetricsSuspect = 1`.

### If the requested OOS part does not exist
If you request `OOS2` on a strategy that only has one OOS period, the analysis **does not fabricate values**: it does not publish any metric for that suffix (the Databank columns show *N/A* instead of a 0% Pass Rate that would look like a real, bad result) and sets `CA_SynthPartMissing_OOS2 = 1`. The strategy is **never removed** from the Databank for this reason.

### Pass Rate Denominator
The Pass Rate is calculated over the simulations that were **actually evaluated**, i.e. those whose statistics SQX was able to compute for that period. A simulation whose statistics don't exist is excluded from the sample rather than counted as a loss, because a measurement gap is not a strategy failure. Under normal conditions this exclusion count is zero and the value matches exactly what earlier versions produced; the number of exclusions is recorded in `CA_SynthMissingStatsCount<suffix>`.

### Databank Columns
The 7 `Synth*` columns (`SynthPassRate`, `SynthMeanProfit`, `SynthStdevProfit`, `SynthCVProfit`, `SynthOverfittingRatio`, `SynthMeanSharpe`, `SynthFailCount`) automatically resolve the period from the **Databank's sample type selector**. To see the `_OOS2` values, simply select **OOS2** in that selector; no new columns are needed.

> **Important:** these 7 columns must be **recompiled** after updating this Custom Analysis. Prior to this version they did not recognize numbered sample types and showed *Full Sample* values as if they were the selected part's.

### Diagnostic Variables
| Key | Meaning |
| :--- | :--- |
| `CA_SynthTargetPeriod` | The target period as interpreted from the argument. |
| `CA_SynthOOSPartsAvailable` | Number of OOS parts detected on the strategy. |
| `CA_SynthOOSResolved` | `1` if the OOS configuration could be resolved, `0` if not. |
| `CA_SynthSeparateMetricsSuspect` | `1` if statistics were missing, suggesting `ComputeSeparateMetrics` is disabled. |
| `CA_SynthPartMissing<suffix>` | `1` if an OOS part was requested that the strategy does not have. |
| `CA_SynthMissingStatsCount<suffix>` | Simulations excluded for lacking computable statistics. |
| `CA_SynthOriginalStatsMissing<suffix>` | `1` if the control backtest did not have reliable statistics for that period (either because the control run failed entirely, or because that specific period had no computable statistics). That suffix's Overfitting Ratio is not published. |
| `CA_SynthNoData<suffix>` | `1` if **none** of that period's synthetic simulations produced computable statistics (e.g. the synthetic data prefix/name does not exist). That suffix's `CA_SynthMeanProfit`, `CA_SynthStdevProfit`, `CA_SyntheticRatio`, `CA_SynthMeanSharpe`, `CA_PassRate`, and `CA_OverfittingRatio` are not published. |

### Known Limitation
There are two distinct scenarios in which some metrics are not published, each with its own diagnostic signal:

- **The control backtest fails** (`CA_OriginalRetestFailed = 1`) or the original symbol could not be resolved: there is no reliable original profit for any period. In that case `CA_OverfittingRatio<suffix>` (and its unsuffixed retrocompat variant `CA_OverfittingRatio`) **are not published** — the `SynthOverfittingRatio` column shows `N/A` instead of a Z-Score computed against a fictitious original profit of 0. The rest of the period's metrics (`CA_SynthMeanProfit`, `CA_SynthStdevProfit`, `CA_PassRate`, etc.) do not depend on the control run and keep publishing normally. `CA_SynthOriginalStatsMissing<suffix>` flags per period when this happens; it's also worth checking `CA_OriginalRetestFailed` to diagnose the root cause.
- **No synthetic simulation could run for a period** (e.g. a misconfigured/nonexistent synthetic data prefix or name): unlike the previous case, here the problem is on the synthetic side, not the control run. That suffix's `CA_SynthMeanProfit`, `CA_SynthStdevProfit`, `CA_SyntheticRatio`, `CA_SynthMeanSharpe`, `CA_PassRate`, and `CA_OverfittingRatio` **are not published** (the columns show `N/A`, including `SynthCVProfit` since it depends on `CA_SynthMeanProfit`/`CA_SynthStdevProfit`), instead of publishing `0`/`0%` as if the simulations had run and produced that result. `CA_SynthFailCount<suffix>` still shows the real failure count (e.g. equal to the number of simulations requested) to diagnose the cause. `CA_SynthNoData<suffix>` flags per period when this happens.

### Persistence Across Runs and Stale Data Cleanup
`specialValues()` is tied to the **strategy**, not the run: its keys persist across different executions of the Custom Analysis on the same strategy. To prevent a period whose test fails in the current run from continuing to show the values of a previous successful test (as if they belonged to the current one), the Custom Analysis **explicitly clears** every `CA_Synth*`/`CA_OverfittingRatio`/`CA_PassRate`/`CA_OriginalProfit`/`CA_OriginalTrades` key (per suffix, and the unsuffixed retrocompat variants) for each period **included in the current run's scope**, before recalculating them. Only the ones that turn out to be reliable in that run are published again; the rest are left without a value (column shows `N/A`).

This **does not affect** periods that are not part of the current run's requested `targetPeriod`: for example, if a previous run tested `FULL` and the current run only requests `OOS1`, the `IS`/`ISV`/`Full` values are left untouched and keep showing their last known result — this is the expected incremental behavior, not stale data. To find out which periods the last run actually covered, check `CA_SynthTargetPeriod`.

### "Could Not Be Evaluated" Visual Marker (the "Filters result" column)
When the test could not be evaluated reliably in the current run, the strategy is marked **FAILED** in SQX's native **"Filters result"** column, with an English tooltip explaining the specific cause (as specific as the diagnosis allows). If the test evaluates normally, it is explicitly marked **PASSED** (so a FAILED from a previous run doesn't stay "stuck"). Priority order (the first matching case is the one shown):

1. **The requested period does not exist on this strategy** (e.g. `OOS5` was requested but the strategy only has 2 OOS parts): nothing could even be evaluated. Message: *"the requested period '...' does not exist for this strategy."*
2. **The control backtest fails** (`CA_OriginalRetestFailed = 1`), with three distinct sub-causes in the message:
   - Original symbol not resolved: *"the original symbol could not be resolved."*
   - `BadStrategyException` in the control run: *"...threw a BadStrategyException (...). Check strategy/symbol compatibility."*
   - Generic exception: *"...control backtest on the original symbol failed (...)."*
3. **No synthetic simulation of any tested period produced data** (every period with `CA_SynthNoData<suffix> = 1`), with the dominant cause among the N simulations:
   - All ran but with no stats per period → likely `ComputeSeparateMetrics` disabled.
   - All failed with "too many trades on the same bar".
   - All failed with `BadStrategyException`.
   - All threw the same generic exception (includes the exception text and the synthetic data prefix used).
   - Mixed with no dominant cause: generic message suggesting to check the synthetic data name.

This is done by writing directly to SQX's standard key `SpecialValues.FiltersResultFailedReason` (the same one used by the `SQ.Columns.Databanks.FiltersResult` column), **not** to a custom `CA_*` key. It is purely a visual indicator: `filterStrategy` still always returns `true`, the strategy is never excluded from the databank for this reason — these are test configuration/infrastructure failures, not a judgment about the strategy's quality. Partial failures (some synthetics fail, most don't), an isolated `CA_SynthSeparateMetricsSuspect`, and `CA_SynthOOSResolved = 0` are outside this binary PASSED/FAILED mechanism.

**Important:** if the same project/databank also has **native SQX Filters** configured (the "Filters" tab, with conditions like `NetProfit > 0`, etc.), both mechanisms write to the same `FiltersResultFailedReason` key — whichever runs last "wins". It has not been possible to confirm the execution order between the Custom Analysis and native Filters; if you use both in the same project, verify that the final verdict is what you expect.
