package SQ.CustomAnalysis;

import com.strategyquant.datalib.consts.Precisions;
import com.strategyquant.datalib.session.Session;
import com.strategyquant.lib.SettingsMap;
import com.strategyquant.lib.XMLUtil;
import com.strategyquant.tradinglib.*;
import com.strategyquant.tradinglib.engine.BacktestEngine;
import com.strategyquant.tradinglib.options.TradingOptions;
import com.strategyquant.tradinglib.results.SpecialValues;
import com.strategyquant.tradinglib.simulator.ITradingSimulator;
import org.jdom2.Element;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CVSintetica_V08
 *
 * Mejoras sobre V07:
 * - Paralelización del bucle principal de backtests sintéticos utilizando ExecutorService.
 * - Reduce considerablemente los tiempos de procesamiento en procesadores multi-núcleo.
 */
public class CVSintetica_V08 extends CustomAnalysisMethod {

    private static final int DEFAULT_SYNTHETIC_COUNT = 100;
    private static final String DEFAULT_PREFIX = "XAUUSD_Darwinex_sim";

    // Carpeta de volcado de logs: la del propio snippet, para tener los diagnósticos junto
    // al código. Se expresa RELATIVA a la raíz de instalación de SQX (que es el working
    // directory de la JVM), de modo que sigue siendo válida tras reinstalar SQX, mover la
    // instalación o clonarla en otro equipo. Único sitio a tocar si el destino cambia.
    private static final String LOG_DIR = "user/extend/Snippets/SQ/CustomAnalysis";

    // Número máximo de partes OOS numeradas que soporta SQX (OutOfSample1..10 == 21..30).
    private static final int MAX_OOS_PARTS = 10;

    private static volatile boolean logFailureReported = false;

    // Execution type interno de los simuladores MetaTrader5 (Hedged y Netted comparten
    // el mismo campo/lógica en checkPriceLevelCorrectness() — no es un proxy de tipo de
    // cuenta, es un eje ortogonal). No es una opción de proyecto/estrategia en SQX (no
    // existe en el XML de <Setup>, SettingsKeys, TradingOptions ni en ningún panel de la
    // UI) — es un parámetro fijo de la implementación del simulador, no heredable.
    // Verificado empíricamente (retest de control trade-a-trade contra el Databank,
    // 0 diferencias) que SQX usa 4 al generar el backtest original con motor
    // "MetaTrader5 (hedged)"; con 2 (valor previo) se perdían operaciones límite.
    // NOTA: validado solo contra MetaTrader5 (hedged); no probado aún contra Netted
    // (aunque ambas clases comparten exactamente la misma lógica de comparación, por lo
    // que es razonable esperar que aplique igual). No aplica a MetaTrader4/Tradestation/
    // NinjaTrader/JForex/Stockpicker: esos simuladores no tienen este parámetro (ver
    // rama else en createSimulatorStrict).
    private static final byte MT5_EXECUTION_TYPE = 4;

    public CVSintetica_V08() {
        super("CVSintetica_V08", TYPE_FILTER_STRATEGY);
    }

    private static class SynthTaskResult {
        int index;
        String symbol;
        BacktestRunInfo info;
        Throwable exception;
        boolean isBadStrategy;
    }

    /**
     * Un periodo a analizar: el sample type de SQX, el sufijo con el que se publican sus
     * SpecialValues, y si realmente existe en esta estrategia (false sólo cuando el usuario
     * pide explícitamente una parte OOS que esta estrategia no tiene).
     */
    private static class PeriodDef {
        final byte sampleType;
        final String suffix;
        final boolean exists;

        PeriodDef(byte sampleType, String suffix, boolean exists) {
            this.sampleType = sampleType;
            this.suffix = suffix;
            this.exists = exists;
        }
    }

    /**
     * Lectura de un periodo concreto. `exists` distingue "SQX no computó estas stats" de
     * "el periodo existe pero no operó" — conflatir ambos casos falsearía las métricas.
     */
    private static class PeriodSample {
        boolean exists;
        double profit;
        double sharpe;
        int trades;
    }

