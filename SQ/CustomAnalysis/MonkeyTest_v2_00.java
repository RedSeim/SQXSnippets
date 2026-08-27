package SQ.CustomAnalysis;

import com.strategyquant.lib.*;
import com.strategyquant.datalib.*;
import com.strategyquant.tradinglib.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jdom2.Element;
import com.strategyquant.datalib.data.io.newDataFormat.RandomAccessReaderFile;
import com.strategyquant.datalib.data.io.newDataFormat.OhlcDataReader;
import com.strategyquant.datalib.data.io.VersatileData;

/**
 * Monkey Test v2.00 -- Monte Carlo permutation test de la ventaja de timing de una estrategia.
 *
 * Frente a la v1, el P&L deja de calcularse trade a trade (pipMult, priceCorrection, prorrateo de
 * comisiones) y pasa a derivarse de una CALIBRACION UNICA por periodo: se mide cuanto vale
 * monetariamente una unidad de desplazamiento de precio en la estrategia original, y el profit de
 * cada mono es ese factor por su desplazamiento acumulado. Eso elimina los replicationMode, los
 * shiftingMode y todo el escalado de lotaje por operacion.
 *
 * Las decisiones de diseno y su justificacion estan en MonkeyTest_v2_00_ENG.md / _SPA.md.
 */
public class MonkeyTest_v2_00 extends CustomAnalysisMethod {
    public static final Logger Log = LoggerFactory.getLogger(MonkeyTest_v2_00.class);

    private static final int MAX_PARTS = 10;
    private static final int MIN_TRADES = 20;

    // Volcado verboso de la palabra clave Debug. Va a un fichero propio y no al log de SQX, que
    // llega a 800 MB/dia y quedaria inservible. La ruta es RELATIVA a la raiz de instalacion de
    // SQX (el working directory de la JVM), igual que el cacheDir del ResultsPlugin, de modo que
    // sigue siendo valida tras reinstalar o mover SQX. Mismo patron que CVSintetica_V08.logDebug.
    private static final String DEBUG_LOG_DIR = "user/extend/Snippets/SQ/CustomAnalysis";
    private static final String DEBUG_LOG_NAME = "MonkeyTest_v2_debug.log";
    private static boolean debugWriteErrorReported = false;

    /** Claves publicadas por periodo. Se limpian antes de recalcular cada periodo en scope. */
    private static final String[] PERIOD_KEYS = {
        "MonkeyTestResult", "MonkeyTestPercentile", "MonkeyTestZScore", "MonkeyTestMedianProfit",
        "MonkeyTestRealProfit", "MonkeyTestMode", "MonkeyTestSpread", "MonkeyTestExposureRatio"
    };

    public static class Candle {
        public long time;
        public double open;
        public double high;
        public double low;
        public double close;
        public double volume;
    }

    /**
     * Un periodo a evaluar: el sample type de SQX y el sufijo con el que se publican sus claves.
     * Los sufijos son los que resuelven las Databank Columns a partir del selector de sample type.
     */
    private static class PeriodDef {
        final byte sampleType;
        final String suffix;
        /** Sufijo adicional bajo el que publicar el mismo resultado (caso de 1 sola parte OOS). */
        String alsoPublishAs = null;

        PeriodDef(byte sampleType, String suffix) {
            this.sampleType = sampleType;
            this.suffix = suffix;
        }

        String label() {
            return suffix.substring(1).toUpperCase();
        }

        boolean isNumberedPart() {
            return (sampleType > SampleTypes.OutOfSample && sampleType <= (byte) (SampleTypes.OutOfSample + MAX_PARTS))
                || (sampleType > SampleTypes.InSampleValidation && sampleType <= (byte) (SampleTypes.InSampleValidation + MAX_PARTS));
        }
    }

    /**
     * Configuracion heredada de la estrategia original, resuelta una sola vez por estrategia.
     * Este snippet NO usa el motor de backtest de SQX (a diferencia de CVSintetica, que instancia
     * BacktestEngine y por tanto hereda el spread gratis), sino una simulacion propia sobre las
     * velas crudas del .dat -- asi que el spread hay que aplicarlo explicitamente.
     */
    private static class StrategyContext {
        String symbol = "";
        String timeframe = "";
        double tickSize = 0.0;
        double spreadPoints = 0.0;
        double spreadPrice = 0.0;
        String spreadSource = "none";
        boolean modeA = true;
        String modeLabel = "A (FixedSize)";
        String mmName = "unknown";
    }

    /** Resultado del test para un periodo concreto, incluido lo necesario para escribir la cache. */
    private static class PeriodResult {
        String status = "ERROR";
        String percentileText = null;
        String zScoreText = null;

        /** true solo si la simulacion se completo y hay estadisticas publicables. */
        boolean hasFullStats = false;

        double[][] curves;
        double[] sortedProfits;
        int numTrades;
        double initialBalance;
        double realProfit;
        double netProfitSQX;
        double thresholdVal;
        double meanMonkey;
        double stdMonkey;
        double medianMonkey;
        double zScore;
        double rankPercentile;
        double exactBars;
        double exposureRatio = 1.0;
        double ratioK;
        String modeLabel = "";
        double spreadPoints;
        long tMin;
        long tMax;
    }

    public MonkeyTest_v2_00() {
        super("MonkeyTest_v2_00", TYPE_FILTER_STRATEGY);
    }

    @Override
    public boolean filterStrategy(String projectName, String task, String databankName, ResultsGroup rg) throws Exception {
        // Input Args: "numMonkeys,percentile,period" mas palabras clave no posicionales:
        // ResultsPluginCache, AutoDiscard, Precision=M1 (o M1), SegmentDuration=N
        // Los antiguos replicationMode y shiftingMode ya no existen: la v2 siempre simula con
        // duracion media diteada y entradas aleatorias sin solapamiento.
        int numMonkeys = 500;
        double percentile = 95.0;
        PeriodDef requested = new PeriodDef(SampleTypes.FullSample, "_Full");
        boolean writeResultsPluginCache = false;
        boolean autoDiscard = false;
        boolean debugDump = false;
        boolean useM1Precision = false;
        double segmentDurationDays = 0.0;

        try {
            String inputArgs = this.getInputArgs();
            if (inputArgs != null && !inputArgs.trim().isEmpty()) {
                String[] args = inputArgs.split(",");
                if (args.length >= 1 && !args[0].trim().isEmpty()) {
                    numMonkeys = Integer.parseInt(args[0].trim());
                }
                if (args.length >= 2 && !args[1].trim().isEmpty()) {
                    percentile = Double.parseDouble(args[1].trim());
                }
                if (args.length >= 3 && !args[2].trim().isEmpty()) {
                    requested = parsePeriod(args[2].trim().toUpperCase());
                }

                warnOnLegacyArgs(args);

                String upperArgs = inputArgs.toUpperCase();
                if (upperArgs.contains("RESULTSPLUGINCACHE")) {
                    writeResultsPluginCache = true;
                }
                if (upperArgs.contains("AUTODISCARD")) {
                    autoDiscard = true;
                }
                // Ninguna otra palabra clave ni nombre de periodo contiene "DEBUG", asi que la
                // deteccion por subcadena no puede dar falsos positivos.
                if (upperArgs.contains("DEBUG")) {
                    debugDump = true;
                }
                useM1Precision = detectM1Precision(upperArgs);
                segmentDurationDays = parseSegmentDuration(inputArgs);
            }
        } catch (Exception e) {
            Log.warn("MonkeyTest v2: could not read input args, using defaults (500 monkeys, 95%, FULL). Reason: " + e.getMessage());
        }

        Random rng = new Random();
        LinkedHashMap<String, PeriodResult> resultsBySuffix = new LinkedHashMap<>();
        ArrayList<PeriodDef> periods = new ArrayList<>();

        try {
            String mainResultKey = rg.getMainResultKey();
            Result mainResult = rg.mainResult();

            StrategyContext ctx = buildStrategyContext(rg, mainResultKey, mainResult);

            if (ctx.symbol.isEmpty() || ctx.timeframe.isEmpty()) {
                throw new Exception("Could not parse symbol and timeframe from main result key: " + mainResultKey);
            }

            // Velas del timeframe principal. Con Precision=M1 la simulacion pasa a hacerse sobre
            // velas de 1 minuto, pero la ventana del periodo se mide siempre sobre las mismas
            // velas que se usan para simular, por lo que basta con un unico juego.
            ArrayList<Candle> candles = loadCandles(ctx.symbol, ctx.timeframe, mainResult);
            ArrayList<Candle> simCandles = candles;

            if (useM1Precision) {
                ArrayList<Candle> m1Candles = loadCandles(ctx.symbol, "M1", mainResult);
                if (m1Candles != null && !m1Candles.isEmpty()) {
                    simCandles = m1Candles;
                    Log.info("MonkeyTest v2: loaded " + m1Candles.size() + " M1 candles for 1-minute precision on " + ctx.symbol);
                } else {
                    Log.warn("MonkeyTest v2: requested Precision=M1 but no M1 data found for " + ctx.symbol
                        + ". Falling back to main timeframe (" + ctx.timeframe + ").");
                }
            }

            periods = buildPeriodTable(rg, mainResultKey, requested);

            for (PeriodDef pd : periods) {
                clearPeriodKeys(rg, pd);

                PeriodResult res;
                if (simCandles == null || simCandles.isEmpty()) {
                    res = new PeriodResult();
                    res.status = "FAILED (NO DATA)";
                    Log.warn("MonkeyTest v2: no BDF candles loaded for " + rg.getName() + " on " + ctx.symbol + " " + ctx.timeframe);
                } else {
                    OrdersList orders = resolveOrders(rg, mainResultKey, pd);
                    boolean needCurves = writeResultsPluginCache && pd.suffix.equals(requested.suffix);
                    res = runMonkeyTestForPeriod(rg, ctx, pd, orders, simCandles, numMonkeys, percentile, rng,
                        needCurves, debugDump);
                }

                publishPeriodResult(rg, pd, res);
                resultsBySuffix.put(pd.suffix, res);
            }

            if (segmentDurationDays > 0) {
                runSegmentedSubTests(rg, ctx, mainResultKey, periods, simCandles, numMonkeys, percentile, segmentDurationDays, rng);
            }
        } catch (Exception e) {
            Log.error("MonkeyTest v2: error computing Monkey Test for strategy " + rg.getName(), e);
        }

        // Claves legacy sin sufijo: solo tienen sentido cuando lo pedido es el agregado FULL. En
        // cualquier otro caso se limpian, para que nunca muestren el valor de un periodo concreto
        // etiquetado como si fuera el total.
        PeriodResult primary = resultsBySuffix.get(requested.suffix);
        if (requested.sampleType == SampleTypes.FullSample && primary != null) {
            writeKeys(rg, "", primary);
        } else {
            for (String base : PERIOD_KEYS) {
                rg.specialValues().set(base, null);
            }
        }

        // El veredicto lo dicta unicamente el periodo pedido en los Input Args.
        String status = (primary != null) ? primary.status : "ERROR";
        boolean testPassed = "PASSED".equals(status);

        boolean existingFilterResult = true;
        try {
            existingFilterResult = rg.specialValues().getBoolean("FilterResult", true);
        } catch (Exception e) {
            // Default to true if not present or unreadable
        }
        rg.specialValues().set("FilterResult", existingFilterResult && testPassed);

        String existingReason = null;
        if (rg.specialValues().containsKey("FiltersResultFailedReason")) {
            existingReason = rg.specialValues().getString("FiltersResultFailedReason");
        }

        if (!testPassed) {
            if ("FAILED (INVALID PERIOD)".equals(status)) {
                rg.specialValues().setString("FiltersResultFailedReason", "Failed Monkey Test (Invalid Period)");
            } else if ("INSUFFICIENT SPACE".equals(status)) {
                rg.specialValues().setString("FiltersResultFailedReason", "Failed Monkey Test (Insufficient Space)");
            } else {
                rg.specialValues().setString("FiltersResultFailedReason", "Failed Monkey Test");
            }
        } else if (existingReason == null || "".equals(existingReason) || "Passed".equals(existingReason)) {
            rg.specialValues().setString("FiltersResultFailedReason", "Passed");
        }

        // Solo con AutoDiscard el resultado real se traslada al motor de SQX para que pueda excluir
        // la estrategia. Por defecto se devuelve siempre true: la marca visual de arriba ya refleja
        // el resultado real sin necesidad de excluir nada.
        boolean passFilters = autoDiscard ? testPassed : true;

        if (writeResultsPluginCache && primary != null && primary.hasFullStats && primary.curves != null) {
            writeCacheArtifacts(rg, requested, primary, numMonkeys, percentile);
        }

        return passFilters;
    }

