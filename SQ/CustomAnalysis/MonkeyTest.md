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
2. **Performs Circular Shifts**: Generates $N$ randomized runs. For each run (monkey), all trades are shifted forward in time by a random offset, wrapping around the history boundary.
3. **Simulates Path Evaluation**:
   * **Entries**: Opened at the shifted bar's Open price.
   * **Exits**: Evaluated bar-by-bar to check if the Stop Loss (SL) or Profit Target (PT) is hit first. If the trade originally had no SL/TP, it uses the number of bars as a hard exit limit.
   * **Friday Exit**: Automatically closes trades at the Friday exit threshold if defined.
   * **Risk Equalization**: Adjusts the simulated position size (lots) proportionally if the entry price differs from the original entry price, keeping the monetary risk of the Stop Loss identical.
4. **Statistical Percentile Evaluation**: Compares the net profit of the original strategy against the distribution of the $N$ monkeys. If the original profit is greater than the defined percentile threshold of the monkeys' profits, the strategy passes.

---

## 3. How to Use & Input Arguments

### Setup in StrategyQuant X
1. Add a **Custom Analysis** task to your project.
2. Select **MonkeyTest** as the analysis method.
3. In the **Input Args** field of the task settings, configure your parameters as a comma-separated string: `numMonkeys,percentile`.

### Input Arguments
| Parameter | Default Value | Description | Example |
| :--- | :--- | :--- | :--- |
| **numMonkeys** | `500` | The number of randomized monkey simulations to run per strategy. | `1000` |
| **percentile** | `95.0` | The statistical confidence threshold. The strategy must beat this percentage of monkey runs to pass. | `99.0` |

*Example Input Args:* `1000,95.0` (Runs 1,000 monkeys and requires the strategy to beat 95% of them).

---

## 4. Expected Outputs

### 1. Databank Columns
The snippet writes outcomes directly to the strategy metadata to populate databank columns:
* **Monkey Test Column** (`MonkeyTestResult` key):
  * `PASSED`: The strategy's net profit beat the defined percentile of the randomized monkey runs.
  * `FAILED`: The strategy did not beat the percentile threshold.
  * `LOW TRADES`: The strategy has fewer than 20 trades (too few to perform a reliable statistical analysis).
  * `FAILED (NO DATA)`: The historical `.dat` file for the symbol/timeframe was missing in the SQX history folders.
  * `ERROR`: An unexpected execution error occurred.
* **Filters Result Column** (`FiltersResultFailedReason` / `FilterResult` keys):
  * Draws a **green PASSED** (`Passed`) if the test passes (and no other filters failed).
  * Draws a **red FAILED** (`Failed Monkey Test`) if the strategy fails, allowing SQX's automated workflow to discard it.

### 2. CSV Debug Log
For deep-dive validation and balance curve plotting, the simulation results of all monkeys are exported to:
`user/exports/MonkeyTest/[StrategyName]_monkey_simulation_data.csv`

This file contains 23 columns (including timestamps, entry/exit prices, MAE, MFE, commissions, swap, SL/PT levels, and cumulative balance curves) and is formatted to be fully compatible with the HTML/JS Results Plugin tab for instant visual charting.