    @Override
    public boolean filterStrategy(String project, String task, String databankName, ResultsGroup rg) throws Exception {
        logDebug("==========================================================================");
        logDebug("=== START filterStrategy for strategy: " + rg.getName() + " ===");
        logDebug("project: " + project + ", task: " + task + ", databankName: " + databankName);

        Result mainResult = rg.mainResult();
        if (mainResult == null) {
            logDebug("mainResult is null, skipping strategy " + rg.getName());
            return true;
        }

        String inputArgs = getInputArgs();
        String synthPrefix = DEFAULT_PREFIX;
        String targetPeriod = "FULL";
        int syntheticCount = DEFAULT_SYNTHETIC_COUNT;
        boolean debugTradesCompare = false;

        if (inputArgs != null && !inputArgs.trim().isEmpty()) {
            String[] parts = inputArgs.split(",");
            if (parts.length > 0) {
                synthPrefix = parts[0].trim();
            }
            if (parts.length > 1) {
                targetPeriod = parts[1].trim().toUpperCase();
            }
            if (parts.length > 2) {
                try {
                    syntheticCount = Integer.parseInt(parts[2].trim());
                    if (syntheticCount <= 0) {
                        logDebug("Synthetic count must be positive. Provided: " + syntheticCount + ". Falling back to: " + DEFAULT_SYNTHETIC_COUNT);
                        syntheticCount = DEFAULT_SYNTHETIC_COUNT;
                    }
                } catch (NumberFormatException e) {
                    logDebug("Invalid third argument for synthetic count: '" + parts[2] + "', using default: " + DEFAULT_SYNTHETIC_COUNT);
                    syntheticCount = DEFAULT_SYNTHETIC_COUNT;
                }
            }
            if (parts.length > 3) {
                debugTradesCompare = "Debug".equalsIgnoreCase(parts[3].trim());
            }
        }

        logDebug("inputArgs: " + inputArgs + " -> synthPrefix: " + synthPrefix + ", targetPeriod: " + targetPeriod + ", syntheticCount: " + syntheticCount + ", debugTradesCompare: " + debugTradesCompare);

        String originalSymbol = resolveOriginalSymbol(rg, mainResult);
        logDebug("originalSymbol resolved: " + originalSymbol);

        // El OOS se resuelve UNA sola vez por estrategia y se reutiliza en el run de control
        // y en los N sintéticos. Antes se resolvía 1+N veces (101 por defecto), cada una
        // parseando el XML completo y emitiendo ~8 líneas por logDebug, que es
        // static synchronized y abre/cierra fichero por línea: un punto de serialización
        // global entre los hilos del executor.
        final com.strategyquant.tradinglib.strategy.OutOfSample resolvedOOS = resolveOOS(rg);
        int[] oosPartTags = enumerateOOSPartTags(resolvedOOS);
        logDebug("[" + rg.getName() + "] OOS parts detected: " + oosPartTags.length);

        ArrayList<PeriodDef> periods = buildPeriodTable(targetPeriod, oosPartTags, rg.getName());
        int nP = periods.size();

        int fullIdx = -1;
        for (int i = 0; i < nP; i++) {
            if (periods.get(i).sampleType == SampleTypes.FullSample) {
                fullIdx = i;
                break;
            }
        }

        double[] originalProfits = new double[nP];
        int[] originalTrades = new int[nP];
        boolean[] originalStatsMissing = new boolean[nP];

        int originalRetestFailed = 0;
        int originalRetestBadStrategy = 0;
        int originalUsedFallback = 0;

        String requestedEngine = "UNKNOWN";
        String normalizedEngine = "UNKNOWN";
        String originalSimulatorClass = "N/A";
        int originalChartEngineApplied = 0;
        int originalChartEngineApplyFailed = 0;
        String originalRetestException = "";

        if (originalSymbol != null && !originalSymbol.trim().isEmpty()) {
            try {
                logDebug("[" + rg.getName() + "] RETESTING ORIGINAL SYMBOL: " + originalSymbol);
                BacktestRunInfo info = runBacktestWithInheritedSettings(rg, originalSymbol, true, debugTradesCompare, resolvedOOS);

                requestedEngine = info.requestedEngine;
                normalizedEngine = info.normalizedEngine;
                originalSimulatorClass = info.simulatorClass;
                originalChartEngineApplied = info.chartEngineApplied ? 1 : 0;
                originalChartEngineApplyFailed = info.chartEngineApplyFailed ? 1 : 0;

                // Extraemos valores reales para cada periodo activo
                for (int p = 0; p < nP; p++) {
                    PeriodDef pd = periods.get(p);
                    if (!pd.exists) {
                        continue;
                    }

                    PeriodSample s = readSample(info.results, pd.sampleType);
                    if (!s.exists) {
                        originalStatsMissing[p] = true;
                        logDebug("[" + rg.getName() + "] WARNING: ORIGINAL has no computable stats for period " + pd.suffix + " (type=" + pd.sampleType + "). OverfittingRatio" + pd.suffix + " will not be meaningful.");
                        continue;
                    }

                    originalProfits[p] = s.profit;
                    originalTrades[p] = s.trades;
                    logDebug("[" + rg.getName() + "] ORIGINAL RESULT FOR PERIOD " + pd.suffix + " (type=" + pd.sampleType + "): Profit=" + s.profit + ", Trades=" + s.trades);
                }

            } catch (BadStrategyException e) {
                originalRetestFailed = 1;
                originalRetestBadStrategy = 1;
                originalUsedFallback = 1;
                originalRetestException = shortError(e);
                for (int p = 0; p < nP; p++) {
                    originalStatsMissing[p] = true;
                }
                logDebug("[" + rg.getName() + "] ORIGINAL RETEST BadStrategyException: " + originalRetestException);
            } catch (Exception e) {
                originalRetestFailed = 1;
                originalUsedFallback = 1;
                originalRetestException = shortError(e);
                for (int p = 0; p < nP; p++) {
                    originalStatsMissing[p] = true;
                }
                logDebug("[" + rg.getName() + "] ORIGINAL RETEST Exception: " + originalRetestException);
            }
        } else {
            originalRetestFailed = 1;
            originalUsedFallback = 1;
            originalSymbol = "N/A";
            originalRetestException = "Original symbol could not be resolved";
            for (int p = 0; p < nP; p++) {
                originalStatsMissing[p] = true;
            }
            logDebug("[" + rg.getName() + "] ORIGINAL RETEST FAILED: symbol empty");
        }

        // Listas para almacenar beneficios netos sintéticos por cada periodo
        ArrayList<ArrayList<Double>> periodProfits = new ArrayList<>();
        ArrayList<ArrayList<Double>> periodSharpes = new ArrayList<>();
        for (int p = 0; p < nP; p++) {
            periodProfits.add(new ArrayList<Double>());
            periodSharpes.add(new ArrayList<Double>());
        }

        int[] periodSuccessCounts = new int[nP];
        int[] periodFailCounts = new int[nP];
        int[] periodBadStrategyCounts = new int[nP];
        int[] periodExceptionCounts = new int[nP];
        int[] periodMissingStatsCounts = new int[nP];

        int synthSameBarErrorCount = 0;
        int synthChartEngineApplyFailedCount = 0;

        String lastSyntheticException = "";
        String lastSyntheticSimulatorClass = "N/A";

        logDebug("[" + rg.getName() + "] STARTING PARALLEL LOOP FOR " + syntheticCount + " SYNTHETIC SYMBOLS. Prefix: " + synthPrefix);

        // Preparar lista de tareas para ejecución paralela
        final boolean debugTradesCompareFinal = debugTradesCompare;
        ArrayList<Callable<SynthTaskResult>> tasks = new ArrayList<>();
        for (int i = 1; i <= syntheticCount; i++) {
            final int index = i;
            final String synthSymbol = String.format("%s%03d", synthPrefix, index);
            tasks.add(new Callable<SynthTaskResult>() {
                @Override
                public SynthTaskResult call() {
                    SynthTaskResult res = new SynthTaskResult();
                    res.index = index;
                    res.symbol = synthSymbol;
                    try {
                        res.info = runBacktestWithInheritedSettings(rg, synthSymbol, false, debugTradesCompareFinal, resolvedOOS);
                    } catch (BadStrategyException e) {
                        res.isBadStrategy = true;
                        res.exception = e;
                    } catch (Exception e) {
                        res.exception = e;
                    }
                    return res;
                }
            });
        }

        // Ejecutar tareas concurrentemente usando un ThreadPool dedicado
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        logDebug("[" + rg.getName() + "] Launching parallel execution with " + cores + " threads.");
        ExecutorService executor = Executors.newFixedThreadPool(cores);
        List<Future<SynthTaskResult>> futures = null;
        try {
            futures = executor.invokeAll(tasks);
        } finally {
            executor.shutdown(); // Liberar recursos del pool
        }

        // Procesar resultados de manera ordenada en el hilo principal
        if (futures != null) {
            for (Future<SynthTaskResult> f : futures) {
                try {
                    SynthTaskResult res = f.get();
                    if (res.info != null) {
                        lastSyntheticSimulatorClass = res.info.simulatorClass;

                        if (res.info.chartEngineApplyFailed) {
                            synthChartEngineApplyFailedCount++;
                        }

                        // Procesamos únicamente los periodos activos de manera independiente
                        for (int p = 0; p < nP; p++) {
                            PeriodDef pd = periods.get(p);
                            if (!pd.exists) {
                                continue;
                            }

                            PeriodSample s = readSample(res.info.results, pd.sampleType);

                            // Stats no computadas != periodo sin operaciones. Un hueco de
                            // medición no puede contarse como pérdida: se excluye de la
                            // muestra y del denominador del Pass Rate.
                            if (!s.exists) {
                                periodMissingStatsCounts[p]++;
                                if (res.index <= 5) {
                                    logDebug("[" + rg.getName() + "] Synth #" + res.index + " (" + res.symbol + ") period " + pd.suffix + ": NO COMPUTABLE STATS, excluded from sample");
                                }
                                continue;
                            }

                            periodProfits.get(p).add(s.profit);
                            periodSharpes.get(p).add(s.sharpe);

                            // Consideramos ganadora si el net profit es estrictamente mayor que cero y operó en el periodo
                            if (s.profit <= 0 || s.trades == 0) {
                                periodFailCounts[p]++;
                            } else {
                                periodSuccessCounts[p]++;
                            }

                            if (res.index <= 5) {
                                logDebug("[" + rg.getName() + "] Synth #" + res.index + " (" + res.symbol + ") period " + pd.suffix + ": Profit=" + s.profit + ", Trades=" + s.trades + ", Sharpe=" + s.sharpe);
                            }
                        }
                    } else {
                        lastSyntheticException = shortError(res.exception);
                        if (res.isBadStrategy) {
                            logDebug("[" + rg.getName() + "] Synth #" + res.index + " (" + res.symbol + ") BadStrategyException: " + lastSyntheticException);

                            for (int p = 0; p < nP; p++) {
                                if (!periods.get(p).exists) continue;
                                periodFailCounts[p]++;
                                periodBadStrategyCounts[p]++;
                            }

                            if (containsTooManyTradesSameBar(res.exception)) {
                                synthSameBarErrorCount++;
                            }
                        } else {
                            logDebug("[" + rg.getName() + "] Synth #" + res.index + " (" + res.symbol + ") Exception: " + lastSyntheticException);
                            for (int p = 0; p < nP; p++) {
                                if (!periods.get(p).exists) continue;
                                periodFailCounts[p]++;
                                periodExceptionCounts[p]++;
                            }
                        }
                    }
                } catch (Exception e) {
                    logDebug("Error retrieving task result: " + e.getMessage());
                }
            }
        }

        // Calculamos estadísticas por periodo activo y guardamos en SpecialValues
        boolean separateMetricsSuspect = false;
        int existingPeriodCount = 0;
        int noDataPeriodCount = 0;

        for (int p = 0; p < nP; p++) {
            PeriodDef pd = periods.get(p);
            String suffix = pd.suffix;

            // Este periodo forma parte del alcance del run actual: se limpia cualquier
            // valor residual de un run anterior antes de recalcular, para no mostrar datos
            // de un test viejo como si fueran de este test (ver clearPeriodSynthKeys).
            clearPeriodSynthKeys(rg, suffix);

            // Parte OOS pedida explícitamente que esta estrategia no tiene: no publicamos
            // ninguna métrica (las columnas mostrarán N/A) en lugar de fabricar un
            // PassRate 0% / profit 0 que parecería un resultado real y malo.
            if (!pd.exists) {
                rg.specialValues().set("CA_SynthPartMissing" + suffix, 1);
                continue;
            }

            if (periodMissingStatsCounts[p] > 0) {
                separateMetricsSuspect = true;
                logDebug("[" + rg.getName() + "] WARNING: " + periodMissingStatsCounts[p] + " of " + syntheticCount + " synthetic runs had no computable stats for " + suffix + ". Likely cause: the global setting 'ComputeSeparateMetrics' is disabled.");
            }

            ArrayList<Double> profitsList = periodProfits.get(p);
            // Si ninguna simulación sintética produjo estadísticas computables (p. ej. el
            // prefijo/nombre de la data sintética no existe), profitsList queda vacía: mean()
            // y stdevSample() devolverían 0.0 por diseño, un valor que parece "medido y es
            // cero" en vez de "no hay nada que medir".
            boolean syntheticStatsAvailable = !profitsList.isEmpty();
            existingPeriodCount++;
            if (!syntheticStatsAvailable) {
                noDataPeriodCount++;
            }
            double mean = mean(profitsList);
            double stdev = stdevSample(profitsList, mean);
            double origProfit = originalProfits[p];

            // 1. OverfittingRatio (Z-Score con signo). Si el profit original no es fiable
            // (run de control falló o el periodo no tuvo stats) o no hay datos sintéticos
            // con los que compararlo, no se publica: un 0 falso produciría un Z-Score que
            // parece válido pero no compara nada real.
            boolean overfittingReliable = !originalStatsMissing[p] && syntheticStatsAvailable;
            double overfittingRatio = (overfittingReliable && stdev > 0.0) ? (origProfit - mean) / stdev : 0.0;

            // 2. Synthetic_Ratio (Filtro de Ergodicidad transversal)
            double syntheticRatio = (stdev > 0.0) ? mean / stdev : 0.0;

            // 3. Pass_Rate (Survival Rate de 0.0 a 1.0). El denominador son las simulaciones
            // realmente evaluadas: en cualquier escenario que funciona hoy missing==0 y el
            // valor es idéntico al anterior.
            int evaluated = syntheticCount - periodMissingStatsCounts[p];
            double passRate = (evaluated > 0) ? ((double) periodSuccessCounts[p] / evaluated) : 0.0;

            // 4. Sharpe medio de las simulaciones individuales
            double meanSharpe = mean(periodSharpes.get(p));

            if (originalStatsMissing[p]) {
                rg.specialValues().set("CA_SynthOriginalStatsMissing" + suffix, 1);
            }
            if (!syntheticStatsAvailable) {
                rg.specialValues().set("CA_SynthNoData" + suffix, 1);
            }
            rg.specialValues().set("CA_SynthMissingStatsCount" + suffix, periodMissingStatsCounts[p]);

            // Media, desviación, ratio sintético, sharpe medio y pass rate dependen todos de
            // profitsList/periodSharpes: si ninguna simulación produjo estadísticas, no se
            // publican (las columnas mostrarán N/A) en vez de fabricar ceros engañosos.
            if (syntheticStatsAvailable) {
                rg.specialValues().set("CA_SynthMeanProfit" + suffix, mean);
                rg.specialValues().set("CA_SynthStdevProfit" + suffix, stdev);
                rg.specialValues().set("CA_SyntheticRatio" + suffix, syntheticRatio);
                rg.specialValues().set("CA_SynthMeanSharpe" + suffix, meanSharpe);
                rg.specialValues().set("CA_PassRate" + suffix, passRate);
            }
            if (overfittingReliable) {
                rg.specialValues().set("CA_OverfittingRatio" + suffix, overfittingRatio);
            }
            rg.specialValues().set("CA_OriginalProfit" + suffix, origProfit);
            rg.specialValues().set("CA_OriginalTrades" + suffix, originalTrades[p]);
            rg.specialValues().set("CA_SynthSuccessCount" + suffix, periodSuccessCounts[p]);
            rg.specialValues().set("CA_SynthFailCount" + suffix, periodFailCounts[p]);

            // 4. SinteticNetProfits (CSV de los profits sintéticos)
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < profitsList.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append(String.format(java.util.Locale.US, "%.2f", profitsList.get(j)));
            }
            rg.specialValues().set("CA_SinteticNetProfits" + suffix, sb.toString());
        }

