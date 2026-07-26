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

/**
 * CVSintetica_V06
 *
 * Mejoras sobre V05:
 * - Sin fallback silencioso a MT4 si falla el simulador del engine solicitado.
 * - Guarda trazas del engine y simulador realmente usados.
 * - Marca si pudo aplicar el engine al ChartSetup.
 * - Registra errores resumidos para diagnóstico.
 *
 * Uso en "Input args":
 * - Escribe SOLO el prefijo de la data sintética, SIN el número final.
 * - Ejemplo: NAS100_Darwinex_sim
 * - El código construirá: NAS100_Darwinex_sim001 ... NAS100_Darwinex_sim150
 */
public class CVSintetica_V06 extends CustomAnalysisMethod {

    private static final int SYNTHETIC_COUNT = 150;
    private static final String DEFAULT_PREFIX = "XAUUSD_Darwinex_sim";

    public CVSintetica_V06() {
        super("CVSintetica_V06", TYPE_FILTER_STRATEGY);
    }

    @Override
    public boolean filterStrategy(String project, String task, String databankName, ResultsGroup rg) throws Exception {

        Result mainResult = rg.mainResult();
        if (mainResult == null) {
            return true;
        }

        String synthPrefix = getInputArgs();
        if (synthPrefix == null || synthPrefix.trim().isEmpty()) {
            synthPrefix = DEFAULT_PREFIX;
        } else {
            synthPrefix = synthPrefix.trim();
        }

        String originalSymbol = resolveOriginalSymbol(rg, mainResult);

        double originalProfit = safeGetNetProfit(rg);
        int originalTrades = safeGetTradeCount(rg);

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
                BacktestRunInfo info = runBacktestWithInheritedSettings(rg, originalSymbol);

                originalProfit = safeGetNetProfit(info.results);
                originalTrades = safeGetTradeCount(info.results);

                requestedEngine = info.requestedEngine;
                normalizedEngine = info.normalizedEngine;
                originalSimulatorClass = info.simulatorClass;
                originalChartEngineApplied = info.chartEngineApplied ? 1 : 0;
                originalChartEngineApplyFailed = info.chartEngineApplyFailed ? 1 : 0;

            } catch (BadStrategyException e) {
                originalRetestFailed = 1;
                originalRetestBadStrategy = 1;
                originalUsedFallback = 1;
                originalRetestException = shortError(e);
            } catch (Exception e) {
                originalRetestFailed = 1;
                originalUsedFallback = 1;
                originalRetestException = shortError(e);
            }
        } else {
            originalRetestFailed = 1;
            originalUsedFallback = 1;
            originalSymbol = "N/A";
            originalRetestException = "Original symbol could not be resolved";
        }

        ArrayList<Double> profits = new ArrayList<>();
        int failCount = 0;
        int successCount = 0;
        int synthBadStrategyCount = 0;
        int synthExceptionCount = 0;
        int synthSameBarErrorCount = 0;
        int synthChartEngineApplyFailedCount = 0;

        String lastSyntheticException = "";
        String lastSyntheticSimulatorClass = "N/A";

        for (int i = 1; i <= SYNTHETIC_COUNT; i++) {
            String synthSymbol = String.format("%s%03d", synthPrefix, i);

            try {
                BacktestRunInfo info = runBacktestWithInheritedSettings(rg, synthSymbol);

                double p = safeGetNetProfit(info.results);
                int trades = safeGetTradeCount(info.results);

                profits.add(p);
                successCount++;

                lastSyntheticSimulatorClass = info.simulatorClass;

                if (info.chartEngineApplyFailed) {
                    synthChartEngineApplyFailedCount++;
                }

                if (p <= 0 || trades == 0) {
                    failCount++;
                }

            } catch (BadStrategyException e) {
                failCount++;
                synthBadStrategyCount++;
                lastSyntheticException = shortError(e);

                if (containsTooManyTradesSameBar(e)) {
                    synthSameBarErrorCount++;
                }

            } catch (Exception e) {
                failCount++;
                synthExceptionCount++;
                lastSyntheticException = shortError(e);
            }
        }

        double mean = mean(profits);
        double stdev = stdevSample(profits, mean);
        double z = (stdev > 0.0) ? (originalProfit - mean) / stdev : 0.0;

        rg.specialValues().set("CA_SynthMeanProfit", mean);
        rg.specialValues().set("CA_SynthStdevProfit", stdev);
        rg.specialValues().set("CA_SynthZScoreProfit", z);
        rg.specialValues().set("CA_OriginalProfit", originalProfit);
        rg.specialValues().set("CA_OriginalTrades", originalTrades);

        rg.specialValues().set("CA_SynthFailCount", failCount);
        rg.specialValues().set("CA_SynthSuccessCount", successCount);
        rg.specialValues().set("CA_SynthRequestedCount", SYNTHETIC_COUNT);
        rg.specialValues().set("CA_SynthBadStrategyCount", synthBadStrategyCount);
        rg.specialValues().set("CA_SynthExceptionCount", synthExceptionCount);
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
    private BacktestRunInfo runBacktestWithInheritedSettings(ResultsGroup source, String targetSymbol) throws Exception {

        Result mainResult = source.mainResult();
        if (mainResult == null) {
            throw new Exception("mainResult is null");
        }

        SettingsMap baseSettings = mainResult.getSettings();
        if (baseSettings == null) {
            throw new Exception("mainResult.getSettings() returned null");
        }

        SettingsMap settings = baseSettings.clone();

        Element elStrategy = source.getStrategyXml();
        if (elStrategy == null) {
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

        long dateFrom = resolveDateFrom(source);
        long dateTo = resolveDateTo(source);
        if (dateFrom <= 0 || dateTo <= 0 || dateFrom >= dateTo) {
            throw new Exception("Invalid original date range");
        }

        String timeframe = resolveTimeframe(source, mainResult);
        String session = extractSetupStringAttr(lastSettingsXml, "session", Session.Forex_247);
        double spread = extractChartDoubleAttr(lastSettingsXml, "spread", 3.5);
        double slippage = extractSetupDoubleAttr(lastSettingsXml, "slippage", getDoubleSetting(settings, SettingsKeys.Slippage, 0.0));
        double minDistance = extractSetupDoubleAttr(lastSettingsXml, "minDist", getDoubleSetting(settings, SettingsKeys.MinimumDistance, 0.0));
        int testPrecision = normalizeTestPrecision(resolveTestPrecision(lastSettingsXml, settings));

        String requestedEngine = resolveEngineName(lastSettingsXml);
        String normalizedEngine = normalizeEngineName(requestedEngine);

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
            chartEngineApplied = applyBacktestEngineToChartSetup(chartSetup, normalizedEngine);
            if (!chartEngineApplied) {
                chartEngineApplyFailed = true;
            }
        } catch (Exception e) {
            chartEngineApplyFailed = true;
        }

        settings.set(SettingsKeys.BacktestChart, chartSetup);
        applySafeSettingTestPrecision(settings, testPrecision);
        settings.set(SettingsKeys.Slippage, slippage);
        settings.set(SettingsKeys.MinimumDistance, minDistance);

        ITradingSimulator simulator = createSimulatorStrict(normalizedEngine);
        applySafeSimulatorTestPrecision(simulator, testPrecision);

        BacktestEngine backtestEngine = new BacktestEngine(simulator);
        backtestEngine.setSingleThreaded(true);
        backtestEngine.addSetup(settings);

        ResultsGroup results = backtestEngine.runBacktest().getResults();

        try {
            com.strategyquant.tradinglib.strategy.OutOfSample oos = resolveOOS(source);
            if (oos != null) {
                results.setOOSSettings(oos);
                results.computeAllStats();
            }
        } catch (Exception e) {
            // Ignored to prevent test from aborting
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

    private double safeGetNetProfit(ResultsGroup rg) {
        try {
            return rg.portfolio()
                    .stats(Directions.Both, PlTypes.Money, SampleTypes.FullSample)
                    .getDouble(StatsKey.NET_PROFIT);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private int safeGetTradeCount(ResultsGroup rg) {
        try {
            return rg.portfolio()
                    .stats(Directions.Both, PlTypes.Money, SampleTypes.FullSample)
                    .getInt(StatsKey.NUMBER_OF_TRADES);
        } catch (Exception e) {
            return 0;
        }
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
                    // MT5 requiere byte en el constructor: modo de ejecución del broker.
                    // OrderExecutionTypes.MARKET = 2 → precio de mercado sin requotes (estándar Forex/Darwinex).
                    java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(byte.class);
                    ctor.setAccessible(true);
                    Object obj = ctor.newInstance((byte) 2);
                    if (obj instanceof ITradingSimulator) return (ITradingSimulator) obj;
                } else {
                    // Resto de simuladores: constructor vacío
                    Object obj = cls.getDeclaredConstructor().newInstance();
                    if (obj instanceof ITradingSimulator) return (ITradingSimulator) obj;
                }

            } catch (Exception ignored) {
                fdebug("Test", "Error al instanciar clase del simulador: " + ignored.getMessage());
            }
        }

        throw new Exception("Could not create simulator for engine: " + normalizedEngine);
    }


    private boolean applyBacktestEngineToChartSetup(ChartSetup chartSetup, String normalizedEngine) throws Exception {
        Class<?> enginesClass = Class.forName("com.strategyquant.tradinglib.simulator.Engines");
        Method getEngineMethod = enginesClass.getMethod("getEngine", String.class);
        int engineId = (Integer) getEngineMethod.invoke(null, normalizedEngine);

        Method setEngineMethod = chartSetup.getClass().getMethod("setBacktestEngine", int.class);
        setEngineMethod.invoke(chartSetup, engineId);

        return true;
    }

    private String normalizeEngineName(String engineName) {
        if (engineName == null) return "MetaTrader4";
        String el = engineName.trim().toLowerCase();

        if (el.contains("metatrader5") || el.equals("mt5")) {
            // Distinguir hedging vs netting según lo que venga en el XML
            if (el.contains("netting") || el.contains("netted")) return "MetaTrader5Netted";
            // Por defecto MT5 → Hedging (estándar en Forex/Darwinex)
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

    private com.strategyquant.tradinglib.strategy.OutOfSample resolveOOS(ResultsGroup source) {
        try {
            com.strategyquant.tradinglib.strategy.OutOfSample oos = source.getOOS();
            if (oos != null && !oos.isEmpty()) {
                return oos;
            }

            Result mainResult = source.mainResult();
            if (mainResult != null) {
                SettingsMap settings = mainResult.getSettings();
                if (settings != null) {
                    Object oosObj = settings.get(SettingsKeys.OutOfSample);
                    if (oosObj instanceof com.strategyquant.tradinglib.strategy.OutOfSample) {
                        return (com.strategyquant.tradinglib.strategy.OutOfSample) oosObj;
                    }
                }
            }

            String lastSettingsXml = source.getLastSettings();
            if (lastSettingsXml != null && !lastSettingsXml.trim().isEmpty()) {
                Element root = XMLUtil.stringToElement(lastSettingsXml);
                if (root != null) {
                    Element elOOS = root.getChild("OutOfSample");
                    if (elOOS != null) {
                        com.strategyquant.tradinglib.strategy.OutOfSample oos2 = new com.strategyquant.tradinglib.strategy.OutOfSample();
                        oos2.setFromXML(elOOS);
                        if (!oos2.isEmpty()) {
                            return oos2;
                        }
                    }
                }
            }

            Element elStrategy = source.getStrategyXml();
            if (elStrategy != null) {
                Element elOOS = elStrategy.getChild("OutOfSample");
                if (elOOS == null) {
                    Element elSettings = elStrategy.getChild("Settings");
                    if (elSettings != null) {
                        elOOS = elSettings.getChild("OutOfSample");
                    }
                }
                if (elOOS != null) {
                    com.strategyquant.tradinglib.strategy.OutOfSample oos3 = new com.strategyquant.tradinglib.strategy.OutOfSample();
                    oos3.setFromXML(elOOS);
                    if (!oos3.isEmpty()) {
                        return oos3;
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }
}