    // =========================================================
    // Input args
    // =========================================================

    /**
     * La v1 aceptaba replicationMode y shiftingMode en las posiciones 4 y 5. En la v2 no existen,
     * asi que una tarea heredada los pasaria como basura silenciosa. Se avisa explicitamente.
     */
    private void warnOnLegacyArgs(String[] args) {
        for (int i = 3; i < args.length && i < 5; i++) {
            String a = args[i].trim();
            if (a.equalsIgnoreCase("SLTP") || a.equalsIgnoreCase("AvgBars") || a.equalsIgnoreCase("IndivBars")) {
                Log.warn("MonkeyTest v2: input arg '" + a + "' is a v1 replicationMode and no longer applies."
                    + " v2 always uses the average-bars replication. The argument is ignored.");
            } else if (a.equalsIgnoreCase("Constant") || a.equalsIgnoreCase("Random")) {
                Log.warn("MonkeyTest v2: input arg '" + a + "' is a v1 shiftingMode and no longer applies."
                    + " v2 always uses random non-overlapping entries. The argument is ignored.");
            }
        }
    }

    private boolean detectM1Precision(String upperArgs) {
        if (upperArgs.contains("PRECISION=M1") || upperArgs.contains("PRECISION_M1")
                || upperArgs.contains("PRECISION=1M") || upperArgs.contains("PRECISION_1M")) {
            return true;
        }
        // Comprobacion por token, no por subcadena: "contains(',M1')" daria falso positivo con M15.
        for (String a : upperArgs.split(",")) {
            String trimmed = a.trim();
            if (trimmed.equals("M1") || trimmed.equals("1M")) {
                return true;
            }
        }
        return false;
    }

    private double parseSegmentDuration(String inputArgs) {
        if (!inputArgs.toUpperCase().contains("SEGMENTDURATION")) {
            return 0.0;
        }
        for (String a : inputArgs.split(",")) {
            String trimmed = a.trim();
            if (!trimmed.toUpperCase().contains("SEGMENTDURATION")) {
                continue;
            }
            String valStr;
            if (trimmed.indexOf("=") >= 0) {
                valStr = trimmed.substring(trimmed.indexOf("=") + 1).trim();
            } else if (trimmed.indexOf(":") >= 0) {
                valStr = trimmed.substring(trimmed.indexOf(":") + 1).trim();
            } else {
                valStr = trimmed.replaceAll("(?i)SEGMENTDURATION", "").trim();
            }
            try {
                double v = Double.parseDouble(valStr);
                if (v > 0) {
                    Log.info("MonkeyTest v2: SegmentDuration requested = " + v + " days.");
                    return v;
                }
            } catch (Exception e) {
                Log.warn("MonkeyTest v2: could not parse SegmentDuration value from '" + a + "': " + e.getMessage());
            }
        }
        return 0.0;
    }

    // =========================================================
    // Contexto de estrategia: simbolo, instrumento, spread y modo de calculo
    // =========================================================

    private StrategyContext buildStrategyContext(ResultsGroup rg, String mainResultKey, Result mainResult) {
        StrategyContext ctx = new StrategyContext();

        // Simbolo y timeframe del main result key, p.ej. "Main: EURUSD_FTMO/H4"
        if (mainResultKey != null && mainResultKey.startsWith("Main: ")) {
            String cleanKey = mainResultKey.substring(6);
            String[] parts = cleanKey.split("/");
            if (parts.length >= 2) {
                ctx.symbol = parts[0];
                ctx.timeframe = parts[1];
            }
        }

        String lastSettingsXml = null;
        try {
            lastSettingsXml = rg.getLastSettings();
        } catch (Exception e) {
            Log.warn("MonkeyTest v2: could not read lastSettings XML for " + rg.getName() + ": " + e.getMessage());
        }

        ctx.tickSize = resolveTickSize(rg, ctx.symbol);
        resolveSpread(rg, ctx, lastSettingsXml, mainResult);
        resolveMoneyManagementMode(rg, ctx, lastSettingsXml);

        Log.info("MonkeyTest v2 [" + rg.getName() + "]: symbol=" + ctx.symbol + " tf=" + ctx.timeframe
            + " tickSize=" + ctx.tickSize + " spread=" + ctx.spreadPoints + " points (" + ctx.spreadSource + ")"
            + " -> " + ctx.spreadPrice + " in price; MM=" + ctx.mmName + " -> MODE " + ctx.modeLabel);

        return ctx;
    }

    /** tickSize del InstrumentInfo del simbolo; sin el no se puede convertir el spread a precio. */
    private double resolveTickSize(ResultsGroup rg, String symbol) {
        try {
            com.strategyquant.tradinglib.results.SymbolsMap sm = rg.symbols();
            if (sm != null) {
                com.strategyquant.tradinglib.results.SymbolInfo si = sm.get(symbol);
                if (si != null && si.InstrumentInfo() != null && si.InstrumentInfo().tickSize > 0) {
                    return si.InstrumentInfo().tickSize;
                }
                InstrumentInfo def = sm.getDefaultInstrumentInfo();
                if (def != null && def.tickSize > 0) {
                    return def.tickSize;
                }
            }
        } catch (Exception e) {
            Log.warn("MonkeyTest v2: could not resolve InstrumentInfo for " + symbol + ": " + e.getMessage());
        }
        Log.warn("MonkeyTest v2: no tickSize available for " + symbol + ", spread cannot be converted to price.");
        return 0.0;
    }