        // Guardar variables de retrocompatibilidad (Full Sample)
        if (targetPeriod.equals("FULL") && fullIdx >= 0) {
            clearFullSynthKeys(rg);
            boolean syntheticStatsAvailableFull = !periodProfits.get(fullIdx).isEmpty();
            double meanFull = mean(periodProfits.get(fullIdx));
            double stdevFull = stdevSample(periodProfits.get(fullIdx), meanFull);
            double origProfitFull = originalProfits[fullIdx];
            boolean overfittingReliableFull = !originalStatsMissing[fullIdx] && syntheticStatsAvailableFull;
            double zFull = (overfittingReliableFull && stdevFull > 0.0) ? (origProfitFull - meanFull) / stdevFull : 0.0;
            double meanFullSharpe = mean(periodSharpes.get(fullIdx));

            if (syntheticStatsAvailableFull) {
                rg.specialValues().set("CA_SynthMeanProfit", meanFull);
                rg.specialValues().set("CA_SynthStdevProfit", stdevFull);
                rg.specialValues().set("CA_SynthMeanSharpe", meanFullSharpe);
            } else {
                rg.specialValues().set("CA_SynthNoData", 1);
            }
            if (overfittingReliableFull) {
                rg.specialValues().set("CA_SynthZScoreProfit", zFull);
                rg.specialValues().set("CA_OverfittingRatio", zFull);
            }
            rg.specialValues().set("CA_OriginalProfit", origProfitFull);
            rg.specialValues().set("CA_OriginalTrades", originalTrades[fullIdx]);
            rg.specialValues().set("CA_SynthFailCount", periodFailCounts[fullIdx]);
            rg.specialValues().set("CA_SynthSuccessCount", periodSuccessCounts[fullIdx]);

            rg.specialValues().set("CA_SynthBadStrategyCount", periodBadStrategyCounts[fullIdx]);
            rg.specialValues().set("CA_SynthExceptionCount", periodExceptionCounts[fullIdx]);
        }

        // Guardado de control
        rg.specialValues().set("CA_SynthTargetPeriod", targetPeriod);
        rg.specialValues().set("CA_SynthOOSPartsAvailable", oosPartTags.length);
        rg.specialValues().set("CA_SynthOOSResolved", resolvedOOS != null ? 1 : 0);
        rg.specialValues().set("CA_SynthSeparateMetricsSuspect", separateMetricsSuspect ? 1 : 0);
        rg.specialValues().set("CA_SynthRequestedCount", syntheticCount);
        rg.specialValues().set("CA_SynthSameBarErrorCount", synthSameBarErrorCount);
        rg.specialValues().set("CA_SynthChartEngineApplyFailedCount", synthChartEngineApplyFailedCount);

        rg.specialValues().set("CA_OriginalRetestFailed", originalRetestFailed);
        rg.specialValues().set("CA_OriginalRetestBadStrategy", originalRetestBadStrategy);
        rg.specialValues().set("CA_OriginalUsedFallback", originalUsedFallback);

        rg.specialValues().set("CA_OriginalSymbol", originalSymbol);
        rg.specialValues().set("CA_SynthPrefix", synthPrefix);

        rg.specialValues().set("CA_RequestedEngine", requestedEngine);
        rg.specialValues().set("CA_NormalizedEngine", normalizedEngine);
        rg.specialValues().set("CA_SimulatorClass", originalSimulatorClass);
        rg.specialValues().set("CA_ChartEngineApplied", originalChartEngineApplied);
        rg.specialValues().set("CA_ChartEngineApplyFailed", originalChartEngineApplyFailed);
        rg.specialValues().set("CA_OriginalRetestException", originalRetestException);
        rg.specialValues().set("CA_LastSyntheticException", lastSyntheticException);
        rg.specialValues().set("CA_LastSyntheticSimulatorClass", lastSyntheticSimulatorClass);

        // Marca visual en la columna "Filters result" (SQ.Columns.Databanks.FiltersResult):
        // si el test no pudo evaluarse de forma fiable, se muestra FAILED con el motivo en el
        // tooltip en vez de dejar la estrategia sin ningún indicador visual del problema. No
        // excluye la estrategia del databank (filterStrategy sigue devolviendo true): es un
        // fallo de configuración/infraestructura del test, no un juicio sobre la estrategia.
        //
        // Prioridad de diagnóstico (el primero que aplique gana):
        // 1. Ningún periodo solicitado existe en esta estrategia (p.ej. se pidió OOS5 y sólo
        //    tiene 2 partes OOS) - ni siquiera se llegó a evaluar nada.
        // 2. Falla el run de control - con sub-causa (símbolo no resuelto / BadStrategyException
        //    / excepción genérica).
        // 3. Ninguna simulación sintética produjo datos - con la causa dominante entre las N
        //    simulaciones (ComputeSeparateMetrics desactivado / errores same-bar / BadStrategyException
        //    / excepción genérica / mezcla sin causa dominante).
        boolean noPeriodExists = existingPeriodCount == 0;
        boolean allSyntheticDataMissing = existingPeriodCount > 0 && noDataPeriodCount == existingPeriodCount;
        boolean testCouldNotBeEvaluated = noPeriodExists || (originalRetestFailed == 1) || allSyntheticDataMissing;

        if (testCouldNotBeEvaluated) {
            String reason;
            if (noPeriodExists) {
                reason = "Requested period \"" + sanitizeForTooltip(targetPeriod) + "\" does not exist for this strategy.";
            } else if (originalRetestFailed == 1) {
                if (originalSymbol.equals("N/A")) {
                    reason = "The original symbol could not be resolved.";
                } else if (originalRetestBadStrategy == 1) {
                    reason = "Control backtest threw a BadStrategyException (" + sanitizeForTooltip(originalRetestException) + ").";
                } else {
                    reason = "Control backtest failed (" + sanitizeForTooltip(originalRetestException) + ").";
                }
            } else {
                int repIdx = -1;
                for (int p = 0; p < nP; p++) {
                    if (periods.get(p).exists) {
                        repIdx = p;
                        break;
                    }
                }

                if (repIdx >= 0 && periodMissingStatsCounts[repIdx] == syntheticCount) {
                    reason = "All " + syntheticCount + " synthetics ran without separate stats (check \"ComputeSeparateMetrics\").";
                } else if (synthSameBarErrorCount >= syntheticCount) {
                    reason = "All " + syntheticCount + " synthetics failed: too many trades on the same bar.";
                } else if (repIdx >= 0 && periodBadStrategyCounts[repIdx] == syntheticCount) {
                    reason = "All " + syntheticCount + " synthetics failed with a BadStrategyException.";
                } else if (repIdx >= 0 && periodExceptionCounts[repIdx] == syntheticCount) {
                    reason = "All " + syntheticCount + " synthetics failed (" + sanitizeForTooltip(lastSyntheticException) + "). Check prefix \"" + sanitizeForTooltip(synthPrefix) + "\".";
                } else {
                    reason = "None of " + syntheticCount + " synthetics (prefix \"" + sanitizeForTooltip(synthPrefix) + "\") produced data.";
                }
            }
            rg.specialValues().set(SpecialValues.FiltersResultFailedReason, reason);
        } else {
            // Limpia un veredicto FAILED de un run anterior: sin esto, una estrategia que
            // falló una vez y luego se testea con éxito quedaría marcada FAILED para siempre.
            rg.specialValues().set(SpecialValues.FiltersResultFailedReason, SpecialValues.FiltersResultPassed);
        }

