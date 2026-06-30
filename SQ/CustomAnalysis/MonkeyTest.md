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
2. **Filters by Sample Period**: Applies the selected period (`FULL`, `IS`, or `OOS`) to restrict both the real strategy's trade set and the monkey simulations to that window. The circular shift range is automatically bounded to the bars covered by the filtered trades. If the selected period contains no trades (e.g. the backtest was run without an OOS period configured), the strategy is marked as `LOW TRADES`.
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
1. Add a **Custom Analysis** task to your project.
2. Select **MonkeyTest** as the analysis method.
3. In the **Input Args** field of the task settings, configure your parameters as a comma-separated string: `numMonkeys,percentile,period`.

### Input Arguments
| Parameter | Default Value | Description | Example |
| :--- | :--- | :--- | :--- |
| **numMonkeys** | `500` | The number of randomized monkey simulations to run per strategy. | `1000` |
| **percentile** | `95.0` | The statistical confidence threshold. The strategy must beat this percentage of monkey runs to pass. | `99.0` |
| **period** | `FULL` | Sample window where the test runs: `FULL` (entire backtest), `IS` (In-Sample only), or `OOS` (Out-of-Sample only). Optional; defaults to `FULL`. | `OOS` |

*Example Input Args:* `500,95,OOS` (Runs 500 monkeys using only the Out-of-Sample trades, and requires the strategy to beat 95% of them). Omitting the third argument is equivalent to `FULL`.

---

## 4. Expected Outputs

### Required: Install the Monkey Test Databank Column

The **MonkeyTest** Custom Analysis snippet only writes results into the strategy metadata (key `MonkeyTestResult`). To **display** these results as a column in the SQX databank, you must also install and activate the companion **Databank Column** snippet:

- **File**: `SQ/Columns/Databanks/MonkeyTestColumn.java` (located alongside this snippet under `user/extend/Snippets/`)
- **Column name in SQX**: `Monkey Test` (type: Text)

**Installation steps:**
1. Ensure `MonkeyTestColumn.java` is present in `user/extend/Snippets/SQ/Columns/Databanks/`.
2. Restart SQX (or trigger snippet recompilation) so the column is registered.
3. In the Databank view, open the column selector and add the **"Monkey Test"** column.

> Without the Databank Column installed, the test still runs and filters strategies via the `FiltersResult` column, but the individual outcomes (`PASSED`, `FAILED`, `LOW TRADES`, etc.) will not be visible in the databank grid.

These two snippets (Custom Analysis + Databank Column) are designed to work together and should both be installed for the full experience.

### Databank Columns
The snippet writes outcomes directly to the strategy metadata to populate databank columns:
* **Monkey Test Column** (`MonkeyTestResult` key):
  * `PASSED`: The strategy's net profit beat the defined percentile of the randomized monkey runs.
  * `FAILED`: The strategy did not beat the percentile threshold.
  * `LOW TRADES`: The strategy has fewer than 20 trades in the selected period (too few to perform a reliable statistical analysis). Also shown when the selected period (`IS`/`OOS`) contains zero trades, which typically means the backtest was not configured with that sample period.
  * `FAILED (NO DATA)`: The historical `.dat` file for the symbol/timeframe was missing in the SQX history folders.
  * `ERROR`: An unexpected execution error occurred.
* **Filters Result Column** (`FiltersResultFailedReason` / `FilterResult` keys):
  * Draws a **green PASSED** (`Passed`) if the test passes (and no other filters failed).
  * Draws a **red FAILED** (`Failed Monkey Test`) if the strategy fails, allowing SQX's automated workflow to discard it.

### Cache Files for the Databank Monkey Test ResultsPlugin (v2)
To let the **Databank Monkey Test** ResultsPlugin auto-display the Gaussian bell curve and equity comparison charts without recalculating, the snippet writes two cache artifacts per strategy into:
`user/extend/ResultsPlugins/DatabankMonkeyTest/cache/`

* **`[StrategyName]_monkey_simulation_data.csv`** — a compact "wide" CSV with up to 50 representative monkey equity curves (not a full trade-level dump). Each row is one monkey's full balance path: `monkey_id;b0;b1;...;bT` (semicolon-separated, dot decimals, no quotes, UTF-8 without BOM). Rows are selected from the full distribution of monkey profits — the lowest (`min`), the highest (`max`), and up to 48 intermediate curves spaced evenly by percentile rank — so the plugin can plot a representative "spaghetti" of equity curves against the real strategy's equity, sourced separately from `GET_ORDERS`.
* **`[StrategyName]_monkey_simulation_data.meta.json`** — all the scalar KPIs plus the full array of monkey profits, schema version 2:

  | Field | Description |
  | :--- | :--- |
  | `schemaVersion` | Always `2`. Marks this as the current cache format. |
  | `strategyName`, `period` | Strategy name and sample period (`FULL`/`IS`/`OOS`) used for the test. |
  | `tradeFromMs`, `tradeToMs` | Epoch ms (UTC) range of the real trades used — lets the plugin verify the cache matches the strategy currently loaded before trusting it. |
  | `numTrades`, `numMonkeys`, `percentile` | Test configuration actually used. |
  | `initialBalance` | Starting balance, equal to `b0` in every CSV row. |
  | `realProfit`, `monkeyThreshold`, `meanMonkey`, `stdMonkey`, `zScore`, `rankPercentile` | Statistics comparing the real strategy against the full N-monkey distribution. |
  | `status` | `"PASSED"`, `"FAILED"`, or `"LOW TRADES"` — exact strings, used directly by the plugin's badges. |
  | `monkeyProfits` | The full array of N monkey profits, sorted ascending — drives the Gaussian histogram. |
  | `generatedAtUtc`, `source` | Cache freshness and origin (`"CustomAnalysis"` here; the plugin can also write its own cache with `"Plugin"` when the user runs a live calculation from its own "Run Monkey Test" button). |

> **Integration with the ResultsPlugin:** when a strategy is double-clicked in the databank, the "Databank Monkey Test" Results tab automatically loads these cache files and renders the charts without requiring the user to re-run the simulation. The full v2 cache contract — including exact field formats, the curve-selection algorithm, and how each UI element consumes these fields — is the authoritative specification in:
> `user/extend/ResultsPlugins/DatabankMonkeyTest/MTCustomAnalysisFixes.md`

> As with v1, the cache files are only written when the test fully runs (i.e. not for `LOW TRADES`, `FAILED (NO DATA)`, or `ERROR` outcomes); the plugin falls back to a live recalculation when no matching cache is found.