    /**
     * El spread SIEMPRE sale del XML de lastSettings (Setup/Chart/@spread), que es el valor con el
     * que se backtesteo la estrategia original. No se usa el spread real por vela aunque los datos
     * lo traigan: los monos deben operar bajo exactamente las mismas condiciones que la referencia
     * contra la que se comparan, o la comparacion deja de ser valida.
     */
    private void resolveSpread(ResultsGroup rg, StrategyContext ctx, String lastSettingsXml, Result mainResult) {
        Double fromXml = extractChartSpread(lastSettingsXml, ctx.symbol, mainResult);
        if (fromXml != null) {
            ctx.spreadPoints = fromXml;
            ctx.spreadSource = "lastSettings XML";
            ctx.spreadPrice = ctx.spreadPoints * ctx.tickSize;
            return;
        }

        try {
            com.strategyquant.tradinglib.results.SymbolsMap sm = rg.symbols();
            if (sm != null) {
                com.strategyquant.tradinglib.results.SymbolInfo si = sm.get(ctx.symbol);
                InstrumentInfo ii = (si != null) ? si.InstrumentInfo() : sm.getDefaultInstrumentInfo();
                if (ii != null && ii.defaultSpread > 0) {
                    ctx.spreadPoints = ii.defaultSpread;
                    ctx.spreadSource = "InstrumentInfo.defaultSpread";
                    ctx.spreadPrice = ctx.spreadPoints * ctx.tickSize;
                    Log.warn("MonkeyTest v2 [" + rg.getName() + "]: spread not found in XML, falling back to"
                        + " InstrumentInfo.defaultSpread = " + ctx.spreadPoints);
                    return;
                }
            }
        } catch (Exception e) {
            // cae al cero de abajo
        }

        ctx.spreadPoints = 0.0;
        ctx.spreadPrice = 0.0;
        ctx.spreadSource = "none (zero)";
        Log.warn("MonkeyTest v2 [" + rg.getName() + "]: could not resolve spread for " + ctx.symbol
            + ". Using zero -- monkeys will be systematically favoured.");
    }

