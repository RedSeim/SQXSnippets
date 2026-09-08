# MonkeyTest_ATR_v1_00 — Verification Checklist

This document lists all verification steps pending execution in SQX. The code compiles cleanly and is ready for testing.

---

## 1. ATR Accuracy Against SQX (CRITICAL — Must Pass First)

**What to do**: Add the ATR(14) indicator to a chart of EURUSD H4 in SQX, then compare 2–3 specific values against those dumped in the Debug log.

**Why it matters**: Validates both Wilder's smoothing and causal (no-lookahead) computation — errors here would invalidate all downstream results.

**How to verify**:
- Run the test with `Debug` flag on a known strategy against EURUSD H4 data.
- Extract ATR values from the `ATR STATS` block in `MonkeyTest_ATR_v1_debug.log`.
- Compare against the ATR(14) indicator visible on the SQX H4 chart at the same bar timestamps.
- ATR values should match exactly (Wilder's smoothing is deterministic).

**Acceptance**: Values match within machine precision (last decimal place rounding only).

---

## 2. Coexistence with MonkeyTest v2 on Existing Databank

**What to do**: Execute on `SynthTestFiltered - OOS` (project `EURUSD H4 - Iterator Edge`, 13 strategies) with `500,70,OOS2`, then inspect the Databank view.

**Why it matters**: Confirms the test does not corrupt existing results, and that column precedence works correctly.

**How to verify**:
1. **Coexistence of keys**: Open the Databank and inspect columns for the 13 strategies.
   - `MonkeyTest*` keys from v2 should still be present and unchanged.
   - New `MonkeyATR*` keys should appear alongside them.
   - Verify that no key values were overwritten or lost.

2. **No invariant warnings**: Check the SQX log.
   - Search for `WARN` messages from invariants A1, A2, A3.
   - Result: zero warnings.

3. **ExposureRatio consistency**: The `MonkeyATRExposureRatio` should read `1.0000` for all 13 strategies.
   - This confirms the dithering and layout machinery (unchanged from v2) is still working.

**Acceptance**: All three conditions pass.

---

## 3. Ranking Correlation with v2 on EURUSD (No Bias Expected)

**What to do**: On the same `SynthTestFiltered - OOS` execution from step 2, compare the ranking of the 13 strategies between the ATR test and the v2 test.

**Why it matters**: EURUSD has minimal price drift (it oscillates in range), so the price-level bias corrected by ATR normalization should be negligible. Both tests should rank strategies almost identically. **A strong divergence here signals a bug, not the bias correction working.**

**How to verify**:
1. Extract the percentile (or rank) of each strategy from both `MonkeyTestPercentile<sfx>` and `MonkeyATRPercentile<sfx>`.
2. Compare the two rankings.
3. Calculate Spearman or Kendall rank correlation.
4. Document any differences found (strategy name, v2 rank, ATR rank, direction of change).

**Acceptance**: Correlation ≥ 0.85 (high positive correlation; minor reorderings acceptable, drastic divergence = bug).

---

## 4. Bias Correction Validation on Drift-Heavy Instrument

**What to do**: Repeat step 3 on a strategy databank for an instrument with strong secular price drift (gold, a long-term equity index, etc.).

**Why it matters**: This is where ATR normalization should show its value — the price-level bias should manifest as systematic ranking divergences, concentrated on strategies whose trades cluster in high-price stretches.

**How to verify**:
1. Create or locate a historical dataset with 50+ strategies on a drift-heavy instrument.
2. Execute both v2 and ATR tests.
3. Compare rankings as in step 3.
4. Identify strategies where the two tests diverge most.
5. For those strategies, inspect the trade distribution: do their trades cluster in high-price periods?

**Acceptance**: Systematic divergences appear, correlating with trade clustering in high-price periods. Correlation may be lower than in step 3 (< 0.7 is acceptable; < 0.4 suggests misalignment).

---

## 5. ATR Period Sensitivity (Manual, One-Time Validation)

**What to do**: Execute the test on the same `SynthTestFiltered - OOS` databank **three more times**, each with a different ATR period.

**Why it matters**: The SL in phase 2 will use a different (unknown) ATR period. This confirms the ranking is robust to period choice, not specific to 14.

**How to verify**:
1. Run the task four times total, changing only the `ATRPeriod` argument:
   - `ATRPeriod=14` (already done in step 2)
   - `ATRPeriod=50`
   - `ATRPeriod=200`
   - `ATRPeriod=350`

2. For each run, extract the percentile rank of all 13 strategies.

3. Compare rankings across the four periods:
   - **Absolute values** will change (longer ATR is larger, so same displacement = fewer "ATRs captured").
   - **Ranking order** should remain substantially stable.

4. Document which strategies reorder and by how much.

**Acceptance**: Strategy order remains stable (< 3 positions of reordering per strategy across the four periods). If ranking reorders drastically, the edge is period-specific (important to know before trusting the test).

---

## 6. Debug Block Audit

**What to do**: Execute with `Debug` flag and inspect the `ATR STATS` block in `MonkeyTest_ATR_v1_debug.log`.

**Why it matters**: Confirms ATR floor diagnostics and detects any systematic data issues.

**How to verify**:
1. **ATR floor hits**: The count of trades touching the `tickSize` floor should be **zero** on EURUSD H4 (and most normal data).
   - If non-zero: data may be corrupted or over-rellenated with empty bars.
   - If this appears, stop and inspect the data source.

2. **LAYOUT block**: Audit the `LAYOUT (monkey #0)` section manually (this is inherited from v2, but good to spot-check).
   - Verify no trade overlaps in the entry times.

3. **ATR-to-displacement correlation**: Read `correlation(ATR_in, atrDisp)` in the stats.
   - **High absolute value** (|r| > 0.7) warns that edge concentrates in one volatility regime.
   - This is diagnostic information: if true, the edge may diverge from monetary expectation when SL money management is added in phase 2.

**Acceptance**: Floor hits = 0; layout clean; correlation value recorded (no pass/fail here, just observation).

---

## 7. Databank Columns Display

**What to do**: Open the Databank grid view and inspect the new columns.

**Why it matters**: Confirms columns render correctly and show expected values.

**How to verify**:
1. **"Monkey ATR Normalized Pips Profit"** and **"Monkey ATR Edge Per Trade"**:
   - Values should differ between strategies (not all identical or `N/A`).
   - Values should be `N/A` for any period where the test was not run.

2. **"Monkey Test"** and **"Monkey Z-Score"**:
   - These should display ATR results (from `MonkeyATRPercentile<sfx>` and `MonkeyATRZScore<sfx>`) with precedence over v2 results.
   - Hover tooltips should mention both tests and the precedence rule.

3. **Mathematical consistency**:
   - Take "Monkey ATR Normalized Pips Profit" ÷ (trade count for that strategy) = "Monkey ATR Edge Per Trade".
   - Pick 3 strategies and verify this division holds.

**Acceptance**: All columns render, values are sensible, tooltips are correct, division checks out.

---

## 8. CVSintetica Unaffected

**What to do**: After running the ATR test, check `CVSintetica_V08` calculations on the same databank.

**Why it matters**: `CVSintetica` reads `MonkeyTestMedianProfit<sfx>` from v2. If ATR test corrupts that key or changes the v2 behavior, the calculation breaks silently.

**How to verify**:
1. Inspect `CVSintetica` output columns before and after running the ATR test.
2. Verify that `PassRateAgainstMonkeys` (or equivalent) still reads from the v2 test, not ATR.
3. Re-run `CVSintetica` on the same databank and confirm the results are **unchanged**.

**Acceptance**: CVSintetica results are identical before and after ATR test execution.

---

## Summary of Required Approvals

| Step | Approval | Notes |
|---|---|---|
| 1 | ATR values match SQX indicator | Must pass first; gates all others |
| 2 | Coexistence, no warnings, ExposureRatio = 1.0 | Structural integrity |
| 3 | High rank correlation on EURUSD | Validates no false-positive bias |
| 4 | Rank divergence on drift-heavy instrument | Validates bias correction actually works |
| 5 | Ranking stable across ATR periods 14, 50, 200, 350 | Robustness to period choice |
| 6 | ATR floor = 0, layout clean, correlation logged | Diagnostic cleanliness |
| 7 | Columns render, values sensible, division checks | User-facing correctness |
| 8 | CVSintetica unchanged after ATR test | No regression in dependent code |

All steps can run in parallel after step 1 passes.

---

## Notes

- The code is **clean and compilation-verified** against SQX stubs.
- No commits yet — all work is staged in the worktree.
- Five new files created: `MonkeyTest_ATR_v1_00.java`, `MonkeyTest_ATR_v1_00_ENG.md`, `MonkeyTest_ATR_v1_00_SPA.md`, `MonkeyATRNormPipsProfit.java`, `MonkeyATREdgePerTrade.java`.
- Two existing files modified (additive only): `MonkeyTestColumn.java`, `MonkeyTestZScoreColumn.java`.
- Four project rules files modified: `CLAUDE.md`, `.clinerules`, `.cursorrules`, `.geminirules` (all synchronized with Regla 6).
- Documentation has been revised to eliminate all prior-knowledge assumptions and clearly delimit scope.