        return true;
    }

    /**
     * Ejecuta un nuevo backtest intentando heredar la configuración original.
     */
    private BacktestRunInfo runBacktestWithInheritedSettings(ResultsGroup source, String targetSymbol, boolean isControlRun, boolean debugTradesCompare) throws Exception {
        return runBacktestWithInheritedSettings(source, targetSymbol, isControlRun, debugTradesCompare, null);
    }

    /**
     * @param preResolvedOOS OOS ya resuelto por el llamante para no repetir el parseo del XML
     *                       en cada uno de los N backtests. Si es null se resuelve aquí.
     *                       SÓLO SE LEE: mutarlo (addRange/setFromXML) renumeraría las partes
     *                       y corrompería todas las ejecuciones concurrentes que lo comparten.
     */
    private BacktestRunInfo runBacktestWithInheritedSettings(ResultsGroup source, String targetSymbol, boolean isControlRun, boolean debugTradesCompare,
                                                             com.strategyquant.tradinglib.strategy.OutOfSample preResolvedOOS) throws Exception {
        logDebug("[" + source.getName() + "] [runBacktestWithInheritedSettings] targetSymbol: " + targetSymbol);

        Result mainResult = source.mainResult();
        if (mainResult == null) {
            logDebug("[" + source.getName() + "] ERROR: mainResult is null");
            throw new Exception("mainResult is null");
        }

        SettingsMap baseSettings = mainResult.getSettings();
        if (baseSettings == null) {
            logDebug("[" + source.getName() + "] ERROR: mainResult.getSettings() returned null");
            throw new Exception("mainResult.getSettings() returned null");
        }

        SettingsMap settings = baseSettings.clone();

        Element elStrategy = source.getStrategyXml();
        if (elStrategy == null) {
            logDebug("[" + source.getName() + "] ERROR: Strategy XML not found");
            throw new Exception("Strategy XML not found");
        }

        StrategyBase strategy = StrategyBase.createXmlStrategy(elStrategy.clone(), source.getName());
        settings.set(SettingsKeys.StrategyObject, strategy);
        settings.set(SettingsKeys.StrategyXml, elStrategy.clone());
        settings.set(SettingsKeys.StrategyName, source.getName());

        Object originalTradingOptions = settings.get(SettingsKeys.TradingOptions);
        if (originalTradingOptions instanceof TradingOptions) {
            settings.set(SettingsKeys.TradingOptions, ((TradingOptions) originalTradingOptions).getClone());
        }

        String lastSettingsXml = source.getLastSettings();

        // Inherit Money Management, Trading Options, Commissions and Swap from lastSettingsXml if available
        if (lastSettingsXml != null && !lastSettingsXml.trim().isEmpty()) {
            try {
                Element rootSettings = XMLUtil.stringToElement(lastSettingsXml);
                if (rootSettings != null) {
                    Element mainSetup = getSetupElement(lastSettingsXml);

                    // 1. Inherit Money Management (Targeting main Setup first)
                    Element elMM = (mainSetup != null && mainSetup.getChild("MoneyManagement") != null) ? mainSetup.getChild("MoneyManagement") : findElementRecursive(rootSettings, "MoneyManagement");
                    if (elMM != null) {
                        com.strategyquant.tradinglib.MoneyManagementMethod mmm = 
                            com.strategyquant.tradinglib.moneymanagement.MoneyManagementMethodsList.get().loadMMMethodFromXML(elMM);
                        if (mmm != null) {
                            settings.set(SettingsKeys.MoneyManagement, mmm);
                            logDebug("[" + source.getName() + "] Inherited MoneyManagement successfully: " + mmm.getClass().getName());
                        }
                    } else {
                        logDebug("[" + source.getName() + "] MoneyManagement tag not found in lastSettingsXml.");
                    }

                    // 2. Inherit Trading Options (Targeting main Setup first, preserving RealisticGapsHandling)
                    Element elTO = (mainSetup != null && mainSetup.getChild("BuildTradingOptions") != null) ? mainSetup.getChild("BuildTradingOptions") : findElementRecursive(rootSettings, "BuildTradingOptions");
                    if (elTO != null) {
                        com.strategyquant.tradinglib.options.TradingOptions to =
                            com.strategyquant.tradinglib.options.TradingOptionsList.getInstance().parseOptionsFromXml(elTO);
                        if (to != null) {
                            settings.set(SettingsKeys.TradingOptions, to);
                            logDebug("[" + source.getName() + "] Inherited TradingOptions successfully.");
                        }
                    } else {
                        logDebug("[" + source.getName() + "] BuildTradingOptions tag not found in lastSettingsXml.");
                    }

                    // 3. Inherit Commissions (Targeting main Setup first)
                    Element elCommissions = (mainSetup != null && mainSetup.getChild("Commissions") != null) ? mainSetup.getChild("Commissions") : findElementRecursive(rootSettings, "Commissions");
                    if (elCommissions != null) {
                        Element elCommMethod = elCommissions.getChild("Method");
                        if (elCommMethod != null) {
                            String type = elCommMethod.getAttributeValue("type");
                            if (type != null && !type.trim().isEmpty()) {
                                try {
                                    String fullClassName = "SQ.Trading.Commissions." + type;
                                    Class<?> clazz = Class.forName(fullClassName);
                                    com.strategyquant.tradinglib.CommissionsMethod commissionsMethod =
                                        (com.strategyquant.tradinglib.CommissionsMethod) clazz.getDeclaredConstructor().newInstance();
                                    commissionsMethod.setFromXML(elCommissions);
                                    settings.set(SettingsKeys.Commission, commissionsMethod);
                                    logDebug("[" + source.getName() + "] Inherited Commission successfully: type=" + type + " class=" + commissionsMethod.getClass().getName());
                                } catch (Exception eComm) {
                                    logDebug("[" + source.getName() + "] WARNING: Could not load Commission type '" + type + "': " + eComm.getMessage());
                                }
                            }
                        }
                    } else {
                        logDebug("[" + source.getName() + "] Commissions tag not found in lastSettingsXml.");
                    }

                    // 4. Inherit Swap (Targeting main Setup first, preserving tripleSwapOn)
                    Element elSwap = (mainSetup != null && mainSetup.getChild("Swap") != null) ? mainSetup.getChild("Swap") : findElementRecursive(rootSettings, "Swap");
                    if (elSwap != null) {
                        try {
                            com.strategyquant.tradinglib.SwapMethod swapMethod = new com.strategyquant.tradinglib.SwapMethod();
                            swapMethod.setFromXML(elSwap);

                            String tripleDay = elSwap.getAttributeValue("tripleSwapOn");
                            if (tripleDay != null && !tripleDay.trim().isEmpty()) {
                                try {
                                    Method setTriple = swapMethod.getClass().getMethod("setTripleSwapDay", String.class);
                                    setTriple.invoke(swapMethod, tripleDay);
                                } catch (Exception e) {
                                    try {
                                        java.lang.reflect.Field f = swapMethod.getClass().getDeclaredField("tripleSwapDay");
                                        f.setAccessible(true);
                                        f.set(swapMethod, tripleDay);
                                    } catch (Exception ignored) {}
                                }
                            }

                            settings.set(SettingsKeys.Swap, swapMethod);
                            logDebug("[" + source.getName() + "] Inherited Swap successfully: use=" + swapMethod.isUsed() + " long=" + swapMethod.getLongSwap() + " short=" + swapMethod.getShortSwap() + " tripleSwapOn=" + tripleDay);
                        } catch (Exception eSwap) {
                            logDebug("[" + source.getName() + "] WARNING: Could not load Swap: " + eSwap.getMessage());
                        }
                    } else {
                        logDebug("[" + source.getName() + "] Swap tag not found in lastSettingsXml.");
                    }
                }
            } catch (Exception e) {
                logDebug("[" + source.getName() + "] WARNING: Error inheriting settings from XML: " + e.getMessage());
            }
        }


        long dateFrom = resolveDateFrom(source);
        long dateTo = resolveDateTo(source);
        logDebug("[" + source.getName() + "] Resolved dates: dateFrom = " + dateFrom + " (" + new java.util.Date(dateFrom) + "), dateTo = " + dateTo + " (" + new java.util.Date(dateTo) + ")");
        if (dateFrom <= 0 || dateTo <= 0 || dateFrom >= dateTo) {
            logDebug("[" + source.getName() + "] ERROR: Invalid original date range");
            throw new Exception("Invalid original date range");
        }

        String timeframe = resolveTimeframe(source, mainResult);
        String session = extractSetupStringAttr(lastSettingsXml, "session", Session.Forex_247);
        double spread = extractChartDoubleAttr(lastSettingsXml, "spread", 3.5);
        double slippage = extractSetupDoubleAttr(lastSettingsXml, "slippage", getDoubleSetting(settings, SettingsKeys.Slippage, 0.0));
        double minDistance = extractSetupDoubleAttr(lastSettingsXml, "minDist", getDoubleSetting(settings, SettingsKeys.MinimumDistance, 0.0));
        int testPrecision = normalizeTestPrecision(resolveTestPrecision(lastSettingsXml, settings));

        logDebug("[" + source.getName() + "] TF: " + timeframe + ", Session: " + session + ", Spread: " + spread + ", Slippage: " + slippage + ", MinDist: " + minDistance + ", Precision: " + testPrecision);

        String requestedEngine = resolveEngineName(lastSettingsXml);
        String normalizedEngine = normalizeEngineName(requestedEngine);
        logDebug("[" + source.getName() + "] requestedEngine: " + requestedEngine + " -> normalizedEngine: " + normalizedEngine);

        // Construct ChartSetup via safe constructor (avoids dataInfo null NPE)
        ChartSetup chartSetup = new ChartSetup(
                "History",
                targetSymbol,
                timeframe,
                dateFrom,
                dateTo,
                spread,
                session
        );

        applySafeChartTestPrecision(chartSetup, testPrecision);

        boolean chartEngineApplied = false;
        boolean chartEngineApplyFailed = false;

        try {
            chartEngineApplied = applyBacktestEngineToChartSetup(chartSetup, requestedEngine);
            if (!chartEngineApplied) {
                chartEngineApplyFailed = true;
            }
        } catch (Exception e) {
            chartEngineApplyFailed = true;
        }

        com.strategyquant.tradinglib.strategy.OutOfSample oosConfig = null;
        try {
            oosConfig = (preResolvedOOS != null) ? preResolvedOOS : resolveOOS(source);
            logDebug("[" + source.getName() + "] pre-backtest OOS is null: " + (oosConfig == null) + " (preResolved: " + (preResolvedOOS != null) + ")");
            if (oosConfig != null) {
                logDebug("[" + source.getName() + "] Injecting OOS into settings BEFORE backtest: " + oosConfig.toString());
                settings.set(SettingsKeys.OutOfSample, oosConfig);
            }
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            logDebug("[" + source.getName() + "] WARNING: could not inject OOS pre-backtest: " + sw.toString());
        }

        settings.set(SettingsKeys.BacktestChart, chartSetup);
        applySafeSettingTestPrecision(settings, testPrecision);
        settings.set(SettingsKeys.Slippage, slippage);
        settings.set(SettingsKeys.MinimumDistance, minDistance);

        ITradingSimulator simulator = createSimulatorStrict(normalizedEngine);
        applySafeSimulatorTestPrecision(simulator, testPrecision);
        logDebug("[" + source.getName() + "] Created simulator: " + simulator.getClass().getName());

        BacktestEngine backtestEngine = new BacktestEngine(simulator);
        backtestEngine.setSingleThreaded(true);
        backtestEngine.addSetup(settings);

        logDebug("[" + source.getName() + "] Launching backtest engine on symbol: " + targetSymbol);
        ResultsGroup results = backtestEngine.runBacktest().getResults();
        logDebug("[" + source.getName() + "] Backtest finished. results is null: " + (results == null));

        try {
            if (oosConfig != null) {
                logDebug("[" + source.getName() + "] Post-backtest: Applying OOS settings: " + oosConfig.toString());
                results.setOOSSettings(oosConfig);
                logDebug("[" + source.getName() + "] setOOSSettings successfully called.");
                results.computeAllStats();
                logDebug("[" + source.getName() + "] computeAllStats successfully called.");

                // DUMP DE TRADES PARA COMPARACIÓN DETALLADA (OOS) — solo si se pidió "Debug"
                // como 4º input argument. Se construye el bloque completo en memoria y se
                // escribe en una única operación sincronizada, para que no quede entrelazado
                // con otros hilos (control + N sintéticos) escribiendo concurrentemente al
                // mismo log.
                if (debugTradesCompare) {
                    StringBuilder tradesCompareBlock = new StringBuilder();
                    tradesCompareBlock.append("--- START COMPARE FOR ").append(source.getName())
                            .append(" [symbol=").append(targetSymbol).append(", control=").append(isControlRun).append("] ---\n");
                    if (isControlRun) {
                        dumpTrades(tradesCompareBlock, "ORIGINAL", source.getName(), targetSymbol, source, source.getMainResultKey(), com.strategyquant.tradinglib.SampleTypes.OutOfSample);
                    }
                    dumpTrades(tradesCompareBlock, "RETEST", source.getName(), targetSymbol, results, results.getMainResultKey(), com.strategyquant.tradinglib.SampleTypes.OutOfSample);
                    tradesCompareBlock.append("--- END COMPARE FOR ").append(source.getName()).append(" ---\n\n");
                    logTradesCompareBlock(tradesCompareBlock.toString());
                }
            } else {
                logDebug("[" + source.getName() + "] WARNING: oosConfig is null, skipping post-backtest OOS application.");
            }
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            logDebug("[" + source.getName() + "] ERROR applying OOS post-backtest: " + sw.toString());
        }

        BacktestRunInfo info = new BacktestRunInfo();
        info.results = results;
        info.requestedEngine = requestedEngine;
        info.normalizedEngine = normalizedEngine;
        info.simulatorClass = simulator.getClass().getName();
        info.chartEngineApplied = chartEngineApplied;
        info.chartEngineApplyFailed = chartEngineApplyFailed;

        return info;
    }

    /**
     * Lee de una vez profit, trades y sharpe de un periodo.
     *
     * Usa statsOrNull() en lugar de stats(): stats() lanza StatsDontExistException cuando la
     * combinación no fue computada, y capturar esa excepción devolviendo 0.0 haría
     * indistinguible "SQX no computó estas stats" de "el periodo existe y no operó" — que
     * aguas abajo se contaría como pérdida y falsearía mean, stdev y PassRate.
     */
    private PeriodSample readSample(ResultsGroup rg, byte sampleType) {
        PeriodSample s = new PeriodSample();

        try {
            SQStats st = rg.portfolio().statsOrNull(Directions.Both, PlTypes.Money, sampleType);
            if (st == null) {
                return s;
            }

            s.exists = true;
            s.profit = st.getDouble(StatsKey.NET_PROFIT);
            s.trades = st.getInt(StatsKey.NUMBER_OF_TRADES);

            double sharpe = st.getDouble(StatsKey.SHARPE_RATIO);
            s.sharpe = (Double.isNaN(sharpe) || Double.isInfinite(sharpe)) ? 0.0 : sharpe;
        } catch (Exception e) {
            s.exists = false;
        }

        return s;
    }

    /**
     * Enumera los sample types OOS numerados (OutOfSample1..10 == 21..30) realmente presentes
     * en la configuración OOS de la estrategia.
     *
     * Se leen los tags almacenados en vez de usar getPartsCount() porque el argumento de ese
     * método es un SELECTOR DE CATEGORÍA (sólo reconoce OutOfSample e InSampleValidation);
     * pasarle un tag numerado como 21 devuelve 0.
     */
    private int[] enumerateOOSPartTags(com.strategyquant.tradinglib.strategy.OutOfSample oos) {
        java.util.TreeSet<Integer> tags = new java.util.TreeSet<Integer>();

        if (oos == null) {
            return new int[0];
        }

        try {
            int ranges = oos.getRangesCount();
            for (int i = 0; i < ranges; i++) {
                byte t = oos.getSampleType(i);
                if (t > SampleTypes.OutOfSample && t <= (byte) (SampleTypes.OutOfSample + MAX_OOS_PARTS)) {
                    tags.add((int) t);
                }
            }
            logDebug("[enumerateOOSPartTags] ranges=" + ranges + ", getPartsCount(OutOfSample)=" + oos.getPartsCount(SampleTypes.OutOfSample) + ", enumerated=" + tags.size());
        } catch (Exception e) {
            logDebug("[enumerateOOSPartTags] ERROR: " + shortError(e));
        }

        int[] out = new int[tags.size()];
        int k = 0;
        for (Integer v : tags) {
            out[k++] = v;
        }
        return out;
    }

    /**
     * Construye la lista de periodos a analizar según el argumento de periodo objetivo.
     * Todas las entradas devueltas están activas por construcción.
     */
    private ArrayList<PeriodDef> buildPeriodTable(String targetPeriod, int[] oosPartTags, String strategyName) {
        ArrayList<PeriodDef> list = new ArrayList<PeriodDef>();

        int requestedPart = parseOOSPartNumber(targetPeriod);
        if (requestedPart > 0) {
            byte tag = (byte) (SampleTypes.OutOfSample + requestedPart);
            boolean exists = containsTag(oosPartTags, tag);
            if (!exists) {
                logDebug("[" + strategyName + "] WARNING: requested OOS part " + requestedPart + " does not exist in this strategy (available OOS parts: " + oosPartTags.length + "). No metrics will be published for _OOS" + requestedPart + ".");
            }
            list.add(new PeriodDef(tag, "_OOS" + requestedPart, exists));
            return list;
        }

        if (targetPeriod.equals("IS")) {
            list.add(new PeriodDef(SampleTypes.InSample, "_IS", true));
            return list;
        }
        if (targetPeriod.equals("OOS") || targetPeriod.equals("IIS")) {
            list.add(new PeriodDef(SampleTypes.OutOfSample, "_OOS", true));
            return list;
        }
        if (targetPeriod.equals("ISV")) {
            list.add(new PeriodDef(SampleTypes.InSampleValidation, "_ISV", true));
            return list;
        }

        // FULL, o cualquier token no reconocido (mismo comportamiento por defecto que antes).
        // Se mantiene el orden histórico de los 4 periodos base.
        list.add(new PeriodDef(SampleTypes.InSample, "_IS", true));
        list.add(new PeriodDef(SampleTypes.OutOfSample, "_OOS", true));
        list.add(new PeriodDef(SampleTypes.InSampleValidation, "_ISV", true));
        list.add(new PeriodDef(SampleTypes.FullSample, "_Full", true));

        // Auto-expansión a las partes OOS numeradas. Con una sola parte no se emite _OOS1:
        // SQX copia las stats de OOS1 sobre OOS (copyStats 21->20), así que serían columnas
        // duplicadas exactamente. No cuesta backtests adicionales: los sintéticos ya
        // ejecutados se muestrean una vez por periodo.
        if (oosPartTags.length >= 2) {
            for (int tag : oosPartTags) {
                list.add(new PeriodDef((byte) tag, "_OOS" + (tag - SampleTypes.OutOfSample), true));
            }
        } else if (oosPartTags.length == 1) {
            logDebug("[" + strategyName + "] Single OOS part: _OOS1 omitted, it would be identical to _OOS by SQX design.");
        }

        return list;
    }

    /**
     * Devuelve 1..10 para "OOS1".."OOS10" (targetPeriod ya viene en mayúsculas), o -1 si no
     * es una parte OOS numerada. Fuera de rango o mal formado devuelve -1, con lo que el
     * llamante cae al comportamiento FULL por defecto, igual que antes, pero ahora se registra.
     */
    private int parseOOSPartNumber(String targetPeriod) {
        if (targetPeriod == null) {
            return -1;
        }

        String s = targetPeriod.trim();
        if (s.startsWith("_")) {
            s = s.substring(1);
        }
        if (!s.startsWith("OOS") || s.length() <= 3) {
            return -1;
        }

        String digits = s.substring(3);
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return -1;
            }
        }

        try {
            int n = Integer.parseInt(digits);
            if (n >= 1 && n <= MAX_OOS_PARTS) {
                return n;
            }
            logDebug("WARNING: OOS part number out of range 1.." + MAX_OOS_PARTS + ": '" + targetPeriod + "' -> falling back to FULL");
        } catch (NumberFormatException e) {
            logDebug("WARNING: could not parse OOS part number from '" + targetPeriod + "' -> falling back to FULL");
        }

        return -1;
    }

    private boolean containsTag(int[] tags, byte tag) {
        for (int t : tags) {
            if (t == tag) {
                return true;
            }
        }
        return false;
    }

    private boolean containsTooManyTradesSameBar(Throwable e) {
        if (e == null) {
            return false;
        }

        String msg = e.getMessage();
        if (msg != null) {
            String m = msg.toLowerCase();
            if (m.contains("too many trades closing at the same bar")) {
                return true;
            }
        }

        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            return containsTooManyTradesSameBar(cause);
        }

        return false;
    }

    private String shortError(Throwable e) {
        if (e == null) {
            return "";
        }

        String cls = e.getClass().getSimpleName();
        String msg = e.getMessage();

        if (msg == null || msg.trim().isEmpty()) {
            return cls;
        }

        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        if (msg.length() > 250) {
            msg = msg.substring(0, 250);
        }

        return cls + ": " + msg;
    }

    // El tooltip de "Filters result" (FiltersResult.java) inserta este texto dentro de un
    // atributo delimitado por comillas simples sin escapar (tooltip='...'). Una comilla simple
    // en el texto cierra el atributo de forma prematura y trunca todo lo que viene después,
    // sin previo aviso y sin relación con la longitud del mensaje. Se sustituye por comilla
    // doble para no perder la referencia visual (p.ej. nombres de símbolo entre comillas).
    private String sanitizeForTooltip(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\'', '"');
    }

    // =========================================================
    // Resolución de parámetros originales
    // =========================================================

    private String resolveOriginalSymbol(ResultsGroup source, Result mainResult) {
        try {
            String lastSettingsXml = source.getLastSettings();
            String symbol = extractChartStringAttr(lastSettingsXml, "symbol", null);
            if (symbol != null && !symbol.trim().isEmpty()) {
                return symbol.trim();
            }
        } catch (Exception ignored) {
        }

        try {
            String symbol = source.specialValues().getString(SpecialValues.Symbol, null);
            if (symbol != null && !symbol.trim().isEmpty()) {
                return symbol.trim();
            }
        } catch (Exception ignored) {
        }

        try {
            String symbol = mainResult.getString(SpecialValues.Symbol, null);
            if (symbol != null && !symbol.trim().isEmpty()) {
                return symbol.trim();
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private long resolveDateFrom(ResultsGroup source) {
        try {
            return source.specialValues().getLong(SpecialValues.HistoryFrom, 0L);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long resolveDateTo(ResultsGroup source) {
        try {
            return source.specialValues().getLong(SpecialValues.HistoryTo, 0L);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String resolveTimeframe(ResultsGroup source, Result mainResult) {
        try {
            String tf = mainResult.getString(SpecialValues.Timeframe, null);
            if (tf != null && !tf.trim().isEmpty()) {
                return tf.trim();
            }
        } catch (Exception ignored) {
        }

        try {
            String tf = source.specialValues().getString(SpecialValues.Timeframe, null);
            if (tf != null && !tf.trim().isEmpty()) {
                return tf.trim();
            }
        } catch (Exception ignored) {
        }

        return "H1";
    }

    private int resolveTestPrecision(String xml, SettingsMap settings) {
        int fromSettings = getIntSetting(settings, SettingsKeys.TestPrecision, Integer.MIN_VALUE);
        if (fromSettings != Integer.MIN_VALUE) {
            return fromSettings;
        }

        int fromXml = extractSetupIntAttr(xml, "testPrecision", Integer.MIN_VALUE);
        if (fromXml != Integer.MIN_VALUE) {
            return fromXml;
        }

        return getBaseTfPrecision();
    }

    private int normalizeTestPrecision(int candidate) {
        int fallback = getBaseTfPrecision();

        if (candidate <= 0) {
            return fallback;
        }

        try {
            Precisions.getPrecision(candidate);
            return candidate;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int getBaseTfPrecision() {
        try {
            Object raw = Precisions.class.getField("PRECISION_BASE_TF").get(null);

            if (raw instanceof Number) {
                return ((Number) raw).intValue();
            }

            if (raw instanceof String) {
                String s = ((String) raw).trim();
                try {
                    return Integer.parseInt(s);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        return 1;
    }

    private void applySafeChartTestPrecision(ChartSetup chartSetup, int testPrecision) {
        try {
            chartSetup.setTestPrecision(testPrecision);
        } catch (Exception ignored) {
        }
    }

    private void applySafeSettingTestPrecision(SettingsMap settings, int testPrecision) {
        try {
            settings.set(SettingsKeys.TestPrecision, testPrecision);
        } catch (Exception ignored) {
            try {
                settings.set(SettingsKeys.TestPrecision, getBaseTfPrecision());
            } catch (Exception ignoredAgain) {
            }
        }
    }

    private void applySafeSimulatorTestPrecision(ITradingSimulator simulator, int testPrecision) {
        try {
            simulator.setTestPrecision(Precisions.getPrecision(testPrecision));
        } catch (Exception ignored) {
            try {
                int fallback = getBaseTfPrecision();
                simulator.setTestPrecision(Precisions.getPrecision(fallback));
            } catch (Exception ignoredAgain) {
            }
        }
    }

    private String resolveEngineName(String xml) {
        String engine = extractSetupStringAttr(xml, "engine", null);
        if (engine != null && !engine.trim().isEmpty()) {
            return engine.trim();
        }
        return "MetaTrader4";
    }

    // =========================================================
    // Simulador / Engine
    // =========================================================

    private ITradingSimulator createSimulatorStrict(String normalizedEngine) throws Exception {
        String[] candidateClasses;

        if ("MetaTrader5Hedged".equals(normalizedEngine) || "MetaTrader5Hedging".equals(normalizedEngine)) {
            candidateClasses = new String[]{
                "com.strategyquant.tradinglib.simulator.impl.MetaTrader5SimulatorHedging"
            };
        } else if ("MetaTrader5Netted".equals(normalizedEngine) || "MetaTrader5Netting".equals(normalizedEngine)) {
            candidateClasses = new String[]{
                "com.strategyquant.tradinglib.simulator.impl.MetaTrader5SimulatorNetting"
            };
        } else if ("MetaTrader4".equals(normalizedEngine)) {
            candidateClasses = new String[]{
                "com.strategyquant.tradinglib.simulator.impl.MetaTrader4Simulator"
            };
        } else if ("Tradestation".equals(normalizedEngine) || "TradeStation".equals(normalizedEngine)) {
            candidateClasses = new String[]{
                "com.strategyquant.tradinglib.simulator.impl.TradeStationSimulator"
            };
        } else if ("NinjaTrader".equals(normalizedEngine)) {
            candidateClasses = new String[]{
                "com.strategyquant.tradinglib.simulator.impl.NinjaTraderSimulator"
            };
        } else if ("JForex".equals(normalizedEngine)) {
            candidateClasses = new String[]{
                "com.strategyquant.tradinglib.simulator.impl.JForexSimulator"
            };
        } else if ("Stockpicker".equals(normalizedEngine)) {
            candidateClasses = new String[]{
                "com.strategyquant.tradinglib.simulator.impl.StockpickerSimulator",
                "com.strategyquant.tradinglib.simulator.impl.StockPickerSimulator"
            };
        } else {
            throw new Exception("Unsupported normalized engine: " + normalizedEngine);
        }

        for (String className : candidateClasses) {
            try {
                Class<?> cls = Class.forName(className);

                if (normalizedEngine.startsWith("MetaTrader5")) {
                    java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(byte.class);
                    ctor.setAccessible(true);
                    Object obj = ctor.newInstance(MT5_EXECUTION_TYPE);
                    if (obj instanceof ITradingSimulator) return (ITradingSimulator) obj;
                } else {
                    Object obj = cls.getDeclaredConstructor().newInstance();
                    if (obj instanceof ITradingSimulator) return (ITradingSimulator) obj;
                }

            } catch (Exception ignored) {
                fdebug("Test", "Error al instanciar clase del simulador: " + ignored.getMessage());
            }
        }

        throw new Exception("Could not create simulator for engine: " + normalizedEngine);
    }

    private boolean applyBacktestEngineToChartSetup(ChartSetup chartSetup, String engineDisplayName) throws Exception {
        try {
            Class<?> enginesClass = Class.forName("com.strategyquant.tradinglib.simulator.Engines");
            Method getEngineMethod = enginesClass.getMethod("getEngine", String.class);
            int engineId = (Integer) getEngineMethod.invoke(null, engineDisplayName);
            logDebug("[applyBacktestEngineToChartSetup] resolved engineId for '" + engineDisplayName + "': " + engineId);

            if (engineId < 0) {
                String[] fallbacks = {
                    "MetaTrader5 (hedged)", "MetaTrader5 (netted)", "MetaTrader4",
                    "Tradestation", "MultiCharts", "JForex"
                };
                for (String fb : fallbacks) {
                    if (fb.equalsIgnoreCase(engineDisplayName)) continue;
                    int fbId = (Integer) getEngineMethod.invoke(null, fb);
                    if (fbId >= 0) {
                        engineId = fbId;
                        logDebug("[applyBacktestEngineToChartSetup] fallback engineId resolved via '" + fb + "': " + engineId);
                        break;
                    }
                }
            }

            Method setEngineMethod = chartSetup.getClass().getMethod("setBacktestEngine", int.class);
            setEngineMethod.invoke(chartSetup, engineId);
            logDebug("[applyBacktestEngineToChartSetup] successfully called setBacktestEngine(int) with " + engineId);

            return engineId >= 0;
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            logDebug("[applyBacktestEngineToChartSetup] ERROR: " + sw.toString());
            throw e;
        }
    }

    private String normalizeEngineName(String engineName) {
        if (engineName == null) return "MetaTrader4";
        String el = engineName.trim().toLowerCase();

        if (el.contains("metatrader5") || el.equals("mt5")) {
            if (el.contains("netting") || el.contains("netted")) return "MetaTrader5Netted";
            return "MetaTrader5Hedged";
        }
        if (el.contains("metatrader4") || el.equals("mt4")) return "MetaTrader4";
        if (el.contains("tradestation"))                    return "Tradestation";
        if (el.contains("ninjatrader"))                     return "NinjaTrader";
        if (el.contains("jforex"))                          return "JForex";
        if (el.contains("stockpicker") || el.contains("stock picker")) return "Stockpicker";

        return "MetaTrader4";
    }

    // =========================================================
    // XML helpers
    // =========================================================

    private Element getSetupElement(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            return null;
        }

        try {
            Element root = XMLUtil.stringToElement(xml);
            if (root == null) {
                return null;
            }

            Element elData = root.getChild("Data");
            if (elData == null) {
                return null;
            }

            Element elSetups = elData.getChild("Setups");
            if (elSetups == null) {
                return null;
            }

            return elSetups.getChild("Setup");
        } catch (Exception e) {
            return null;
        }
    }

    private Element getChartElement(String xml) {
        try {
            Element elSetup = getSetupElement(xml);
            if (elSetup == null) {
                return null;
            }
            return elSetup.getChild("Chart");
        } catch (Exception e) {
            return null;
        }
    }

    private String extractSetupStringAttr(String xml, String attr, String fallback) {
        try {
            Element elSetup = getSetupElement(xml);
            if (elSetup == null) {
                return fallback;
            }
            String v = elSetup.getAttributeValue(attr);
            return (v != null && !v.trim().isEmpty()) ? v.trim() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private double extractSetupDoubleAttr(String xml, String attr, double fallback) {
        try {
            Element elSetup = getSetupElement(xml);
            if (elSetup == null) {
                return fallback;
            }
            String v = elSetup.getAttributeValue(attr);
            return (v != null && !v.trim().isEmpty()) ? Double.parseDouble(v) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private int extractSetupIntAttr(String xml, String attr, int fallback) {
        try {
            Element elSetup = getSetupElement(xml);
            if (elSetup == null) {
                return fallback;
            }
            String v = elSetup.getAttributeValue(attr);
            return (v != null && !v.trim().isEmpty()) ? Integer.parseInt(v) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String extractChartStringAttr(String xml, String attr, String fallback) {
        try {
            Element elChart = getChartElement(xml);
            if (elChart == null) {
                return fallback;
            }
            String v = elChart.getAttributeValue(attr);
            return (v != null && !v.trim().isEmpty()) ? v.trim() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private double extractChartDoubleAttr(String xml, String attr, double fallback) {
        try {
            Element elChart = getChartElement(xml);
            if (elChart == null) {
                return fallback;
            }
            String v = elChart.getAttributeValue(attr);
            return (v != null && !v.trim().isEmpty()) ? Double.parseDouble(v) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    // =========================================================
    // Settings helpers
    // =========================================================

    private int getIntSetting(SettingsMap settings, String key, int fallback) {
        try {
            return settings.getInt(key);
        } catch (Exception e) {
            return fallback;
        }
    }

    private double getDoubleSetting(SettingsMap settings, String key, double fallback) {
        try {
            return settings.getDouble(key);
        } catch (Exception e) {
            return fallback;
        }
    }

    // =========================================================
    // SpecialValues cleanup helpers
    // =========================================================

    // specialValues() persiste entre ejecuciones del Custom Analysis (está asociado a la
    // estrategia, no al run). SettingsMap no expone remove(String), sólo set(); escribir
    // null en una clave produce el mismo resultado que "clave ausente" para las Databank
    // Columns (todas comprueban "if (v == null) return NOT_AVAILABLE"). Sin este barrido,
    // un periodo cuyo test falla en el run actual seguiría mostrando los valores de un
    // test anterior exitoso, como si fueran del run actual.
    private static final String[] PERIOD_SYNTH_KEYS = {
        "CA_SynthPartMissing", "CA_SynthOriginalStatsMissing", "CA_SynthNoData",
        "CA_SynthMissingStatsCount", "CA_SynthMeanProfit", "CA_SynthStdevProfit",
        "CA_SyntheticRatio", "CA_SynthMeanSharpe", "CA_PassRate", "CA_OverfittingRatio",
        "CA_OriginalProfit", "CA_OriginalTrades", "CA_SynthSuccessCount", "CA_SynthFailCount",
        "CA_SinteticNetProfits"
    };

    private void clearPeriodSynthKeys(ResultsGroup rg, String suffix) {
        for (String base : PERIOD_SYNTH_KEYS) {
            rg.specialValues().set(base + suffix, null);
        }
    }

    private static final String[] FULL_SYNTH_KEYS = {
        "CA_SynthMeanProfit", "CA_SynthStdevProfit", "CA_SynthMeanSharpe", "CA_SynthZScoreProfit",
        "CA_OverfittingRatio", "CA_SynthNoData", "CA_OriginalProfit", "CA_OriginalTrades",
        "CA_SynthFailCount", "CA_SynthSuccessCount", "CA_SynthBadStrategyCount", "CA_SynthExceptionCount"
    };

    private void clearFullSynthKeys(ResultsGroup rg) {
        for (String base : FULL_SYNTH_KEYS) {
            rg.specialValues().set(base, null);
        }
    }

    // =========================================================
    // Stats helpers
    // =========================================================

    private double mean(List<Double> x) {
        if (x == null || x.isEmpty()) {
            return 0.0;
        }
        double s = 0.0;
        for (double v : x) {
            s += v;
        }
        return s / x.size();
    }

    private double stdevSample(List<Double> x, double mean) {
        if (x == null || x.size() < 2) {
            return 0.0;
        }
        double s = 0.0;
        for (double v : x) {
            double d = v - mean;
            s += d * d;
        }
        return Math.sqrt(s / (x.size() - 1));
    }

    // =========================================================
    // Helper interno para trazabilidad del backtest
    // =========================================================

    private static class BacktestRunInfo {
        ResultsGroup results;
        String requestedEngine;
        String normalizedEngine;
        String simulatorClass;
        boolean chartEngineApplied;
        boolean chartEngineApplyFailed;
    }

    private static synchronized void logDebug(String msg) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(logFile("CVSintetica_debug.log"), true);
            java.io.PrintWriter pw = new java.io.PrintWriter(fw);
            String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date());
            pw.println("[" + timestamp + "] [Thread-" + Thread.currentThread().getId() + "] " + msg);
            pw.close();
            fw.close();
        } catch (Exception e) {
            reportLogFailureOnce(e);
        }
    }

    /**
     * Resuelve el fichero de log dentro de LOG_DIR, creando la carpeta si hiciera falta.
     * LOG_DIR es relativo: el working directory de la JVM de SQX es la raíz de la
     * instalación (mismo supuesto que ya usa MonkeyTest.java con "user/data/History/..."),
     * de modo que la ruta sigue siendo válida tras reinstalar SQX, mover la instalación
     * o clonarla en otro equipo.
     */
    private static java.io.File logFile(String name) {
        java.io.File dir = new java.io.File(LOG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new java.io.File(dir, name);
    }

    /**
     * Los escritores de log ignoran sus errores para que un problema de logging nunca
     * tumbe un análisis. Pero ignorarlos *en silencio* es justo lo que hizo que la ruta
     * absoluta obsoleta pasara desapercibida, así que el primer fallo sí se reporta.
     */
    private static void reportLogFailureOnce(Exception e) {
        if (logFailureReported) {
            return;
        }
        logFailureReported = true;
        try {
            System.err.println("[CVSintetica_V08] WARNING: no se pudo escribir el log en '"
                    + new java.io.File(LOG_DIR).getAbsolutePath() + "': " + e
                    + " (los siguientes fallos de logging se omiten)");
        } catch (Exception ignored) {}
    }

    private com.strategyquant.tradinglib.strategy.OutOfSample resolveOOS(ResultsGroup source) {
        try {
            logDebug("[resolveOOS] Starting resolution for strategy: " + source.getName());

            com.strategyquant.tradinglib.strategy.OutOfSample oos = source.getOOS();
            if (oos != null && !oos.isEmpty()) {
                logDebug("[resolveOOS] found valid OOS directly from source.getOOS(): " + oos.toString());
                return oos;
            } else {
                logDebug("[resolveOOS] source.getOOS() is null or empty. isEmpty: " + (oos == null ? "null" : oos.isEmpty()));
            }

            Result mainResult = source.mainResult();
            if (mainResult != null) {
                SettingsMap settings = mainResult.getSettings();
                if (settings != null) {
                    logDebug("[resolveOOS] settings is not null. SettingsMap type: " + settings.getClass().getName());
                    Object oosObj = settings.get(SettingsKeys.OutOfSample);
                    if (oosObj instanceof com.strategyquant.tradinglib.strategy.OutOfSample) {
                        logDebug("[resolveOOS] found valid OOS in mainResult settings map under SettingsKeys.OutOfSample");
                        return (com.strategyquant.tradinglib.strategy.OutOfSample) oosObj;
                    } else {
                        logDebug("[resolveOOS] settings.get(SettingsKeys.OutOfSample) type: " + (oosObj == null ? "null" : oosObj.getClass().getName()));
                    }
                } else {
                    logDebug("[resolveOOS] mainResult settings is null!");
                }
            } else {
                logDebug("[resolveOOS] mainResult is null!");
            }

            String lastSettingsXml = source.getLastSettings();
            logDebug("[resolveOOS] lastSettingsXml is null: " + (lastSettingsXml == null) + ", length: " + (lastSettingsXml == null ? 0 : lastSettingsXml.length()));
            if (lastSettingsXml != null && !lastSettingsXml.trim().isEmpty()) {
                if (lastSettingsXml.length() < 3000) {
                    logDebug("[resolveOOS] lastSettingsXml content: " + lastSettingsXml);
                } else {
                    logDebug("[resolveOOS] lastSettingsXml start: " + lastSettingsXml.substring(0, 1000));
                }
                Element root = XMLUtil.stringToElement(lastSettingsXml);
                if (root != null) {
                    logDebug("[resolveOOS] root element name: " + root.getName());
                    Element elOOS = findElementRecursive(root, "OutOfSample");
                    if (elOOS != null) {
                        com.strategyquant.tradinglib.strategy.OutOfSample oos2 = new com.strategyquant.tradinglib.strategy.OutOfSample();
                        oos2.setFromXML(elOOS);
                        if (!oos2.isEmpty()) {
                            logDebug("[resolveOOS] parsed valid OOS from lastSettingsXml recursive search: " + oos2.toString());
                            return oos2;
                        } else {
                            logDebug("[resolveOOS] parsed OOS from lastSettingsXml was empty!");
                        }
                    } else {
                        logDebug("[resolveOOS] OutOfSample tag not found in lastSettingsXml!");
                    }
                }
            }

            Element elStrategy = source.getStrategyXml();
            logDebug("[resolveOOS] strategyXml is null: " + (elStrategy == null));
            if (elStrategy != null) {
                Element elOOS = findElementRecursive(elStrategy, "OutOfSample");
                if (elOOS != null) {
                    com.strategyquant.tradinglib.strategy.OutOfSample oos3 = new com.strategyquant.tradinglib.strategy.OutOfSample();
                    oos3.setFromXML(elOOS);
                    if (!oos3.isEmpty()) {
                        logDebug("[resolveOOS] parsed valid OOS from strategyXml recursive search: " + oos3.toString());
                        return oos3;
                    } else {
                        logDebug("[resolveOOS] parsed OOS from strategyXml was empty!");
                    }
                } else {
                    logDebug("[resolveOOS] OutOfSample tag not found in strategyXml!");
                }
            }
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            logDebug("[resolveOOS] Error resolving OOS: " + sw.toString());
        }

        logDebug("[resolveOOS] WARNING: Could not resolve OutOfSample settings!");
        return null;
    }

    private Element findElementRecursive(Element parent, String name) {
        if (parent == null) return null;
        if (parent.getName().equalsIgnoreCase(name)) {
            return parent;
        }
        for (Element child : parent.getChildren()) {
            Element found = findElementRecursive(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static synchronized void logTradesCompareBlock(String block) {
        try (java.io.FileWriter fw = new java.io.FileWriter(logFile("CVSintetica_trades_compare.log"), true);
             java.io.PrintWriter pw = new java.io.PrintWriter(fw)) {
            pw.print(block);
        } catch (Exception e) {
            reportLogFailureOnce(e);
        }
    }

    private void dumpTrades(StringBuilder sb, String prefix, String strategyName, String targetSymbol, ResultsGroup rg, String resultKey, byte sampleType) {
        try {
            if (rg == null || resultKey == null) return;

            com.strategyquant.tradinglib.OrdersList orders = rg.orders().filterWithClone(resultKey, com.strategyquant.tradinglib.Directions.Both, sampleType);
            if (orders == null) {
                sb.append(prefix).append(";").append(strategyName).append(";").append(targetSymbol).append(";NO_ORDERS_FOUND\n");
                return;
            }

            int count = 0;
            for (int i = 0; i < orders.size(); i++) {
                com.strategyquant.tradinglib.Order o = orders.get(i);
                if (o.isBalanceOrder()) continue;
                if (o.OpenPrice == o.ClosePrice && Math.abs(o.PL) < 1e-9) continue;

                count++;

                String orderType = o.isShort() ? "SHORT" : "LONG";
                String openTimeStr = new java.text.SimpleDateFormat("yyyy.MM.dd HH:mm:ss").format(new java.util.Date(o.OpenTime));
                String closeTimeStr = new java.text.SimpleDateFormat("yyyy.MM.dd HH:mm:ss").format(new java.util.Date(o.CloseTime));

                double commSwap = o.CommSwap;
                double grossPL = o.PL - commSwap;

                sb.append(String.format(java.util.Locale.US,
                    "%s;%s;%s;Trade#%d;%s;OpenTime=%s;CloseTime=%s;OpenPrice=%.5f;ClosePrice=%.5f;Size=%.2f;GrossPL=%.2f;CommSwap=%.2f;NetPL=%.2f;CloseType=%d\n",
                    prefix,
                    strategyName,
                    targetSymbol,
                    count,
                    orderType,
                    openTimeStr,
                    closeTimeStr,
                    o.OpenPrice,
                    o.ClosePrice,
                    o.Size,
                    grossPL,
                    commSwap,
                    o.PL,
                    o.CloseType
                ));
            }

            sb.append(String.format("=== TOTAL TRADES %s (%s / %s): %d ===\n", prefix, strategyName, targetSymbol, count));
        } catch (Exception e) {
            sb.append(prefix).append(";").append(strategyName).append(";").append(targetSymbol).append(";ERROR: ").append(e.getMessage()).append("\n");
        }
    }
}