    /**
     * Busca Setup/Chart/@spread emparejando por @symbol. Puede haber varios Setup (cross-checks
     * sobre mercados adicionales), asi que coger el primero daria el spread del mercado equivocado.
     */
    private Double extractChartSpread(String xml, String symbol, Result mainResult) {
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

            String targetSymbol = symbol;
            try {
                String fromResult = mainResult.getString("Symbol", null);
                if (fromResult != null && !fromResult.trim().isEmpty()) {
                    targetSymbol = fromResult.trim();
                }
            } catch (Exception ignored) {
            }

            Element fallback = null;
            for (Object o : elSetups.getChildren("Setup")) {
                Element elSetup = (Element) o;
                Element elChart = elSetup.getChild("Chart");
                if (elChart == null) {
                    continue;
                }
                if (fallback == null) {
                    fallback = elChart;
                }
                String chartSymbol = elChart.getAttributeValue("symbol");
                if (chartSymbol != null && chartSymbol.equalsIgnoreCase(targetSymbol)) {
                    String v = elChart.getAttributeValue("spread");
                    if (v != null && !v.trim().isEmpty()) {
                        return Double.parseDouble(v.trim());
                    }
                }
            }

            if (fallback != null) {
                String v = fallback.getAttributeValue("spread");
                if (v != null && !v.trim().isEmpty()) {
                    Log.warn("MonkeyTest v2: no Chart matched symbol " + targetSymbol + ", using first Setup spread.");
                    return Double.parseDouble(v.trim());
                }
            }
        } catch (Exception e) {
            Log.warn("MonkeyTest v2: could not extract spread from XML: " + e.getMessage());
        }
        return null;
    }

    /**
     * El money management declarado manda, sin degradacion. FixedSize -> MODO A siempre, aunque los
     * Order.Size observados varien: elegir mal el modo no es neutral, porque el MODO B normaliza
     * cada desplazamiento por el precio de entrada y sobre una estrategia de lote fijo pondera de
     * mas los trades abiertos a precios bajos.
     */
    private void resolveMoneyManagementMode(ResultsGroup rg, StrategyContext ctx, String lastSettingsXml) {
        try {
            Element root = XMLUtil.stringToElement(lastSettingsXml);
            Element elRMM = (root != null) ? root.getChild("RiskMoneyManagement") : null;
            Element elMM = (elRMM != null) ? elRMM.getChild("MoneyManagement") : null;
            if (elMM != null) {
                MoneyManagementMethod mm =
                    com.strategyquant.tradinglib.project.ProjectConfigHelper.getMoneyManagement(elMM);
                if (mm != null) {
                    ctx.mmName = (mm.name != null && !mm.name.trim().isEmpty()) ? mm.name : mm.getClass().getSimpleName();
                    ctx.modeA = isFixedSizeMM(ctx.mmName, mm);
                    ctx.modeLabel = ctx.modeA ? "A (FixedSize)" : "B (Variable)";
                    return;
                }
            }
            // Segunda via: el atributo type del Method con use="true".
            String activeType = findActiveMMType(elMM);
            if (activeType != null) {
                ctx.mmName = activeType;
                ctx.modeA = activeType.equalsIgnoreCase("FixedSize");
                ctx.modeLabel = ctx.modeA ? "A (FixedSize)" : "B (Variable)";
                return;
            }
        } catch (Exception e) {
            Log.warn("MonkeyTest v2 [" + rg.getName() + "]: could not read MoneyManagement from XML: " + e.getMessage());
        }

        // Solo si el XML no se puede parsear se recurre al chequeo empirico sobre los tamanos.
        ctx.mmName = "unknown (empirical)";
        ctx.modeA = allSizesEqual(rg);
        ctx.modeLabel = ctx.modeA ? "A (FixedSize)" : "B (Variable)";
        Log.warn("MonkeyTest v2 [" + rg.getName() + "]: MoneyManagement unreadable, falling back to empirical"
            + " size check -> MODE " + ctx.modeLabel);
    }

    private boolean isFixedSizeMM(String name, MoneyManagementMethod mm) {
        if (name != null && name.replace(" ", "").equalsIgnoreCase("FixedSize")) {
            return true;
        }
        return mm != null && mm.getClass().getSimpleName().equalsIgnoreCase("FixedSize");
    }

    private String findActiveMMType(Element elMM) {
        if (elMM == null) {
            return null;
        }
        try {
            for (Object o : elMM.getChildren("Method")) {
                Element m = (Element) o;
                String use = m.getAttributeValue("use");
                if ("true".equalsIgnoreCase(use) || "1".equals(use)) {
                    return m.getAttributeValue("type");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Diagnostico y ultimo recurso: comprueba si todos los trades usan el mismo lotaje. */
    private boolean allSizesEqual(ResultsGroup rg) {
        try {
            OrdersList all = rg.orders();
            if (all == null || all.size() < 2) {
                return true;
            }
            double first = Double.NaN;
            for (int i = 0; i < all.size(); i++) {
                Order o = all.get(i);
                if (o.isBalanceOrder()) {
                    continue;
                }
                if (Double.isNaN(first)) {
                    first = o.Size;
                } else if (Math.abs(o.Size - first) > 1e-5) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    // =========================================================
    // Periodos
    // =========================================================

    /** Traduce el argumento de periodo a su PeriodDef. Cualquier valor no reconocido cae en FULL. */
    private PeriodDef parsePeriod(String periodArg) {
        if ("IS".equals(periodArg)) {
            return new PeriodDef(SampleTypes.InSample, "_IS");
        }
        if ("OOS".equals(periodArg)) {
            return new PeriodDef(SampleTypes.OutOfSample, "_OOS");
        }
        if ("ISV".equals(periodArg)) {
            return new PeriodDef(SampleTypes.InSampleValidation, "_ISV");
        }
        if ("FULL".equals(periodArg)) {
            return new PeriodDef(SampleTypes.FullSample, "_Full");
        }

        int n = parsePartNumber(periodArg, "OOS");
        if (n > 0) {
            return new PeriodDef((byte) (SampleTypes.OutOfSample + n), "_OOS" + n);
        }
        n = parsePartNumber(periodArg, "ISV");
        if (n > 0) {
            return new PeriodDef((byte) (SampleTypes.InSampleValidation + n), "_ISV" + n);
        }

        Log.warn("MonkeyTest v2: unrecognized period argument '" + periodArg + "'. Valid values: FULL, IS, OOS, ISV,"
            + " OOS1..OOS10, ISV1..ISV10. Defaulting to FULL.");
        return new PeriodDef(SampleTypes.FullSample, "_Full");
    }

    /** Devuelve 1..MAX_PARTS para "<prefix>N", o -1 si no encaja o esta fuera de rango. */
    private int parsePartNumber(String periodArg, String prefix) {
        if (!periodArg.startsWith(prefix) || periodArg.length() <= prefix.length()) {
            return -1;
        }
        String digits = periodArg.substring(prefix.length());
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return -1;
            }
        }
        try {
            int n = Integer.parseInt(digits);
            if (n >= 1 && n <= MAX_PARTS) {
                return n;
            }
            Log.warn("MonkeyTest v2: period part number out of range 1.." + MAX_PARTS + ": '" + periodArg + "'.");
        } catch (NumberFormatException e) {
            // cae al -1 de abajo
        }
        return -1;
    }

    /**
     * Periodos a calcular en esta ejecucion. Si se pidio un periodo concreto, solo ese. Si se pidio
     * FULL, ademas del agregado se sondean todos los periodos con operaciones para poder
     * consultarlos aislados: cada uno simula unicamente sus propios trades.
     */
    private ArrayList<PeriodDef> buildPeriodTable(ResultsGroup rg, String mainResultKey, PeriodDef requested) {
        ArrayList<PeriodDef> list = new ArrayList<>();

        if (requested.sampleType != SampleTypes.FullSample) {
            list.add(requested);
            return list;
        }

        list.add(new PeriodDef(SampleTypes.FullSample, "_Full"));

        if (hasOrders(rg, mainResultKey, SampleTypes.InSample)) {
            list.add(new PeriodDef(SampleTypes.InSample, "_IS"));
        }

        ArrayList<Integer> oosParts = new ArrayList<>();
        for (int n = 1; n <= MAX_PARTS; n++) {
            if (hasOrders(rg, mainResultKey, (byte) (SampleTypes.OutOfSample + n))) {
                oosParts.add(n);
            }
        }
        // Con una sola parte OOS, SQX copia sus stats sobre el OOS agregado: son el mismo periodo.
        // Se simula una vez y se publica bajo ambos sufijos.
        boolean singleOosPart = oosParts.size() == 1 && oosParts.get(0) == 1;

        if (hasOrders(rg, mainResultKey, SampleTypes.OutOfSample)) {
            PeriodDef oos = new PeriodDef(SampleTypes.OutOfSample, "_OOS");
            if (singleOosPart) {
                oos.alsoPublishAs = "_OOS1";
            }
            list.add(oos);
        }
        if (!singleOosPart) {
            for (int n : oosParts) {
                list.add(new PeriodDef((byte) (SampleTypes.OutOfSample + n), "_OOS" + n));
            }
        }

        if (hasOrders(rg, mainResultKey, SampleTypes.InSampleValidation)) {
            list.add(new PeriodDef(SampleTypes.InSampleValidation, "_ISV"));
        }
        for (int n = 1; n <= MAX_PARTS; n++) {
            if (hasOrders(rg, mainResultKey, (byte) (SampleTypes.InSampleValidation + n))) {
                list.add(new PeriodDef((byte) (SampleTypes.InSampleValidation + n), "_ISV" + n));
            }
        }

        return list;
    }

    private boolean hasOrders(ResultsGroup rg, String mainResultKey, byte sampleType) {
        try {
            OrdersList o = rg.orders().filterWithClone(mainResultKey, Directions.Both, sampleType);
            return o != null && o.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ordenes del periodo, filtradas directamente por su sample type. No se sustituyen nunca por
     * las de otro periodo: si el periodo pedido no tiene operaciones se devuelve vacio y el
     * llamante lo reporta como LOW TRADES / FAILED (INVALID PERIOD).
     */
    private OrdersList resolveOrders(ResultsGroup rg, String mainResultKey, PeriodDef pd) {
        OrdersList orders = null;
        try {
            orders = rg.orders().filterWithClone(mainResultKey, Directions.Both, pd.sampleType);
        } catch (Exception e) {
            Log.warn("MonkeyTest v2: error filtering orders for " + pd.label() + ": " + e.getMessage());
        }

        if (orders != null && orders.size() > 0) {
            return orders;
        }

        // Unica equivalencia admitida, y no es una sustitucion por otro periodo: con una sola parte
        // OOS, OOS1 y el OOS agregado son literalmente el mismo tramo.
        if (pd.sampleType == SampleTypes.OutOfSample1) {
            boolean anyNumberedPart = false;
            for (int n = 1; n <= MAX_PARTS; n++) {
                if (hasOrders(rg, mainResultKey, (byte) (SampleTypes.OutOfSample + n))) {
                    anyNumberedPart = true;
                    break;
                }
            }
            if (!anyNumberedPart) {
                try {
                    OrdersList aggregated = rg.orders().filterWithClone(mainResultKey, Directions.Both, SampleTypes.OutOfSample);
                    if (aggregated != null && aggregated.size() > 0) {
                        Log.info("MonkeyTest v2: strategy [" + rg.getName() + "] has a single OOS segment, so OOS1 is the same period as OOS.");
                        return aggregated;
                    }
                } catch (Exception e) {
                    Log.warn("MonkeyTest v2: error filtering aggregated OOS orders: " + e.getMessage());
                }
            }
        }

        return orders;
    }

    // =========================================================
    // Publicacion de resultados
    // =========================================================

    private void clearPeriodKeys(ResultsGroup rg, PeriodDef pd) {
        for (String base : PERIOD_KEYS) {
            rg.specialValues().set(base + pd.suffix, null);
            if (pd.alsoPublishAs != null) {
                rg.specialValues().set(base + pd.alsoPublishAs, null);
            }
        }
    }

    private void clearSegmentedPeriodKeys(ResultsGroup rg, String label) {
        try {
            int prevCount = rg.specialValues().getInt("MonkeyTest_SegCount_" + label, 0);
            int maxClear = Math.max(prevCount, 50);
            for (int j = 1; j <= maxClear; j++) {
                String suffix = "_Seg_" + label + "_" + j;
                for (String base : PERIOD_KEYS) {
                    rg.specialValues().set(base + suffix, null);
                }
            }
            rg.specialValues().set("MonkeyTest_SegCount_" + label, null);
            rg.specialValues().set("MonkeyTest_SegDays_" + label, null);
        } catch (Exception ignored) {
        }
    }

    private void publishPeriodResult(ResultsGroup rg, PeriodDef pd, PeriodResult res) {
        writeKeys(rg, pd.suffix, res);
        if (pd.alsoPublishAs != null) {
            writeKeys(rg, pd.alsoPublishAs, res);
        }
    }

    private void writeKeys(ResultsGroup rg, String suffix, PeriodResult res) {
        rg.specialValues().setString("MonkeyTestResult" + suffix, res.status);
        rg.specialValues().setString("MonkeyTestPercentile" + suffix,
            res.percentileText != null ? res.percentileText : "N/A");
        rg.specialValues().setString("MonkeyTestZScore" + suffix,
            res.zScoreText != null ? res.zScoreText : "N/A");

        // Los valores numericos solo se publican si el test se completo. La v1 escribia 0.0 en
        // LOW TRADES / ERROR, y la columna mostraba 0.00 en lugar de N/A -- ademas de que
        // CVSintetica consumia ese cero como si fuera una mediana real.
        if (res.hasFullStats) {
            rg.specialValues().set("MonkeyTestMedianProfit" + suffix, res.medianMonkey);
            rg.specialValues().set("MonkeyTestRealProfit" + suffix, res.realProfit);
            rg.specialValues().set("MonkeyTestExposureRatio" + suffix, res.exposureRatio);
            rg.specialValues().set("MonkeyTestSpread" + suffix, res.spreadPoints);
            rg.specialValues().setString("MonkeyTestMode" + suffix, res.modeLabel);
        } else {
            rg.specialValues().set("MonkeyTestMedianProfit" + suffix, null);
            rg.specialValues().set("MonkeyTestRealProfit" + suffix, null);
            rg.specialValues().set("MonkeyTestExposureRatio" + suffix, null);
            rg.specialValues().set("MonkeyTestSpread" + suffix, null);
            rg.specialValues().set("MonkeyTestMode" + suffix, null);
        }
    }

    private void runSegmentedSubTests(ResultsGroup rg, StrategyContext ctx, String mainResultKey,
                                      ArrayList<PeriodDef> periods, ArrayList<Candle> simCandles,
                                      int numMonkeys, double percentile, double segmentDurationDays, Random rng) {
        if (segmentDurationDays <= 0 || simCandles == null || simCandles.isEmpty()) {
            return;
        }

        try {
            rg.specialValues().set("MonkeyTest_SegTargetDays", segmentDurationDays);

            for (PeriodDef pd : periods) {
                String label = pd.label();
                clearSegmentedPeriodKeys(rg, label);

                OrdersList orders = resolveOrders(rg, mainResultKey, pd);
                if (orders == null || orders.size() == 0) {
                    continue;
                }

                long tMin = Long.MAX_VALUE;
                long tMax = Long.MIN_VALUE;
                int validTradeCount = 0;
                for (int i = 0; i < orders.size(); i++) {
                    Order o = orders.get(i);
                    if (isRealTrade(o)) {
                        validTradeCount++;
                        if (o.OpenTime < tMin) tMin = o.OpenTime;
                        if (o.CloseTime > tMax) tMax = o.CloseTime;
                    }
                }

                if (validTradeCount == 0 || tMin >= tMax) {
                    continue;
                }

                double totalDays = (double) (tMax - tMin) / (1000.0 * 60.0 * 60.0 * 24.0);
                if (totalDays <= 0) {
                    continue;
                }

                int k = (int) Math.round(totalDays / segmentDurationDays);
                k = Math.max(1, Math.min(50, k));
                double actualSegDays = totalDays / k;

                rg.specialValues().set("MonkeyTest_SegCount_" + label, k);
                rg.specialValues().set("MonkeyTest_SegDays_" + label, Math.round(actualSegDays * 10.0) / 10.0);

                long segmentMs = (long) Math.ceil((double) (tMax - tMin) / (double) k);

                for (int j = 1; j <= k; j++) {
                    long segStart = tMin + (long) (j - 1) * segmentMs;
                    long segEnd = (j == k) ? tMax : (tMin + (long) j * segmentMs);

                    OrdersList subOrders = new OrdersList("SubOrders");
                    for (int i = 0; i < orders.size(); i++) {
                        Order o = orders.get(i);
                        if (o.OpenTime >= segStart && o.OpenTime <= segEnd) {
                            subOrders.add(o);
                        }
                    }

                    ArrayList<Candle> subCandles = new ArrayList<>();
                    for (Candle c : simCandles) {
                        if (c.time >= segStart && c.time <= segEnd) {
                            subCandles.add(c);
                        }
                    }

                    // El volcado va desactivado en la ruta segmentada aunque Debug este presente:
                    // un SegmentDuration sobre FULL genera decenas de sub-segmentos y multiplicaria
                    // el fichero sin mostrar nada que el periodo principal no muestre ya.
                    PeriodResult subRes = runMonkeyTestForPeriod(rg, ctx, pd, subOrders, subCandles,
                        numMonkeys, percentile, rng, false, false);

                    writeKeys(rg, "_Seg_" + label + "_" + j, subRes);
                }
            }
        } catch (Exception e) {
            Log.error("MonkeyTest v2: error executing segmented Monkey Test for " + rg.getName(), e);
        }
    }

    // =========================================================
    // Calculo del test para un periodo
    // =========================================================

    /**
     * Nucleo de la v2. En vez de reconstruir el P&L de cada trade de cada mono, se calibra una
     * unica constante K por periodo -- cuanto dinero vale una unidad de desplazamiento en la
     * estrategia original -- y el profit de cada mono es K por su desplazamiento acumulado.
     */
    private PeriodResult runMonkeyTestForPeriod(ResultsGroup rg, StrategyContext ctx, PeriodDef pd,
                                                OrdersList orders, ArrayList<Candle> simCandles,
                                                int numMonkeys, double percentile, Random rng,
                                                boolean needCurves, boolean debugDump) {
        PeriodResult res = new PeriodResult();
        res.modeLabel = ctx.modeLabel;
        res.spreadPoints = ctx.spreadPoints;

        try {
            // --- 1. Trades reales del periodo -------------------------------------------------
            ArrayList<Order> tradeOrders = new ArrayList<>();
            if (orders != null) {
                for (int i = 0; i < orders.size(); i++) {
                    Order o = orders.get(i);
                    if (isRealTrade(o)) {
                        tradeOrders.add(o);
                    }
                }
            }
            int n = tradeOrders.size();

            if (n == 0 && pd.isNumberedPart()) {
                res.status = "FAILED (INVALID PERIOD)";
                Log.warn("MonkeyTest v2: strategy [" + rg.getName() + "] has no trades in period " + pd.label()
                    + " -- that segment does not exist on this strategy. -> FAILED (INVALID PERIOD)");
                return res;
            }

            if (n < MIN_TRADES) {
                if (n == 0 && pd.sampleType != SampleTypes.FullSample) {
                    Log.warn("MonkeyTest v2: strategy [" + rg.getName() + "] has no trades in the " + pd.label()
                        + " period. Verify that the last backtest has that sample period configured. -> LOW TRADES.");
                } else {
                    Log.warn("MonkeyTest v2: strategy [" + rg.getName() + "] has too few trades in the " + pd.label()
                        + " period (" + n + " trades, minimum " + MIN_TRADES + "). -> LOW TRADES.");
                }
                res.status = "LOW TRADES";
                return res;
            }

            // --- 2. Calibracion del ratio K ---------------------------------------------------
            // K se pondera por magnitud: sum(|PL bruto|) / sum(|desplazamiento|). Con lotaje fijo
            // da identicamente el mismo numero que neto/neto, pero nunca divide por casi cero. El
            // signo no se pierde: vive en el desplazamiento acumulado de cada mono, no en K.
            int[] dirs = new int[n];
            double sumAbsGross = 0.0;
            double sumAbsUnit = 0.0;
            double sumUnit = 0.0;
            double commSwapTotal = 0.0;
            int commApplied = 0;
            int commNotApplied = 0;

            for (int i = 0; i < n; i++) {
                Order o = tradeOrders.get(i);
                dirs[i] = o.isShort() ? -1 : 1;

                double disp = (o.ClosePrice - o.OpenPrice) * dirs[i];
                double unit = ctx.modeA ? disp : (o.OpenPrice != 0 ? disp / o.OpenPrice * 100.0 : 0.0);

                // CommSwapApplied indica si CommSwap ya esta sumado dentro de PL. La v1 lo restaba
                // siempre e incondicionalmente, lo que duplicaba los costes cuando no lo estaba.
                double gross = o.CommSwapApplied ? (o.PL - o.CommSwap) : o.PL;
                if (o.CommSwapApplied) {
                    commApplied++;
                } else {
                    commNotApplied++;
                }

                sumAbsGross += Math.abs(gross);
                sumAbsUnit += Math.abs(unit);
                sumUnit += unit;
                commSwapTotal += o.CommSwap;
            }

            if (sumAbsUnit <= 1e-12) {
                res.status = "ERROR";
                Log.error("MonkeyTest v2: strategy [" + rg.getName() + "] period " + pd.label()
                    + " has zero total price displacement; cannot calibrate the money-per-unit ratio.");
                return res;
            }

            double ratioK = sumAbsGross / sumAbsUnit;
            res.ratioK = ratioK;

            // --- 3. Ventana temporal del periodo ----------------------------------------------
            long tMin = Long.MAX_VALUE;
            long tMax = Long.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                Order o = tradeOrders.get(i);
                if (o.OpenTime < tMin) tMin = o.OpenTime;
                if (o.CloseTime > tMax) tMax = o.CloseTime;
            }

            int barsCount = simCandles.size();
            long tfMs = inferTimeframeMs(simCandles);

            int idxMin = findBarIndex(simCandles, tMin);
            int idxMax = findBarIndex(simCandles, tMax);
            if (idxMin < 0 || idxMax < 0 || idxMax <= idxMin) {
                idxMin = 0;
                idxMax = barsCount - 1;
            }
            int m = idxMax - idxMin + 1;

            // --- 4. Duracion media y dithering ------------------------------------------------
            // La duracion se mide como posicion FRACCIONARIA en el eje de barras. Usar indices
            // enteros cuantizaria cada trade antes de promediar y anularia el dithering cuando la
            // estrategia se backtesteo a mayor precision que la usada aqui (H4 sobre backtest M1).
            double sumBars = 0.0;
            for (int i = 0; i < n; i++) {
                Order o = tradeOrders.get(i);
                double d = posOf(simCandles, o.CloseTime, tfMs) - posOf(simCandles, o.OpenTime, tfMs);
                if (d > 0) {
                    sumBars += d;
                }
            }
            double exactBars = sumBars / n;
            res.exactBars = exactBars;

            int baseBars = (int) Math.floor(exactBars);
            if (baseBars < 1) {
                Log.warn("MonkeyTest v2: strategy [" + rg.getName() + "] period " + pd.label()
                    + " has an average trade duration below one bar (" + String.format(java.util.Locale.US, "%.3f", exactBars)
                    + "). Consider Precision=M1 for a meaningful simulation.");
                baseBars = 1;
            }
            int numExtra = (int) Math.round(n * (exactBars - Math.floor(exactBars)));
            if (numExtra < 0) numExtra = 0;
            if (numExtra > n) numExtra = n;

            int targetTotalBars = n * baseBars + numExtra;
            res.exposureRatio = (sumBars > 0) ? (targetTotalBars / sumBars) : 1.0;

            // El volcado va ANTES de la guarda para que un INSUFFICIENT SPACE quede diagnosticado.
            if (debugDump) {
                dumpCalibration(rg, pd, ctx, n, sumAbsGross, sumAbsUnit, sumUnit, commSwapTotal,
                    commApplied, commNotApplied, ratioK, exactBars, baseBars, numExtra,
                    idxMin, idxMax, m, res.exposureRatio);
            }

            // Sin solapamiento en la estrategia original se cumple sum(dur) <= M, asi que esto es
            // teoricamente inalcanzable; la guarda existe para detectar datos corruptos.
            // El requisito real es sumDur <= m-1: con sumDur == m exacto la holgura seria cero y
            // la ultima salida caeria en idxMax+1, una barra fuera de la ventana evaluada.
            if (targetTotalBars >= m) {
                res.status = "INSUFFICIENT SPACE";
                Log.warn("MonkeyTest v2: strategy [" + rg.getName() + "] period " + pd.label()
                    + " needs " + targetTotalBars + " bars for " + n + " trades but the period only spans " + m
                    + " bars. -> INSUFFICIENT SPACE");
                return res;
            }

            // --- 5. Referencia de la estrategia original --------------------------------------
            double initialBalance = tradeOrders.get(0).AccountBalance - tradeOrders.get(0).PL;
            double realProfit = ratioK * sumUnit + commSwapTotal;
            double netProfitSQX = readNetProfit(rg, pd);

            // --- 6. Simulacion de los monos ---------------------------------------------------
            double[] monkeyProfits = new double[numMonkeys];
            double[][] curves = needCurves ? new double[numMonkeys][] : null;

            boolean invariantsWarned = false;

            for (int mk = 0; mk < numMonkeys; mk++) {
                int[] dur = ditherDurations(n, baseBars, numExtra, rng);
                int[] entries = layoutEntries(idxMin, m, dur, rng);

                // Las invariantes se comprueban SIEMPRE, no solo en modo debug: si solo corriesen
                // con Debug activo, una violacion en produccion pasaria desapercibida, que es justo
                // el escenario a detectar. Solo se reporta la primera de cada periodo.
                String violation = checkLayoutInvariants(entries, dur, idxMin, idxMax, baseBars, numExtra);
                if (violation != null && !invariantsWarned) {
                    invariantsWarned = true;
                    Log.warn("MonkeyTest v2: LAYOUT INVARIANT VIOLATED for [" + rg.getName() + "] "
                        + pd.label() + " monkey #" + mk + " -- " + violation
                        + ". This is a bug in the layout algorithm, never a market condition."
                        + " Further violations in this period are not reported.");
                }

                if (debugDump && mk == 0) {
                    dumpMonkeyLayout(rg, pd, idxMin, m, entries, dur, violation);
                }

                double acc = 0.0;
                double[] curve = needCurves ? new double[n + 1] : null;
                if (curve != null) {
                    curve[0] = initialBalance;
                }

                for (int k = 0; k < n; k++) {
                    int entryIdx = entries[k];
                    int exitIdx = entryIdx + dur[k];
                    if (entryIdx < 0) entryIdx = 0;
                    if (entryIdx >= barsCount) entryIdx = barsCount - 1;
                    if (exitIdx >= barsCount) exitIdx = barsCount - 1;
                    if (exitIdx < entryIdx) exitIdx = entryIdx;

                    double entryPrice = simCandles.get(entryIdx).open;
                    double exitPrice = simCandles.get(exitIdx).open;

                    // El spread se resta una sola vez por trade: entrar en ask y salir en bid
                    // equivale exactamente a penalizar el desplazamiento en un spread completo.
                    double disp = (exitPrice - entryPrice) * dirs[k] - ctx.spreadPrice;
                    double unit = ctx.modeA ? disp : (entryPrice != 0 ? disp / entryPrice * 100.0 : 0.0);
                    acc += unit;

                    if (curve != null) {
                        curve[k + 1] = initialBalance + ratioK * acc + commSwapTotal * ((k + 1) / (double) n);
                    }
                }

                monkeyProfits[mk] = ratioK * acc + commSwapTotal;
                if (curves != null) {
                    curves[mk] = curve;
                }
            }

            // --- 7. Estadistica ---------------------------------------------------------------
            double sum = 0;
            for (double p : monkeyProfits) sum += p;
            double mean = sum / numMonkeys;

            double sqDiffSum = 0;
            for (double p : monkeyProfits) sqDiffSum += (p - mean) * (p - mean);
            double variance = numMonkeys > 1 ? sqDiffSum / (numMonkeys - 1) : 0.0;
            double std = Math.sqrt(variance);

            double zScore = std > 0 ? (realProfit - mean) / std : 0.0;

            double[] sortedProfits = monkeyProfits.clone();
            Arrays.sort(sortedProfits);
            int thresholdIndex = (int) Math.floor(numMonkeys * (percentile / 100.0));
            if (thresholdIndex < 0) thresholdIndex = 0;
            if (thresholdIndex >= numMonkeys) thresholdIndex = numMonkeys - 1;
            double thresholdVal = sortedProfits[thresholdIndex];

            int beaten = 0;
            for (double p : monkeyProfits) if (p < realProfit) beaten++;
            double rankPercentile = (beaten / (double) numMonkeys) * 100.0;

            double medianMonkey;
            int len = sortedProfits.length;
            if (len % 2 == 1) {
                medianMonkey = sortedProfits[len / 2];
            } else {
                medianMonkey = (sortedProfits[len / 2 - 1] + sortedProfits[len / 2]) / 2.0;
            }

            res.status = (realProfit > thresholdVal) ? "PASSED" : "FAILED";
            res.percentileText = String.format(java.util.Locale.US, "%.2f%%", rankPercentile);
            res.zScoreText = String.format(java.util.Locale.US, "%.2f", zScore);

            res.hasFullStats = true;
            res.curves = curves;
            res.sortedProfits = sortedProfits;
            res.numTrades = n;
            res.initialBalance = initialBalance;
            res.realProfit = realProfit;
            res.netProfitSQX = netProfitSQX;
            res.thresholdVal = thresholdVal;
            res.meanMonkey = mean;
            res.stdMonkey = std;
            res.medianMonkey = medianMonkey;
            res.zScore = zScore;
            res.rankPercentile = rankPercentile;
            res.tMin = tMin;
            res.tMax = tMax;

            Log.info("MonkeyTest v2 [" + rg.getName() + "] " + pd.label() + ": mode=" + ctx.modeLabel
                + " K=" + String.format(java.util.Locale.US, "%.6f", ratioK)
                + " trades=" + n + " avgBars=" + String.format(java.util.Locale.US, "%.4f", exactBars)
                + " (base=" + baseBars + " extra=" + numExtra + ")"
                + " exposureRatio=" + String.format(java.util.Locale.US, "%.4f", res.exposureRatio)
                + " realProfit=" + String.format(java.util.Locale.US, "%.2f", realProfit)
                + " netProfitSQX=" + String.format(java.util.Locale.US, "%.2f", netProfitSQX)
                + " -> " + res.status);

        } catch (Exception e) {
            res.status = "ERROR";
            res.hasFullStats = false;
            Log.error("MonkeyTest v2: error computing Monkey Test for strategy " + rg.getName()
                + " period " + pd.label(), e);
        }

        return res;
    }

    /** Excluye balance orders y pseudo-trades de longitud y P&L nulos. */
    private boolean isRealTrade(Order o) {
        if (o.isBalanceOrder()) {
            return false;
        }
        return !(o.OpenPrice == o.ClosePrice && Math.abs(o.PL) < 1e-9);
    }

    /**
     * Posicion fraccionaria de un instante en el eje de barras. Al apoyarse en el indice, los
     * fines de semana y festivos no cuentan (los indices son contiguos aunque el calendario salte),
     * mientras que el termino fraccionario conserva la resolucion sub-barra del backtest original.
     */
    private double posOf(ArrayList<Candle> candles, long time, long tfMs) {
        int idx = findBarIndex(candles, time);
        if (idx < 0) {
            return 0.0;
        }
        double frac = 0.0;
        if (tfMs > 0) {
            frac = (double) (time - candles.get(idx).time) / (double) tfMs;
            if (frac < 0) frac = 0.0;
            if (frac > 1) frac = 1.0;
        }
        return idx + frac;
    }

    /**
     * Reparte las numExtra barras extra entre trades elegidos al azar, sin repeticion (Fisher-Yates
     * parcial). El numero de trades largos es siempre exactamente numExtra, asi que la exposicion
     * total es identica en todos los monos: solo se aleatoriza CUALES son, no CUANTOS. Una
     * probabilidad independiente por trade parece mas aleatoria pero haria variar la exposicion
     * entre monos segun una binomial, destruyendo justo la propiedad que los hace comparables.
     */
    private int[] ditherDurations(int n, int baseBars, int numExtra, Random rng) {
        int[] dur = new int[n];
        Arrays.fill(dur, baseBars);

        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        for (int i = 0; i < numExtra && i < n; i++) {
            int j = i + rng.nextInt(n - i);
            int tmp = idx[i];
            idx[i] = idx[j];
            idx[j] = tmp;
            dur[idx[i]]++;
        }
        return dur;
    }

    /**
     * Coloca las n entradas dentro de la ventana [idxMin, idxMin+m) repartiendo la holgura sobrante
     * en huecos aleatorios. Garantiza cero solapamiento (la separacion es la duracion real de cada
     * trade, no un promedio redondeado) y que el ultimo trade termine dentro del periodo.
     */
    private int[] layoutEntries(int idxMin, int m, int[] dur, Random rng) {
        int n = dur.length;
        int sumDur = 0;
        for (int d : dur) {
            sumDur += d;
        }
        int slack = m - sumDur;
        if (slack < 0) {
            slack = 0;
        }

        double[] cuts = new double[n];
        for (int i = 0; i < n; i++) {
            cuts[i] = rng.nextDouble();
        }
        Arrays.sort(cuts);

        int[] entries = new int[n];
        int cursor = idxMin;
        int prevCut = 0;
        for (int k = 0; k < n; k++) {
            int cut = (int) Math.floor(cuts[k] * slack);
            if (cut < prevCut) {
                cut = prevCut;
            }
            entries[k] = cursor + (cut - prevCut);
            cursor = entries[k] + dur[k];
            prevCut = cut;
        }
        return entries;
    }

    /**
     * Comprueba las tres invariantes del layout de un mono. Devuelve null si todo esta bien, o el
     * mensaje describiendo la primera violacion encontrada. Coste O(n), despreciable frente a la
     * simulacion, asi que corre en todos los monos y no solo en modo debug.
     */
    private String checkLayoutInvariants(int[] entries, int[] dur, int idxMin, int idxMax,
                                         int baseBars, int numExtra) {
        int n = dur.length;
        if (n == 0) {
            return null;
        }

        // A3: el dithering repartio exactamente las barras planificadas.
        int sumDur = 0;
        for (int d : dur) {
            sumDur += d;
        }
        int expected = n * baseBars + numExtra;
        if (sumDur != expected) {
            return "A3 (dithering count): sum(dur)=" + sumDur + " but n*baseBars+numExtra=" + expected;
        }

        // A2 (inicio): ningun mono empieza antes de la ventana.
        if (entries[0] < idxMin) {
            return "A2 (window start): entries[0]=" + entries[0] + " < idxMin=" + idxMin;
        }

        // A1: cero solapamiento entre operaciones consecutivas.
        for (int k = 1; k < n; k++) {
            if (entries[k] < entries[k - 1] + dur[k - 1]) {
                return "A1 (overlap) at k=" + k + ": entries[" + k + "]=" + entries[k]
                    + " < entries[" + (k - 1) + "]+dur[" + (k - 1) + "]=" + (entries[k - 1] + dur[k - 1]);
            }
        }

        // A2 (fin): la ultima salida cae dentro de la ventana evaluada.
        int lastExit = entries[n - 1] + dur[n - 1];
        if (lastExit > idxMax) {
            return "A2 (window end): last exit=" + lastExit + " > idxMax=" + idxMax;
        }

        return null;
    }

    private static String dbgFmt(double v) {
        return String.format(java.util.Locale.US, "%.6f", v);
    }

    /** Bloque 1 del volcado: de donde sale K y con que costes. Una vez por periodo. */
    private void dumpCalibration(ResultsGroup rg, PeriodDef pd, StrategyContext ctx, int n,
                                 double sumAbsGross, double sumAbsUnit, double sumUnit,
                                 double commSwapTotal, int commApplied, int commNotApplied,
                                 double ratioK, double exactBars, int baseBars, int numExtra,
                                 int idxMin, int idxMax, int m, double exposureRatio) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== [").append(rg.getName()).append("] ").append(pd.label())
          .append(" | CALIBRATION ===\n");
        sb.append("  mode=").append(ctx.modeLabel)
          .append(" mm=").append(ctx.mmName)
          .append(" tickSize=").append(ctx.tickSize)
          .append(" spreadPoints=").append(ctx.spreadPoints)
          .append(" spreadPrice=").append(ctx.spreadPrice).append("\n");
        sb.append("  trades=").append(n)
          .append(" sumAbsGross=").append(dbgFmt(sumAbsGross))
          .append(" sumAbsUnit=").append(dbgFmt(sumAbsUnit))
          .append(" sumUnit=").append(dbgFmt(sumUnit)).append("\n");
        sb.append("  ratioK=").append(dbgFmt(ratioK)).append("   (sumAbsGross / sumAbsUnit)\n");
        sb.append("  commSwapTotal=").append(dbgFmt(commSwapTotal))
          .append("   CommSwapApplied: true=").append(commApplied)
          .append(" false=").append(commNotApplied).append("\n");
        sb.append("  exactBars=").append(dbgFmt(exactBars))
          .append(" baseBars=").append(baseBars)
          .append(" numExtra=").append(numExtra)
          .append(" exposureRatio=").append(dbgFmt(exposureRatio)).append("\n");
        sb.append("  window: idxMin=").append(idxMin).append(" idxMax=").append(idxMax)
          .append(" m=").append(m);
        logDebugDump(sb.toString());
    }

    /**
     * Bloque 2 del volcado: el reparto completo del primer mono. Se vuelcan todas las operaciones y
     * no una muestra, porque el objetivo es auditar el no-solapamiento a mano: cualquier valor
     * negativo en la columna gapToPrev es un solapamiento.
     */
    private void dumpMonkeyLayout(ResultsGroup rg, PeriodDef pd, int idxMin, int m,
                                  int[] entries, int[] dur, String violation) {
        int n = dur.length;
        int sumDur = 0;
        for (int d : dur) {
            sumDur += d;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== [").append(rg.getName()).append("] ").append(pd.label())
          .append(" | LAYOUT (monkey #0) ===\n");
        sb.append("  sumDur=").append(sumDur).append(" slack=").append(m - sumDur)
          .append(" firstEntry=").append(entries[0])
          .append(" lastExit=").append(entries[n - 1] + dur[n - 1]).append("\n");
        sb.append("  invariants: ")
          .append(violation == null ? "A1 PASS  A2 PASS  A3 PASS" : ("FAIL -> " + violation))
          .append("\n");
        sb.append("  k\tentry\tdur\texit\tgapToPrev\n");
        for (int k = 0; k < n; k++) {
            int exit = entries[k] + dur[k];
            int gap = (k == 0) ? (entries[0] - idxMin) : (entries[k] - (entries[k - 1] + dur[k - 1]));
            sb.append("  ").append(k).append('\t').append(entries[k]).append('\t')
              .append(dur[k]).append('\t').append(exit).append('\t').append(gap)
              .append('\n');
        }
        logDebugDump(sb.toString());
    }

    /**
     * Escritor del volcado. Es static synchronized porque Per Strategy Analysis corre multihilo y
     * sin sincronizar las lineas se entrelazarian. Un error de escritura no tumba el analisis, pero
     * el primero SI se reporta: ignorarlos en silencio es lo que hizo que una ruta obsoleta pasara
     * desapercibida en CVSintetica.
     */
    private static synchronized void logDebugDump(String msg) {
        java.io.PrintWriter pw = null;
        try {
            java.io.File dir = new java.io.File(DEBUG_LOG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            pw = new java.io.PrintWriter(new java.io.FileWriter(new java.io.File(dir, DEBUG_LOG_NAME), true));
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date());
            pw.println("[" + ts + "] [" + Thread.currentThread().getName() + "] " + msg);
        } catch (Exception e) {
            if (!debugWriteErrorReported) {
                debugWriteErrorReported = true;
                Log.warn("MonkeyTest v2: could not write the debug dump to " + DEBUG_LOG_DIR + "/"
                    + DEBUG_LOG_NAME + ": " + e.getMessage()
                    + ". Further write errors are not reported.");
            }
        } finally {
            if (pw != null) {
                pw.close();
            }
        }
    }

    /** Net Profit que reporta SQX para el periodo. Solo se usa para diagnostico en el log. */
    private double readNetProfit(ResultsGroup rg, PeriodDef pd) {
        try {
            SQStats st = rg.mainResult().statsOrNull(Directions.Both, PlTypes.Money, pd.sampleType);
            if (st != null) {
                return st.getDouble(StatsKey.NET_PROFIT);
            }
        } catch (Exception ignored) {
        }
        return Double.NaN;
    }

    // =========================================================
    // Cache del ResultsPlugin
    // =========================================================

    private void writeCacheArtifacts(ResultsGroup rg, PeriodDef pd, PeriodResult res, int numMonkeys, double percentile) {
        java.io.PrintWriter csvWriter = null;
        java.io.PrintWriter metaWriter = null;
        try {
            java.io.File cacheDir = new java.io.File("user/extend/ResultsPlugins/DatabankMonkeyTest/cache");
            cacheDir.mkdirs();

            // Ranking de monos por profit sin perder la correspondencia indice <-> curva
            final double[] profitsForSort = new double[numMonkeys];
            for (int i = 0; i < numMonkeys; i++) {
                profitsForSort[i] = res.curves[i][res.numTrades] - res.curves[i][0];
            }
            Integer[] order = new Integer[numMonkeys];
            for (int i = 0; i < numMonkeys; i++) order[i] = i;
            Arrays.sort(order, new java.util.Comparator<Integer>() {
                public int compare(Integer a, Integer b) {
                    return Double.compare(profitsForSort[a], profitsForSort[b]);
                }
            });

            // Hasta 50 curvas representativas: minimo, maximo y pasos uniformes de percentil
            int numCurves = Math.min(50, numMonkeys);
            java.util.TreeSet<Integer> positions = new java.util.TreeSet<>();
            positions.add(0);
            positions.add(numMonkeys - 1);
            for (int k = 1; k <= numCurves - 2; k++) {
                positions.add((int) Math.round(k * (numMonkeys - 1) / (double) (numCurves - 1)));
            }

            String csvPath = cacheDir.getPath() + "/" + rg.getName() + "_monkey_simulation_data.csv";
            csvWriter = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(csvPath), java.nio.charset.StandardCharsets.UTF_8));

            StringBuilder header = new StringBuilder("monkey_id");
            for (int b = 0; b <= res.numTrades; b++) header.append(";b").append(b);
            csvWriter.println(header.toString());

            int qLabel = 1;
            for (int pos : positions) {
                String label;
                if (pos == 0) label = "min";
                else if (pos == numMonkeys - 1) label = "max";
                else label = String.format("q%02d", qLabel++);

                double[] curve = res.curves[order[pos]];
                StringBuilder row = new StringBuilder(label);
                for (int b = 0; b <= res.numTrades; b++) {
                    row.append(';').append(String.format(java.util.Locale.US, "%.2f", curve[b]));
                }
                csvWriter.println(row.toString());
            }

            String metaPath = cacheDir.getPath() + "/" + rg.getName() + "_monkey_simulation_data.meta.json";
            metaWriter = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(metaPath), java.nio.charset.StandardCharsets.UTF_8));
            String escapedName = rg.getName().replace("\\", "\\\\").replace("\"", "\\\"");

            StringBuilder profitsArr = new StringBuilder("[");
            for (int i = 0; i < res.sortedProfits.length; i++) {
                if (i > 0) profitsArr.append(",");
                profitsArr.append(String.format(java.util.Locale.US, "%.2f", res.sortedProfits[i]));
            }
            profitsArr.append("]");

            metaWriter.println("{");
            metaWriter.println("  \"schemaVersion\": 4,");
            metaWriter.println("  \"strategyName\": \"" + escapedName + "\",");
            metaWriter.println("  \"period\": \"" + pd.label() + "\",");
            metaWriter.println("  \"tradeFromMs\": " + res.tMin + ",");
            metaWriter.println("  \"tradeToMs\": " + res.tMax + ",");
            metaWriter.println("  \"numTrades\": " + res.numTrades + ",");
            metaWriter.println("  \"numMonkeys\": " + numMonkeys + ",");
            metaWriter.println("  \"percentile\": " + String.format(java.util.Locale.US, "%.1f", percentile) + ",");
            metaWriter.println("  \"mode\": \"" + res.modeLabel + "\",");
            metaWriter.println("  \"ratioPerUnit\": " + String.format(java.util.Locale.US, "%.6f", res.ratioK) + ",");
            metaWriter.println("  \"spreadPoints\": " + String.format(java.util.Locale.US, "%.4f", res.spreadPoints) + ",");
            metaWriter.println("  \"exposureRatio\": " + String.format(java.util.Locale.US, "%.4f", res.exposureRatio) + ",");
            metaWriter.println("  \"initialBalance\": " + String.format(java.util.Locale.US, "%.2f", res.initialBalance) + ",");
            metaWriter.println("  \"realProfit\": " + String.format(java.util.Locale.US, "%.2f", res.realProfit) + ",");
            metaWriter.println("  \"monkeyThreshold\": " + String.format(java.util.Locale.US, "%.2f", res.thresholdVal) + ",");
            metaWriter.println("  \"meanMonkey\": " + String.format(java.util.Locale.US, "%.2f", res.meanMonkey) + ",");
            metaWriter.println("  \"medianMonkey\": " + String.format(java.util.Locale.US, "%.2f", res.medianMonkey) + ",");
            metaWriter.println("  \"stdMonkey\": " + String.format(java.util.Locale.US, "%.2f", res.stdMonkey) + ",");
            metaWriter.println("  \"zScore\": " + String.format(java.util.Locale.US, "%.2f", res.zScore) + ",");
            metaWriter.println("  \"rankPercentile\": " + String.format(java.util.Locale.US, "%.2f", res.rankPercentile) + ",");
            metaWriter.println("  \"status\": \"" + res.status + "\",");
            metaWriter.println("  \"meanHoldingPeriod\": " + String.format(java.util.Locale.US, "%.1f", res.exactBars) + ",");
            metaWriter.println("  \"monkeyProfits\": " + profitsArr.toString() + ",");
            metaWriter.println("  \"generatedAtUtc\": " + System.currentTimeMillis() + ",");
            metaWriter.println("  \"source\": \"CustomAnalysis\"");
            metaWriter.println("}");
        } catch (Exception cacheEx) {
            Log.warn("MonkeyTest v2: could not write cache artifacts for " + rg.getName() + ": " + cacheEx.getMessage());
        } finally {
            if (csvWriter != null) {
                try { csvWriter.close(); } catch (Exception ex) {}
            }
            if (metaWriter != null) {
                try { metaWriter.close(); } catch (Exception ex) {}
            }
        }
    }

    // =========================================================
    // Datos historicos y utilidades
    // =========================================================

    private ArrayList<Candle> loadCandles(String symbolConnection, String timeframe, Result mainResult) {
        ArrayList<Candle> candles = new ArrayList<>();
        RandomAccessReaderFile reader = null;
        try {
            String path = "user/data/History/" + symbolConnection + "/" + symbolConnection + "_" + timeframe + ".dat";
            java.io.File file = new java.io.File(path);
            String altTimeframe = timeframe.equalsIgnoreCase("M1") ? "1M" : (timeframe.equalsIgnoreCase("1M") ? "M1" : null);

            if (!file.exists() && altTimeframe != null) {
                java.io.File altFile = new java.io.File("user/data/History/" + symbolConnection + "/" + symbolConnection + "_" + altTimeframe + ".dat");
                if (altFile.exists()) {
                    file = altFile;
                }
            }

            if (!file.exists()) {
                java.io.File historyDir = new java.io.File("user/data/History");
                if (historyDir.exists() && historyDir.isDirectory()) {
                    for (java.io.File sub : historyDir.listFiles()) {
                        if (sub.isDirectory() && sub.getName().equalsIgnoreCase(symbolConnection)) {
                            java.io.File[] datFiles = sub.listFiles();
                            if (datFiles != null) {
                                for (java.io.File f : datFiles) {
                                    if (f.getName().equalsIgnoreCase(symbolConnection + "_" + timeframe + ".dat") ||
                                        (altTimeframe != null && f.getName().equalsIgnoreCase(symbolConnection + "_" + altTimeframe + ".dat"))) {
                                        file = f;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!file.exists()) {
                Log.error("MonkeyTest v2: BDF history file not found for " + symbolConnection + " " + timeframe);
                return null;
            }

            long dataStartPos = findDataStartPosition(file.getAbsolutePath());

            reader = new RandomAccessReaderFile(file.getAbsolutePath());
            reader.openFile();

            OhlcDataReader ohlcReader = new OhlcDataReader(true);
            ohlcReader.setDataStartPosition(dataStartPos);
            ohlcReader.overrideDecimals(6); // Escala estandar dentro de los binarios BDF de SQX
            ohlcReader.seek(reader, 0);

            VersatileData vd = new VersatileData();

            while (reader.dataRemaining()) {
                ohlcReader.readData(reader, vd);

                Candle c = new Candle();
                c.time = vd.time;
                c.open = vd.open;
                c.high = vd.high;
                c.low = vd.low;
                c.close = vd.close;
                c.volume = vd.volume;

                candles.add(c);
            }

            Log.info(String.format("MonkeyTest v2: loaded %d candles for %s %s", candles.size(), symbolConnection, timeframe));

        } catch (Exception e) {
            Log.error("MonkeyTest v2: error loading BDF candles", e);
        } finally {
            if (reader != null) {
                try { reader.closeFile(); } catch (Exception ex) {}
            }
        }
        return candles;
    }

    private long findDataStartPosition(String filePath) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(filePath, "r");
            byte[] header = new byte[300];
            int bytesRead = raf.read(header);
            for (int i = 0; i < bytesRead - 6; i++) {
                if (header[i] == 'S' && header[i+1] == 'n' && header[i+2] == 'R' &&
                    header[i+3] == 'b' && header[i+4] == 'T' && header[i+5] == 's') {
                    return i + 6;
                }
            }
        } catch (Exception e) {
            Log.error("MonkeyTest v2: error finding SnRbTs position in BDF file", e);
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Exception e) {}
            }
        }
        return 94; // Offset estandar de reserva
    }

    private int findBarIndex(ArrayList<Candle> candles, long time) {
        int low = 0;
        int high = candles.size() - 1;
        int bestIdx = -1;
        while (low <= high) {
            int mid = (low + high) / 2;
            long barTime = candles.get(mid).time;
            if (barTime == time) return mid;
            if (barTime < time) {
                bestIdx = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return bestIdx;
    }

    private long inferTimeframeMs(ArrayList<Candle> candles) {
        int count = Math.min(candles.size(), 500);
        if (count < 2) return 60000;
        long[] diffs = new long[count - 1];
        int actualDiffs = 0;
        for (int i = 1; i < count; i++) {
            long d = candles.get(i).time - candles.get(i - 1).time;
            if (d > 0) {
                diffs[actualDiffs++] = d;
            }
        }
        if (actualDiffs == 0) return 60000;
        Arrays.sort(diffs, 0, actualDiffs);
        return diffs[actualDiffs / 2];
    }
}
