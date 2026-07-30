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

        // Periodos admitidos en SQX y sus sufijos correspondientes
        byte[] periods = {
            SampleTypes.InSample,
            SampleTypes.OutOfSample,
            SampleTypes.InSampleValidation,
            SampleTypes.FullSample
        };
        String[] suffixes = {"_IS", "_OOS", "_ISV", "_Full"};

        // Determinar qué índices de periodo se van a procesar
        ArrayList<Integer> activePeriodIndices = new ArrayList<Integer>();
        if (targetPeriod.equals("IS")) {
            activePeriodIndices.add(0);
        } else if (targetPeriod.equals("OOS") || targetPeriod.equals("IIS")) {
            activePeriodIndices.add(1);
        } else if (targetPeriod.equals("ISV")) {
            activePeriodIndices.add(2);
        } else {
            // FULL o por defecto
            activePeriodIndices.add(0);
            activePeriodIndices.add(1);
            activePeriodIndices.add(2);
            activePeriodIndices.add(3);
        }

        double[] originalProfits = new double[periods.length];
        int[] originalTrades = new int[periods.length];

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
                BacktestRunInfo info = runBacktestWithInheritedSettings(rg, originalSymbol, true, debugTradesCompare);

                requestedEngine = info.requestedEngine;
                normalizedEngine = info.normalizedEngine;
                originalSimulatorClass = info.simulatorClass;
                originalChartEngineApplied = info.chartEngineApplied ? 1 : 0;
                originalChartEngineApplyFailed = info.chartEngineApplyFailed ? 1 : 0;

                // Extraemos valores reales para cada periodo activo
                for (int p : activePeriodIndices) {
                    originalProfits[p] = safeGetNetProfit(info.results, periods[p]);
                    originalTrades[p] = safeGetTradeCount(info.results, periods[p]);
                    logDebug("[" + rg.getName() + "] ORIGINAL RESULT FOR PERIOD " + suffixes[p] + " (type=" + periods[p] + "): Profit=" + originalProfits[p] + ", Trades=" + originalTrades[p]);
                }

            } catch (BadStrategyException e) {
                originalRetestFailed = 1;
                originalRetestBadStrategy = 1;
                originalUsedFallback = 1;
                originalRetestException = shortError(e);
                logDebug("[" + rg.getName() + "] ORIGINAL RETEST BadStrategyException: " + originalRetestException);
            } catch (Exception e) {
                originalRetestFailed = 1;
                originalUsedFallback = 1;
                originalRetestException = shortError(e);
                logDebug("[" + rg.getName() + "] ORIGINAL RETEST Exception: " + originalRetestException);
            }
        } else {
            originalRetestFailed = 1;
            originalUsedFallback = 1;
            originalSymbol = "N/A";
            originalRetestException = "Original symbol could not be resolved";
            logDebug("[" + rg.getName() + "] ORIGINAL RETEST FAILED: symbol empty");
        }

        // Listas para almacenar beneficios netos sintéticos por cada periodo
        ArrayList<ArrayList<Double>> periodProfits = new ArrayList<>();
        for (int p = 0; p < periods.length; p++) {
            periodProfits.add(new ArrayList<Double>());
        }

        int[] periodSuccessCounts = new int[periods.length];
        int[] periodFailCounts = new int[periods.length];
        int[] periodBadStrategyCounts = new int[periods.length];
        int[] periodExceptionCounts = new int[periods.length];

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
                        res.info = runBacktestWithInheritedSettings(rg, synthSymbol, false, debugTradesCompareFinal);
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
                        for (int p : activePeriodIndices) {
                            double pVal = safeGetNetProfit(res.info.results, periods[p]);
                            int tVal = safeGetTradeCount(res.info.results, periods[p]);

                            periodProfits.get(p).add(pVal);

                            // Consideramos ganadora si el net profit es estrictamente mayor que cero y operó en el periodo
                            if (pVal <= 0 || tVal == 0) {
                                periodFailCounts[p]++;
                            } else {
                                periodSuccessCounts[p]++;
                            }

                            if (res.index <= 5) {
                                logDebug("[" + rg.getName() + "] Synth #" + res.index + " (" + res.symbol + ") period " + suffixes[p] + ": Profit=" + pVal + ", Trades=" + tVal);
                            }
                        }
                    } else {
                        lastSyntheticException = shortError(res.exception);
                        if (res.isBadStrategy) {
                            logDebug("[" + rg.getName() + "] Synth #" + res.index + " (" + res.symbol + ") BadStrategyException: " + lastSyntheticException);

                            for (int p : activePeriodIndices) {
                                periodFailCounts[p]++;
                                periodBadStrategyCounts[p]++;
                            }

                            if (containsTooManyTradesSameBar(res.exception)) {
                                synthSameBarErrorCount++;
                            }
                        } else {
                            logDebug("[" + rg.getName() + "] Synth #" + res.index + " (" + res.symbol + ") Exception: " + lastSyntheticException);
                            for (int p : activePeriodIndices) {
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
        for (int p : activePeriodIndices) {
            ArrayList<Double> profitsList = periodProfits.get(p);
            double mean = mean(profitsList);
            double stdev = stdevSample(profitsList, mean);
            double origProfit = originalProfits[p];

            // 1. OverfittingRatio (Z-Score con signo)
            double overfittingRatio = (stdev > 0.0) ? (origProfit - mean) / stdev : 0.0;

            // 2. Synthetic_Ratio (Filtro de Ergodicidad)
            double syntheticRatio = (stdev > 0.0) ? mean / stdev : 0.0;

            // 3. Pass_Rate (Survival Rate de 0.0 a 1.0)
            double passRate = (double) periodSuccessCounts[p] / syntheticCount;

            String suffix = suffixes[p];

            rg.specialValues().set("CA_SynthMeanProfit" + suffix, mean);
            rg.specialValues().set("CA_SynthStdevProfit" + suffix, stdev);
            rg.specialValues().set("CA_OverfittingRatio" + suffix, overfittingRatio);
            rg.specialValues().set("CA_SyntheticRatio" + suffix, syntheticRatio);
            rg.specialValues().set("CA_SynthMeanSharpe" + suffix, syntheticRatio);
            rg.specialValues().set("CA_PassRate" + suffix, passRate);
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
        if (targetPeriod.equals("FULL")) {
            double meanFull = mean(periodProfits.get(3));
            double stdevFull = stdevSample(periodProfits.get(3), meanFull);
            double origProfitFull = originalProfits[3];
            double zFull = (stdevFull > 0.0) ? (origProfitFull - meanFull) / stdevFull : 0.0;

            rg.specialValues().set("CA_SynthMeanProfit", meanFull);
            rg.specialValues().set("CA_SynthStdevProfit", stdevFull);
            rg.specialValues().set("CA_SynthZScoreProfit", zFull);
            rg.specialValues().set("CA_OverfittingRatio", zFull);
            rg.specialValues().set("CA_SynthMeanSharpe", (stdevFull > 0.0) ? meanFull / stdevFull : 0.0);
            rg.specialValues().set("CA_OriginalProfit", origProfitFull);
            rg.specialValues().set("CA_OriginalTrades", originalTrades[3]);
            rg.specialValues().set("CA_SynthFailCount", periodFailCounts[3]);
            rg.specialValues().set("CA_SynthSuccessCount", periodSuccessCounts[3]);

            rg.specialValues().set("CA_SynthBadStrategyCount", periodBadStrategyCounts[3]);
            rg.specialValues().set("CA_SynthExceptionCount", periodExceptionCounts[3]);
        }

        // Guardado de control
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

        return true;
    }

    /**
     * Ejecuta un nuevo backtest intentando heredar la configuración original.
     */
    private BacktestRunInfo runBacktestWithInheritedSettings(ResultsGroup source, String targetSymbol, boolean isControlRun, boolean debugTradesCompare) throws Exception {
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
            oosConfig = resolveOOS(source);
            logDebug("[" + source.getName() + "] pre-backtest resolveOOS is null: " + (oosConfig == null));
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

    private double safeGetNetProfit(ResultsGroup rg, byte sampleType) {
        try {
            return rg.portfolio()
                    .stats(Directions.Both, PlTypes.Money, sampleType)
                    .getDouble(StatsKey.NET_PROFIT);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private int safeGetTradeCount(ResultsGroup rg, byte sampleType) {
        try {
            return rg.portfolio()
                    .stats(Directions.Both, PlTypes.Money, sampleType)
                    .getInt(StatsKey.NUMBER_OF_TRADES);
        } catch (Exception e) {
            return 0;
        }
    }

    private double safeGetNetProfit(ResultsGroup rg) {
        return safeGetNetProfit(rg, SampleTypes.FullSample);
    }

    private int safeGetTradeCount(ResultsGroup rg) {
        return safeGetTradeCount(rg, SampleTypes.FullSample);
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
            java.io.FileWriter fw = new java.io.FileWriter("g:\\Software\\StrategyQuantX\\144\\user\\extend\\Snippets\\SQ\\CustomAnalysis\\CVSintetica_debug.log", true);
            java.io.PrintWriter pw = new java.io.PrintWriter(fw);
            String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date());
            pw.println("[" + timestamp + "] [Thread-" + Thread.currentThread().getId() + "] " + msg);
            pw.close();
            fw.close();
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
        try (java.io.FileWriter fw = new java.io.FileWriter("g:\\Software\\StrategyQuantX\\144\\user\\extend\\Snippets\\SQ\\CustomAnalysis\\CVSintetica_trades_compare.log", true);
             java.io.PrintWriter pw = new java.io.PrintWriter(fw)) {
            pw.print(block);
        } catch (Exception ignored) {}
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
