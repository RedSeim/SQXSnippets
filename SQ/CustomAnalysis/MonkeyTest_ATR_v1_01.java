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
 * Monkey Test ATR v1.01 -- Monte Carlo permutation test del EDGE GEOMETRICO de una estrategia.
 *
 * A diferencia de MonkeyTest_v2_00, que mide el edge en dinero calibrando un ratio K, esta version
 * NO convierte nunca a dinero: mide el desplazamiento de precio de cada operacion NORMALIZADO POR EL
 * ATR vigente en su entrada, y compara la suma de la estrategia contra la de N monos.
 *
 * Eso corrige un sesgo estructural de la medicion en pips absolutos: los trades ejecutados donde el
 * activo cotizaba mas alto producen mas pips por el mismo movimiento relativo, asi que pesan mas en
 * la suma. En un activo con deriva de precio, el veredicto acaba midiendo "edge en periodos de
 * precio elevado" en vez de "edge" a secas.
 *
 * Al no tocar cuantias monetarias desaparece la necesidad de distinguir MODO A y MODO B: el money
 * management solo hacia falta para convertir desplazamiento en euros.
 *
 * Las decisiones de diseno y su justificacion estan en MonkeyTest_ATR_v1_01_ENG.md / _SPA.md.
 */
public class MonkeyTest_ATR_v1_01 extends CustomAnalysisMethod {
    public static final Logger Log = LoggerFactory.getLogger(MonkeyTest_ATR_v1_01.class);

    private static final int MAX_PARTS = 10;
    private static final int MIN_TRADES = 20;

    // Volcado verboso de la palabra clave Debug. Va a un fichero propio y no al log de SQX, que
    // llega a 800 MB/dia y quedaria inservible. La ruta es RELATIVA a la raiz de instalacion de
    // SQX (el working directory de la JVM), igual que el cacheDir del ResultsPlugin, de modo que
    // sigue siendo valida tras reinstalar o mover SQX. Mismo patron que CVSintetica_V08.logDebug.
    private static final String DEBUG_LOG_DIR = "user/extend/Snippets/SQ/CustomAnalysis";
    private static final String DEBUG_LOG_NAME = "MonkeyTest_ATR_v1_01_debug.log";
    private static boolean debugWriteErrorReported = false;

    /** Claves publicadas por periodo. Se limpian antes de recalcular cada periodo en scope. */
    private static final String[] PERIOD_KEYS = {
        "MonkeyATRResult", "MonkeyATRPercentile", "MonkeyATRZScore", "MonkeyATRMedianNormPips",
        "MonkeyATRNormPipsProfit", "MonkeyATREdgePerTrade", "MonkeyATRSpread",
        "MonkeyATRExposureRatio", "MonkeyATRPeriod"
    };

    /** Periodo de ATR por defecto: el estandar de Wilder, y el que usa el indicador ATR de SQX. */
    private static final int DEFAULT_ATR_PERIOD = 14;

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
    }

    /**
     * Plan de duraciones de UN grupo direccional (las operaciones en largo, o las que van en corto).
     * Cada grupo tiene su propia bolsa de duraciones para que los monos repliquen no solo la
     * exposicion total, sino cuanta de esa exposicion correspondia a cada direccion. Ver la
     * seccion 5.16 de la documentacion.
     *
     * NOTA DE TERMINOLOGIA: en este fichero "largo"/"corto" (long/short) se reservan SIEMPRE para
     * la DIRECCION de la operacion. Para hablar de cuanto dura una operacion se dice "duracion",
     * nunca "larga" o "corta", que resultaria ambiguo.
     */
    private static class DirPlan {
        /** Cuantas operaciones reales tiene el grupo. */
        int count = 0;
        /** Duracion media exacta del grupo, en barras fraccionarias. */
        double exactBars = 0.0;
        /** Duracion entera que recibe cada operacion del grupo. */
        int baseBars = 0;
        /** Cuantas operaciones del grupo reciben una barra extra, para cuadrar la media. */
        int numExtra = 0;
        /** Barras totales que el grupo ocupara en cada mono: count*baseBars + numExtra. */
        int totalBars = 0;
        /** true si baseBars se saturo a 1 porque la media del grupo caia por debajo de una barra. */
        boolean clamped = false;

        /** Exposicion planificada frente a la real del grupo. 1.0 = replicada con exactitud. */
        double exposureRatio(double realBars) {
            return (realBars > 0) ? (totalBars / realBars) : 1.0;
        }
    }

    /** Resultado del test para un periodo concreto, incluido lo necesario para escribir la cache. */
    private static class PeriodResult {
        String status = "ERROR";
        String percentileText = null;
        String zScoreText = null;

        /** true solo si la simulacion se completo y hay estadisticas publicables. */
        boolean hasFullStats = false;

        double[] sortedEdges;
        int numTrades;

        /** Suma de los desplazamientos normalizados por ATR. Es la magnitud del veredicto. */
        double edgeReal;
        /** edgeReal / numTrades. Solo para mostrar; nunca se compara con esto. */
        double edgePerTrade;

        double thresholdVal;
        double meanMonkey;
        double stdMonkey;
        double medianMonkey;
        double zScore;
        double rankPercentile;
        double exactBars;
        double exposureRatio = 1.0;
        double spreadPoints;
        int atrPeriod;
        long tMin;
        long tMax;

        // Diagnostico del ATR, solo para el log y el volcado Debug.
        double atrMin;
        double atrMedian;
        double atrMax;
        int atrFloorHits;
        double atrEdgeCorrelation;
    }

    public MonkeyTest_ATR_v1_01() {
        super("MonkeyTest_ATR_v1_01", TYPE_FILTER_STRATEGY);
    }

    @Override
    public boolean filterStrategy(String projectName, String task, String databankName, ResultsGroup rg) throws Exception {
        // Input Args: "numMonkeys,percentile,period" mas palabras clave no posicionales:
        // AutoDiscard, Precision=M1 (o M1), SegmentDuration=N, ATRPeriod=N, Debug.
        // No existe ResultsPluginCache: el plugin que la consumia esta construido sobre conceptos
        // monetarios (balance inicial, curvas de equity) y mostraria multiplos de ATR etiquetados
        // como euros.
        int numMonkeys = 500;
        double percentile = 95.0;
        PeriodDef requested = new PeriodDef(SampleTypes.FullSample, "_Full");
        boolean autoDiscard = false;
        int atrPeriod = DEFAULT_ATR_PERIOD;
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
                atrPeriod = parseAtrPeriod(inputArgs);
            }
        } catch (Exception e) {
            Log.warn("MonkeyTest ATR v1: could not read input args, using defaults (500 monkeys, 95%, FULL). Reason: " + e.getMessage());
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
            // velas de 1 minuto, pero el ATR se calcula SIEMPRE sobre el timeframe principal: un
            // ATR(14) de barras de un minuto mide la volatilidad de los ultimos 14 minutos, que no
            // tiene nada que ver con la escala a la que opera una estrategia H4.
            ArrayList<Candle> candles = loadCandles(ctx.symbol, ctx.timeframe, mainResult);
            ArrayList<Candle> simCandles = candles;
            ArrayList<Candle> atrCandles = candles;

            if (useM1Precision) {
                ArrayList<Candle> m1Candles = loadCandles(ctx.symbol, "M1", mainResult);
                if (m1Candles != null && !m1Candles.isEmpty()) {
                    simCandles = m1Candles;
                    Log.info("MonkeyTest ATR v1: loaded " + m1Candles.size() + " M1 candles for 1-minute precision on " + ctx.symbol);
                } else {
                    Log.warn("MonkeyTest ATR v1: requested Precision=M1 but no M1 data found for " + ctx.symbol
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
                    Log.warn("MonkeyTest ATR v1: no BDF candles loaded for " + rg.getName() + " on " + ctx.symbol + " " + ctx.timeframe);
                } else {
                    OrdersList orders = resolveOrders(rg, mainResultKey, pd);
                    res = runMonkeyTestForPeriod(rg, ctx, pd, orders, simCandles, atrCandles,
                        atrPeriod, numMonkeys, percentile, rng, debugDump);
                }

                publishPeriodResult(rg, pd, res);
                resultsBySuffix.put(pd.suffix, res);
            }

            if (segmentDurationDays > 0) {
                runSegmentedSubTests(rg, ctx, mainResultKey, periods, simCandles, atrCandles,
                    atrPeriod, numMonkeys, percentile, segmentDurationDays, rng);
            }
        } catch (Exception e) {
            Log.error("MonkeyTest ATR v1: error computing Monkey Test for strategy " + rg.getName(), e);
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
                Log.warn("MonkeyTest ATR v1: input arg '" + a + "' is a v1 replicationMode and no longer applies."
                    + " v2 always uses the average-bars replication. The argument is ignored.");
            } else if (a.equalsIgnoreCase("ResultsPluginCache")) {
                Log.warn("MonkeyTest ATR v1: input arg 'ResultsPluginCache' does not apply to the ATR"
                    + " test. Its consumer plugin is built on monetary concepts and this test never"
                    + " produces money. The argument is ignored.");
            } else if (a.equalsIgnoreCase("Constant") || a.equalsIgnoreCase("Random")) {
                Log.warn("MonkeyTest ATR v1: input arg '" + a + "' is a v1 shiftingMode and no longer applies."
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
                    Log.info("MonkeyTest ATR v1: SegmentDuration requested = " + v + " days.");
                    return v;
                }
            } catch (Exception e) {
                Log.warn("MonkeyTest ATR v1: could not parse SegmentDuration value from '" + a + "': " + e.getMessage());
            }
        }
        return 0.0;
    }

    /**
     * ATRPeriod=N (por defecto 14). No es un parametro que haya que tocar en el uso normal: existe
     * para poder comprobar que el ranking no depende criticamente del periodo elegido, dado que un
     * SL basado en ATR anadido mas adelante usara uno distinto y desconocido de antemano.
     */
    private int parseAtrPeriod(String inputArgs) {
        if (!inputArgs.toUpperCase().contains("ATRPERIOD")) {
            return DEFAULT_ATR_PERIOD;
        }
        for (String a : inputArgs.split(",")) {
            String trimmed = a.trim();
            if (!trimmed.toUpperCase().contains("ATRPERIOD")) {
                continue;
            }
            String valStr;
            if (trimmed.indexOf("=") >= 0) {
                valStr = trimmed.substring(trimmed.indexOf("=") + 1).trim();
            } else if (trimmed.indexOf(":") >= 0) {
                valStr = trimmed.substring(trimmed.indexOf(":") + 1).trim();
            } else {
                valStr = trimmed.replaceAll("(?i)ATRPERIOD", "").trim();
            }
            try {
                int v = Integer.parseInt(valStr);
                if (v >= 2) {
                    Log.info("MonkeyTest ATR v1: ATRPeriod requested = " + v + ".");
                    return v;
                }
                Log.warn("MonkeyTest ATR v1: ATRPeriod must be >= 2, got '" + valStr + "'. Using "
                    + DEFAULT_ATR_PERIOD + ".");
            } catch (Exception e) {
                Log.warn("MonkeyTest ATR v1: could not parse ATRPeriod value from '" + a + "': "
                    + e.getMessage() + ". Using " + DEFAULT_ATR_PERIOD + ".");
            }
        }
        return DEFAULT_ATR_PERIOD;
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
            Log.warn("MonkeyTest ATR v1: could not read lastSettings XML for " + rg.getName() + ": " + e.getMessage());
        }

        ctx.tickSize = resolveTickSize(rg, ctx.symbol);
        resolveSpread(rg, ctx, lastSettingsXml, mainResult);

        // El money management no se consulta: sin conversion a dinero es irrelevante.
        Log.info("MonkeyTest ATR v1 [" + rg.getName() + "]: symbol=" + ctx.symbol + " tf=" + ctx.timeframe
            + " tickSize=" + ctx.tickSize + " spread=" + ctx.spreadPoints + " points (" + ctx.spreadSource + ")"
            + " -> " + ctx.spreadPrice + " in price");

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
            Log.warn("MonkeyTest ATR v1: could not resolve InstrumentInfo for " + symbol + ": " + e.getMessage());
        }
        Log.warn("MonkeyTest ATR v1: no tickSize available for " + symbol + ", spread cannot be converted to price.");
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
                    Log.warn("MonkeyTest ATR v1 [" + rg.getName() + "]: spread not found in XML, falling back to"
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
        Log.warn("MonkeyTest ATR v1 [" + rg.getName() + "]: could not resolve spread for " + ctx.symbol
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
                    Log.warn("MonkeyTest ATR v1: no Chart matched symbol " + targetSymbol + ", using first Setup spread.");
                    return Double.parseDouble(v.trim());
                }
            }
        } catch (Exception e) {
            Log.warn("MonkeyTest ATR v1: could not extract spread from XML: " + e.getMessage());
        }
        return null;
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

        Log.warn("MonkeyTest ATR v1: unrecognized period argument '" + periodArg + "'. Valid values: FULL, IS, OOS, ISV,"
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
            Log.warn("MonkeyTest ATR v1: period part number out of range 1.." + MAX_PARTS + ": '" + periodArg + "'.");
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
            Log.warn("MonkeyTest ATR v1: error filtering orders for " + pd.label() + ": " + e.getMessage());
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
                        Log.info("MonkeyTest ATR v1: strategy [" + rg.getName() + "] has a single OOS segment, so OOS1 is the same period as OOS.");
                        return aggregated;
                    }
                } catch (Exception e) {
                    Log.warn("MonkeyTest ATR v1: error filtering aggregated OOS orders: " + e.getMessage());
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
        rg.specialValues().setString("MonkeyATRResult" + suffix, res.status);
        rg.specialValues().setString("MonkeyATRPercentile" + suffix,
            res.percentileText != null ? res.percentileText : "N/A");
        rg.specialValues().setString("MonkeyATRZScore" + suffix,
            res.zScoreText != null ? res.zScoreText : "N/A");

        // Los valores numericos solo se publican si el test se completo, para que un LOW TRADES
        // muestre N/A en el Databank y nunca un enganoso 0.00.
        if (res.hasFullStats) {
            rg.specialValues().set("MonkeyATRMedianNormPips" + suffix, res.medianMonkey);
            rg.specialValues().set("MonkeyATRNormPipsProfit" + suffix, res.edgeReal);
            rg.specialValues().set("MonkeyATREdgePerTrade" + suffix, res.edgePerTrade);
            rg.specialValues().set("MonkeyATRExposureRatio" + suffix, res.exposureRatio);
            rg.specialValues().set("MonkeyATRSpread" + suffix, res.spreadPoints);
            rg.specialValues().set("MonkeyATRPeriod" + suffix, (double) res.atrPeriod);
        } else {
            rg.specialValues().set("MonkeyATRMedianNormPips" + suffix, null);
            rg.specialValues().set("MonkeyATRNormPipsProfit" + suffix, null);
            rg.specialValues().set("MonkeyATREdgePerTrade" + suffix, null);
            rg.specialValues().set("MonkeyATRExposureRatio" + suffix, null);
            rg.specialValues().set("MonkeyATRSpread" + suffix, null);
            rg.specialValues().set("MonkeyATRPeriod" + suffix, null);
        }
    }

    private void runSegmentedSubTests(ResultsGroup rg, StrategyContext ctx, String mainResultKey,
                                      ArrayList<PeriodDef> periods, ArrayList<Candle> simCandles,
                                      ArrayList<Candle> atrCandles, int atrPeriod,
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
                    // El ATR se calcula siempre sobre el array completo del timeframe principal,
                    // no sobre el recorte del sub-segmento: asi el calentamiento queda absorbido por
                    // el historico previo tambien aqui.
                    PeriodResult subRes = runMonkeyTestForPeriod(rg, ctx, pd, subOrders, subCandles,
                        atrCandles, atrPeriod, numMonkeys, percentile, rng, false);

                    writeKeys(rg, "_Seg_" + label + "_" + j, subRes);
                }
            }
        } catch (Exception e) {
            Log.error("MonkeyTest ATR v1: error executing segmented Monkey Test for " + rg.getName(), e);
        }
    }

    // =========================================================
    // Calculo del test para un periodo
    // =========================================================

    /**
     * Nucleo del test. No hay calibracion monetaria: la magnitud es el desplazamiento de precio de
     * cada operacion dividido por el ATR vigente en su entrada. Se compara la SUMA de la estrategia
     * contra la suma de cada uno de los N monos.
     */
    private PeriodResult runMonkeyTestForPeriod(ResultsGroup rg, StrategyContext ctx, PeriodDef pd,
                                                OrdersList orders, ArrayList<Candle> simCandles,
                                                ArrayList<Candle> atrCandles, int atrPeriod,
                                                int numMonkeys, double percentile, Random rng,
                                                boolean debugDump) {
        PeriodResult res = new PeriodResult();
        res.spreadPoints = ctx.spreadPoints;
        res.atrPeriod = atrPeriod;

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
                Log.warn("MonkeyTest ATR v1: strategy [" + rg.getName() + "] has no trades in period " + pd.label()
                    + " -- that segment does not exist on this strategy. -> FAILED (INVALID PERIOD)");
                return res;
            }

            if (n < MIN_TRADES) {
                if (n == 0 && pd.sampleType != SampleTypes.FullSample) {
                    Log.warn("MonkeyTest ATR v1: strategy [" + rg.getName() + "] has no trades in the " + pd.label()
                        + " period. Verify that the last backtest has that sample period configured. -> LOW TRADES.");
                } else {
                    Log.warn("MonkeyTest ATR v1: strategy [" + rg.getName() + "] has too few trades in the " + pd.label()
                        + " period (" + n + " trades, minimum " + MIN_TRADES + "). -> LOW TRADES.");
                }
                res.status = "LOW TRADES";
                return res;
            }

            // --- 2. ATR sobre el timeframe PRINCIPAL ------------------------------------------
            // Se calcula sobre el array completo, no sobre el recorte del periodo, para que el
            // calentamiento de atrPeriod barras quede absorbido por el historico previo.
            double[] atrArray = computeATR(atrCandles, atrPeriod);

            // Un tick es el menor movimiento de precio representable: por debajo de eso el ATR no
            // mide volatilidad, es un artefacto de datos rellenados o corruptos.
            double atrFloor = ctx.tickSize > 0 ? ctx.tickSize : 1e-10;

            // --- 3. Desplazamiento normalizado de las operaciones reales ----------------------
            // Los precios de las ordenes reales YA incorporan el spread (SQX llena en ask/bid),
            // asi que aqui no se resta nada; solo se resta a los monos, que leen velas crudas.
            int[] dirs = new int[n];
            double[] realAtr = new double[n];
            double[] realAtrDisp = new double[n];
            double edgeReal = 0.0;
            double sumAbsAtrDisp = 0.0;
            int realFloorHits = 0;

            for (int i = 0; i < n; i++) {
                Order o = tradeOrders.get(i);
                dirs[i] = o.isShort() ? -1 : 1;

                double atrHere = atrAtTime(atrCandles, atrArray, o.OpenTime, atrFloor);
                if (atrHere <= atrFloor) {
                    realFloorHits++;
                }
                realAtr[i] = atrHere;

                double atrDisp = ((o.ClosePrice - o.OpenPrice) * dirs[i]) / atrHere;
                realAtrDisp[i] = atrDisp;

                edgeReal += atrDisp;
                sumAbsAtrDisp += Math.abs(atrDisp);
            }

            double[] atrSorted = realAtr.clone();
            Arrays.sort(atrSorted);
            res.atrMin = atrSorted[0];
            res.atrMax = atrSorted[n - 1];
            res.atrMedian = medianOfSorted(atrSorted);
            res.atrFloorHits = realFloorHits;
            res.atrEdgeCorrelation = correlation(realAtr, realAtrDisp);

            // Si el ATR mediano esta en el suelo, el juego de datos entero es degenerado: mejor
            // fallar de forma ruidosa que publicar un numero sin sentido.
            if (res.atrMedian <= atrFloor) {
                res.status = "ERROR";
                Log.error("MonkeyTest ATR v1: strategy [" + rg.getName() + "] period " + pd.label()
                    + " has a median ATR at or below one tick (" + res.atrMedian
                    + "); the price data for this period looks degenerate.");
                return res;
            }

            if (sumAbsAtrDisp <= 1e-12) {
                res.status = "ERROR";
                Log.error("MonkeyTest ATR v1: strategy [" + rg.getName() + "] period " + pd.label()
                    + " has zero total normalized displacement; cannot evaluate the edge.");
                return res;
            }

            // --- 4. Ventana temporal del periodo ----------------------------------------------
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

            // ATR que le corresponde a cada barra de la ventana de simulacion. Con Precision=M1
            // esto mapea cada vela de un minuto a la ultima barra COMPLETADA del timeframe
            // principal; sin ella el mapeo es la identidad.
            int[] windowFloorHits = new int[1];
            double[] atrAtSim = buildAtrForWindow(simCandles, atrCandles, atrArray, idxMin, m,
                atrFloor, windowFloorHits);

            // --- 5. Duracion media y dithering, POR DIRECCION ---------------------------------
            // La duracion se promedia por separado para las operaciones en largo y para las que
            // van en corto. Promediarlas juntas borraria la asimetria direccional de la exposicion
            // y abriria un falso positivo: una estrategia que aguanta mas tiempo en la direccion en
            // que el mercado deriva capturaria esa deriva sin que los monos pudieran replicarla, y
            // el test lo leeria como edge de timing. Ver la seccion 5.16 de la documentacion.
            double sumBarsLong = 0.0;
            double sumBarsShort = 0.0;
            int nLong = 0;
            int nShort = 0;
            for (int i = 0; i < n; i++) {
                Order o = tradeOrders.get(i);
                double d = posOf(simCandles, o.CloseTime, tfMs) - posOf(simCandles, o.OpenTime, tfMs);
                // La operacion cuenta en su grupo aunque su duracion no sea positiva, igual que
                // antes contaba en el total: solo se descarta su aportacion a la suma.
                if (dirs[i] > 0) {
                    nLong++;
                    if (d > 0) sumBarsLong += d;
                } else {
                    nShort++;
                    if (d > 0) sumBarsShort += d;
                }
            }
            double sumBars = sumBarsLong + sumBarsShort;

            DirPlan planLong = planDirection(nLong, sumBarsLong, "long", rg, pd);
            DirPlan planShort = planDirection(nShort, sumBarsShort, "short", rg, pd);

            double exactBars = sumBars / n;
            res.exactBars = exactBars;

            int targetTotalBars = planLong.totalBars + planShort.totalBars;
            res.exposureRatio = (sumBars > 0) ? (targetTotalBars / sumBars) : 1.0;

            res.edgeReal = edgeReal;
            res.edgePerTrade = edgeReal / n;

            // El volcado va ANTES de la guarda para que un INSUFFICIENT SPACE quede diagnosticado.
            if (debugDump) {
                dumpAtrStats(rg, pd, ctx, n, edgeReal, sumAbsAtrDisp, atrPeriod, res,
                    windowFloorHits[0], exactBars, planLong, planShort, sumBarsLong, sumBarsShort,
                    idxMin, idxMax, m);
            }

            // Sin solapamiento en la estrategia original se cumple sum(dur) <= M, asi que esto es
            // teoricamente inalcanzable; la guarda existe para detectar datos corruptos.
            // El requisito real es sumDur <= m-1: con sumDur == m exacto la holgura seria cero y
            // la ultima salida caeria en idxMax+1, una barra fuera de la ventana evaluada.
            if (targetTotalBars >= m) {
                res.status = "INSUFFICIENT SPACE";
                Log.warn("MonkeyTest ATR v1: strategy [" + rg.getName() + "] period " + pd.label()
                    + " needs " + targetTotalBars + " bars for " + n + " trades but the period only spans " + m
                    + " bars. -> INSUFFICIENT SPACE");
                return res;
            }

            // --- 6. Simulacion de los monos ---------------------------------------------------
            double[] monkeyEdges = new double[numMonkeys];
            boolean invariantsWarned = false;

            for (int mk = 0; mk < numMonkeys; mk++) {
                // Cada grupo direccional sortea sus duraciones dentro de su propia bolsa, de modo
                // que la exposicion total de las operaciones en largo y la de las que van en corto
                // se replican por separado.
                int[] durLong = ditherDurations(planLong.count, planLong.baseBars, planLong.numExtra, rng);
                int[] durShort = ditherDurations(planShort.count, planShort.baseBars, planShort.numExtra, rng);

                // Los pares (direccion, duracion) se combinan y se barajan JUNTOS: el orden en el
                // tiempo pasa a ser completamente aleatorio y distinto en cada mono, pero cada
                // duracion sigue pegada a la direccion cuya bolsa la genero.
                int[] mkDirs = new int[n];
                int[] dur = new int[n];
                int w = 0;
                for (int i = 0; i < durLong.length; i++) {
                    mkDirs[w] = 1;
                    dur[w] = durLong[i];
                    w++;
                }
                for (int i = 0; i < durShort.length; i++) {
                    mkDirs[w] = -1;
                    dur[w] = durShort[i];
                    w++;
                }
                shufflePairs(mkDirs, dur, rng);

                int[] entries = layoutEntries(idxMin, m, dur, rng);

                // Las invariantes se comprueban SIEMPRE, no solo en modo debug: si solo corriesen
                // con Debug activo, una violacion en produccion pasaria desapercibida, que es justo
                // el escenario a detectar. Solo se reporta la primera de cada periodo.
                String violation = checkLayoutInvariants(entries, dur, mkDirs, idxMin, idxMax,
                    planLong, planShort);
                if (violation != null && !invariantsWarned) {
                    invariantsWarned = true;
                    Log.warn("MonkeyTest ATR v1: LAYOUT INVARIANT VIOLATED for [" + rg.getName() + "] "
                        + pd.label() + " monkey #" + mk + " -- " + violation
                        + ". This is a bug in the layout algorithm, never a market condition."
                        + " Further violations in this period are not reported.");
                }

                if (debugDump && mk == 0) {
                    dumpMonkeyLayout(rg, pd, idxMin, m, entries, dur, mkDirs, violation);
                }

                double acc = 0.0;

                for (int k = 0; k < n; k++) {
                    int entryIdx = entries[k];
                    int exitIdx = entryIdx + dur[k];
                    if (entryIdx < 0) entryIdx = 0;
                    if (entryIdx >= barsCount) entryIdx = barsCount - 1;
                    if (exitIdx >= barsCount) exitIdx = barsCount - 1;
                    if (exitIdx < entryIdx) exitIdx = entryIdx;

                    double entryPrice = simCandles.get(entryIdx).open;
                    double exitPrice = simCandles.get(exitIdx).open;

                    // El spread se resta en unidades de PRECIO y antes de normalizar, porque es un
                    // desplazamiento de precio: entrar en ask y salir en bid equivale a penalizar
                    // el desplazamiento en un spread completo.
                    double disp = (exitPrice - entryPrice) * mkDirs[k] - ctx.spreadPrice;

                    int wIdx = entryIdx - idxMin;
                    double atrHere = (wIdx >= 0 && wIdx < atrAtSim.length) ? atrAtSim[wIdx] : atrFloor;
                    acc += disp / atrHere;
                }

                monkeyEdges[mk] = acc;
            }

            // --- 7. Estadistica ---------------------------------------------------------------
            double sum = 0;
            for (double p : monkeyEdges) sum += p;
            double mean = sum / numMonkeys;

            double variance = 0;
            for (double p : monkeyEdges) variance += (p - mean) * (p - mean);
            double std = numMonkeys > 1 ? Math.sqrt(variance / (numMonkeys - 1)) : 0.0;

            double zScore = std > 0 ? (edgeReal - mean) / std : 0.0;

            double[] sortedEdges = monkeyEdges.clone();
            Arrays.sort(sortedEdges);
            int thresholdIndex = (int) Math.floor(numMonkeys * (percentile / 100.0));
            if (thresholdIndex < 0) thresholdIndex = 0;
            if (thresholdIndex >= numMonkeys) thresholdIndex = numMonkeys - 1;
            double thresholdVal = sortedEdges[thresholdIndex];

            int beaten = 0;
            for (double p : monkeyEdges) if (p < edgeReal) beaten++;
            double rankPercentile = (beaten / (double) numMonkeys) * 100.0;

            double medianMonkey = medianOfSorted(sortedEdges);

            res.status = (edgeReal > thresholdVal) ? "PASSED" : "FAILED";
            res.percentileText = String.format(java.util.Locale.US, "%.2f%%", rankPercentile);
            res.zScoreText = String.format(java.util.Locale.US, "%.2f", zScore);

            res.hasFullStats = true;
            res.sortedEdges = sortedEdges;
            res.numTrades = n;
            res.thresholdVal = thresholdVal;
            res.meanMonkey = mean;
            res.stdMonkey = std;
            res.medianMonkey = medianMonkey;
            res.zScore = zScore;
            res.rankPercentile = rankPercentile;
            res.tMin = tMin;
            res.tMax = tMax;

            Log.info("MonkeyTest ATR v1 [" + rg.getName() + "] " + pd.label()
                + ": atrPeriod=" + atrPeriod
                + " trades=" + n + " avgBars=" + String.format(java.util.Locale.US, "%.4f", exactBars)
                + " (long=" + planLong.count + "@" + planLong.baseBars + "+" + planLong.numExtra
                + " short=" + planShort.count + "@" + planShort.baseBars + "+" + planShort.numExtra + ")"
                + " exposureRatio=" + String.format(java.util.Locale.US, "%.4f", res.exposureRatio)
                + " edge=" + String.format(java.util.Locale.US, "%.4f", edgeReal)
                + " edge/trade=" + String.format(java.util.Locale.US, "%.4f", res.edgePerTrade)
                + " monkeyMedian=" + String.format(java.util.Locale.US, "%.4f", medianMonkey)
                + " atrFloorHits=" + realFloorHits
                + " -> " + res.status);

        } catch (Exception e) {
            res.status = "ERROR";
            res.hasFullStats = false;
            Log.error("MonkeyTest ATR v1: error computing Monkey Test for strategy " + rg.getName()
                + " period " + pd.label(), e);
        }

        return res;
    }

    // =========================================================
    // ATR
    // =========================================================

    /**
     * ATR calculado replicando EXACTAMENTE el algoritmo del indicador ATR interno de SQX
     * (SQ.Blocks.Indicators.ATR.OnBarUpdate), no una formula generica de manual. La unica
     * diferencia entre el arranque (warm-up) y el regimen estacionario es el denominador
     * D_i = min(i+1, period): crece con cada vela hasta tocar el periodo completo, momento en
     * el que la formula se convierte en el suavizado de Wilder de siempre con denominador fijo.
     * No existe una semilla separada por media simple, es la misma linea de codigo en todo
     * el rango. Devuelve un array del mismo tamano que las velas, con un valor valido en TODAS
     * las posiciones desde el indice 0 (igual que el indicador real, que tambien publica un
     * valor, mas ruidoso cuanto mas cerca del principio del historico, desde su primera barra).
     */
    private double[] computeATR(ArrayList<Candle> candles, int period) {
        int n = candles.size();
        double[] atr = new double[n];
        if (period < 1 || n == 0) {
            return atr;
        }

        Candle c0 = candles.get(0);
        atr[0] = c0.high - c0.low;

        for (int i = 1; i < n; i++) {
            Candle c = candles.get(i);
            double prevClose = candles.get(i - 1).close;
            double range = c.high - c.low;
            double trueRange = Math.max(range,
                Math.max(Math.abs(c.high - prevClose), Math.abs(c.low - prevClose)));

            int denom = Math.min(i + 1, period);
            atr[i] = (atr[i - 1] * (denom - 1) + trueRange) / denom;
        }
        return atr;
    }

    /**
     * ATR vigente en un instante dado. Es CAUSAL: devuelve el ATR de la ultima barra COMPLETADA,
     * nunca el de la barra que contiene el instante, porque su maximo y su minimo no se conocen
     * todavia en su apertura. Cualquier asimetria aqui entre la estrategia y los monos invalidaria
     * la comparacion.
     */
    private double atrAtTime(ArrayList<Candle> atrCandles, double[] atrArray, long time, double atrFloor) {
        int aIdx = findBarIndex(atrCandles, time);
        double v = (aIdx > 0 && (aIdx - 1) < atrArray.length) ? atrArray[aIdx - 1] : 0.0;
        return v < atrFloor ? atrFloor : v;
    }

    /**
     * Precalcula, para cada barra de la ventana de simulacion, el ATR del timeframe principal que
     * le corresponde. Con Precision=M1 esto mapea cada vela de un minuto a la ultima barra
     * completada del grafico principal; sin ella el mapeo es la identidad y se resuelve sin
     * busqueda binaria. Se limita a la ventana [idxMin, idxMax] -- que es donde los monos pueden
     * entrar -- para no reservar un array del tamano del historico completo.
     */
    private double[] buildAtrForWindow(ArrayList<Candle> simCandles, ArrayList<Candle> atrCandles,
                                       double[] atrArray, int idxMin, int m, double atrFloor,
                                       int[] floorHitsOut) {
        double[] out = new double[m];
        boolean sameArray = (simCandles == atrCandles);
        int hits = 0;
        int simSize = simCandles.size();

        for (int j = 0; j < m; j++) {
            int simIdx = idxMin + j;
            if (simIdx < 0 || simIdx >= simSize) {
                out[j] = atrFloor;
                hits++;
                continue;
            }

            int aIdx = sameArray ? simIdx : findBarIndex(atrCandles, simCandles.get(simIdx).time);
            double v = (aIdx > 0 && (aIdx - 1) < atrArray.length) ? atrArray[aIdx - 1] : 0.0;
            if (v < atrFloor) {
                v = atrFloor;
                hits++;
            }
            out[j] = v;
        }

        floorHitsOut[0] = hits;
        return out;
    }

    private double medianOfSorted(double[] sorted) {
        int len = sorted.length;
        if (len == 0) {
            return 0.0;
        }
        if (len % 2 == 1) {
            return sorted[len / 2];
        }
        return (sorted[len / 2 - 1] + sorted[len / 2]) / 2.0;
    }

    /**
     * Correlacion de Pearson entre el ATR de entrada y el desplazamiento normalizado, sobre las
     * operaciones reales. Es diagnostico: un valor alto en valor absoluto avisa de que el edge se
     * concentra en un regimen de volatilidad concreto, que es el caso en que extrapolar a una fase
     * posterior con SL por ATR es menos fiable.
     */
    private double correlation(double[] x, double[] y) {
        int n = x.length;
        if (n < 2 || y.length != n) {
            return 0.0;
        }
        double mx = 0.0, my = 0.0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;

        double sxy = 0.0, sxx = 0.0, syy = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        double den = Math.sqrt(sxx * syy);
        return den > 0 ? (sxy / den) : 0.0;
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
     * Calcula el plan de duraciones de UN grupo direccional: cuantas barras recibe cada operacion
     * del grupo para que su duracion media reproduzca la de las operaciones reales de esa misma
     * direccion. Un grupo vacio (estrategia que solo opera en un sentido) devuelve un plan a cero,
     * que mas adelante genera un array de duraciones vacio sin necesidad de casos especiales.
     */
    private DirPlan planDirection(int count, double sumBarsDir, String dirLabel,
                                  ResultsGroup rg, PeriodDef pd) {
        DirPlan p = new DirPlan();
        p.count = count;
        if (count == 0) {
            return p;
        }

        p.exactBars = sumBarsDir / count;
        p.baseBars = (int) Math.floor(p.exactBars);
        if (p.baseBars < 1) {
            // Saturar a una barra sobreexpone este grupo. Al planificar por direccion el aviso
            // tiene que nombrar CUAL, porque puede afectar a un sentido y al otro no -- algo que
            // la media global de antes disimulaba.
            Log.warn("MonkeyTest ATR v1: strategy [" + rg.getName() + "] period " + pd.label()
                + " has an average " + dirLabel + " trade duration below one bar ("
                + String.format(java.util.Locale.US, "%.3f", p.exactBars)
                + "), so its exposure will be inflated. Consider Precision=M1 for a meaningful simulation.");
            p.baseBars = 1;
            p.clamped = true;
        }

        p.numExtra = (int) Math.round(count * (p.exactBars - Math.floor(p.exactBars)));
        if (p.numExtra < 0) p.numExtra = 0;
        if (p.numExtra > count) p.numExtra = count;

        p.totalBars = count * p.baseBars + p.numExtra;
        return p;
    }

    /**
     * Baraja en bloque los pares (direccion, duracion) con Fisher-Yates completo: cualquiera de las
     * n! secuencias es igual de probable, asi que el orden en el tiempo no arrastra nada del orden
     * original de la estrategia. Intercambiar los DOS arrays a la vez es lo que mantiene cada
     * duracion pegada a la direccion cuya bolsa la genero, de modo que la exposicion total de cada
     * direccion se conserva por mucho que el orden cambie. Una permutacion no altera un
     * multiconjunto, asi que el numero de operaciones de cada sentido tampoco varia.
     */
    private void shufflePairs(int[] dirsOut, int[] durOut, Random rng) {
        for (int i = dirsOut.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);

            int tmpDir = dirsOut[i];
            dirsOut[i] = dirsOut[j];
            dirsOut[j] = tmpDir;

            int tmpDur = durOut[i];
            durOut[i] = durOut[j];
            durOut[j] = tmpDur;
        }
    }

    /**
     * Reparte las numExtra barras extra entre operaciones elegidas al azar, sin repeticion
     * (Fisher-Yates parcial). El numero de operaciones que reciben la barra extra es siempre
     * exactamente numExtra, asi que la exposicion total es identica en todos los monos: solo se
     * aleatoriza CUALES la reciben, no CUANTAS. Una probabilidad independiente por operacion parece
     * mas aleatoria pero haria variar la exposicion entre monos segun una binomial, destruyendo
     * justo la propiedad que los hace comparables.
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
     * Comprueba las cuatro invariantes del layout de un mono. Devuelve null si todo esta bien, o el
     * mensaje describiendo la primera violacion encontrada. Coste O(n), despreciable frente a la
     * simulacion, asi que corre en todos los monos y no solo en modo debug.
     */
    private String checkLayoutInvariants(int[] entries, int[] dur, int[] mkDirs,
                                         int idxMin, int idxMax,
                                         DirPlan planLong, DirPlan planShort) {
        int n = dur.length;
        if (n == 0) {
            return null;
        }

        // A3: el dithering repartio exactamente las barras planificadas, EN CADA DIRECCION. Es la
        // comprobacion que garantiza que el mono replica no solo la exposicion total, sino cuanta
        // corresponde a cada sentido. Subsume la version global anterior, porque si ambos grupos
        // cuadran su suma tambien cuadra.
        int sumDurLong = 0;
        int sumDurShort = 0;
        int countLong = 0;
        int countShort = 0;
        for (int k = 0; k < n; k++) {
            if (mkDirs[k] > 0) {
                countLong++;
                sumDurLong += dur[k];
            } else {
                countShort++;
                sumDurShort += dur[k];
            }
        }

        if (sumDurLong != planLong.totalBars) {
            return "A3 (long exposure): sum(dur) over long trades=" + sumDurLong
                + " but planned=" + planLong.totalBars;
        }
        if (sumDurShort != planShort.totalBars) {
            return "A3 (short exposure): sum(dur) over short trades=" + sumDurShort
                + " but planned=" + planShort.totalBars;
        }

        // A4: la permutacion conservo cuantas operaciones van en cada direccion.
        if (countLong != planLong.count) {
            return "A4 (direction count): long trades=" + countLong + " but expected=" + planLong.count;
        }
        if (countShort != planShort.count) {
            return "A4 (direction count): short trades=" + countShort + " but expected=" + planShort.count;
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

    /** Bloque 1 del volcado: de donde sale el edge y en que regimen de volatilidad. */
    private void dumpAtrStats(ResultsGroup rg, PeriodDef pd, StrategyContext ctx, int n,
                              double edgeReal, double sumAbsAtrDisp, int atrPeriod, PeriodResult res,
                              int windowFloorHits, double exactBars,
                              DirPlan planLong, DirPlan planShort,
                              double sumBarsLong, double sumBarsShort,
                              int idxMin, int idxMax, int m) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== [").append(rg.getName()).append("] ").append(pd.label())
          .append(" | ATR STATS ===\n");
        sb.append("  atrPeriod=").append(atrPeriod)
          .append(" tickSize=").append(ctx.tickSize)
          .append(" spreadPoints=").append(ctx.spreadPoints)
          .append(" spreadPrice=").append(ctx.spreadPrice).append("\n");
        sb.append("  trades=").append(n)
          .append(" edge=").append(dbgFmt(edgeReal))
          .append(" edge/trade=").append(dbgFmt(res.edgePerTrade))
          .append(" sumAbsAtrDisp=").append(dbgFmt(sumAbsAtrDisp)).append("\n");
        sb.append("  ATR at entry -- min=").append(dbgFmt(res.atrMin))
          .append(" median=").append(dbgFmt(res.atrMedian))
          .append(" max=").append(dbgFmt(res.atrMax)).append("\n");
        sb.append("  atrFloorHits: realTrades=").append(res.atrFloorHits)
          .append(" simWindowBars=").append(windowFloorHits)
          .append("   (non-zero means padded or corrupt candles)\n");
        sb.append("  corr(ATR, atrDisp) over real trades = ").append(dbgFmt(res.atrEdgeCorrelation))
          .append("   (high |value| = edge concentrated in one volatility regime)\n");
        sb.append("  exactBars=").append(dbgFmt(exactBars))
          .append(" exposureRatio=").append(dbgFmt(res.exposureRatio))
          .append("   (overall)\n");
        // Desglose por direccion: es donde se ve si la exposicion de cada sentido se replico, y si
        // alguno de los dos grupos saturo su duracion base a una barra.
        sb.append("  LONG  trades=").append(planLong.count)
          .append(" realBars=").append(dbgFmt(sumBarsLong))
          .append(" exactBars=").append(dbgFmt(planLong.exactBars))
          .append(" baseBars=").append(planLong.baseBars)
          .append(" numExtra=").append(planLong.numExtra)
          .append(" plannedBars=").append(planLong.totalBars)
          .append(" exposureRatio=").append(dbgFmt(planLong.exposureRatio(sumBarsLong)))
          .append(planLong.clamped ? "  [CLAMPED to 1 bar]" : "").append("\n");
        sb.append("  SHORT trades=").append(planShort.count)
          .append(" realBars=").append(dbgFmt(sumBarsShort))
          .append(" exactBars=").append(dbgFmt(planShort.exactBars))
          .append(" baseBars=").append(planShort.baseBars)
          .append(" numExtra=").append(planShort.numExtra)
          .append(" plannedBars=").append(planShort.totalBars)
          .append(" exposureRatio=").append(dbgFmt(planShort.exposureRatio(sumBarsShort)))
          .append(planShort.clamped ? "  [CLAMPED to 1 bar]" : "").append("\n");
        sb.append("  window: idxMin=").append(idxMin).append(" idxMax=").append(idxMax)
          .append(" m=").append(m);
        logDebugDump(sb.toString());
    }

    /**
     * Bloque 2 del volcado: el reparto completo del primer mono. Se vuelcan todas las operaciones y
     * no una muestra, porque el objetivo es auditar a mano dos cosas: el no-solapamiento (cualquier
     * valor negativo en la columna gapToPrev lo delata) y la secuencia de direcciones, que debe
     * salir distinta en cada ejecucion pero con los mismos conteos que la estrategia real.
     */
    private void dumpMonkeyLayout(ResultsGroup rg, PeriodDef pd, int idxMin, int m,
                                  int[] entries, int[] dur, int[] mkDirs, String violation) {
        int n = dur.length;
        int sumDur = 0;
        int countLong = 0;
        int countShort = 0;
        int barsLong = 0;
        int barsShort = 0;
        for (int k = 0; k < n; k++) {
            sumDur += dur[k];
            if (mkDirs[k] > 0) {
                countLong++;
                barsLong += dur[k];
            } else {
                countShort++;
                barsShort += dur[k];
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== [").append(rg.getName()).append("] ").append(pd.label())
          .append(" | LAYOUT (monkey #0) ===\n");
        sb.append("  sumDur=").append(sumDur).append(" slack=").append(m - sumDur)
          .append(" firstEntry=").append(entries[0])
          .append(" lastExit=").append(entries[n - 1] + dur[n - 1]).append("\n");
        sb.append("  directions: long=").append(countLong).append(" (").append(barsLong).append(" bars)")
          .append("  short=").append(countShort).append(" (").append(barsShort).append(" bars)")
          .append("   (counts and bars per direction must match the real strategy)\n");
        sb.append("  invariants: ")
          .append(violation == null ? "A1 PASS  A2 PASS  A3 PASS  A4 PASS" : ("FAIL -> " + violation))
          .append("\n");
        sb.append("  k\tdir\tentry\tdur\texit\tgapToPrev\n");
        for (int k = 0; k < n; k++) {
            int exit = entries[k] + dur[k];
            int gap = (k == 0) ? (entries[0] - idxMin) : (entries[k] - (entries[k - 1] + dur[k - 1]));
            sb.append("  ").append(k).append('\t').append(mkDirs[k] > 0 ? "L" : "S").append('\t')
              .append(entries[k]).append('\t')
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
                Log.warn("MonkeyTest ATR v1: could not write the debug dump to " + DEBUG_LOG_DIR + "/"
                    + DEBUG_LOG_NAME + ": " + e.getMessage()
                    + ". Further write errors are not reported.");
            }
        } finally {
            if (pw != null) {
                pw.close();
            }
        }
    }

    // =========================================================
    // Cache del ResultsPlugin
    // =========================================================

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
                Log.error("MonkeyTest ATR v1: BDF history file not found for " + symbolConnection + " " + timeframe);
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

            Log.info(String.format("MonkeyTest ATR v1: loaded %d candles for %s %s", candles.size(), symbolConnection, timeframe));

        } catch (Exception e) {
            Log.error("MonkeyTest ATR v1: error loading BDF candles", e);
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
            Log.error("MonkeyTest ATR v1: error finding SnRbTs position in BDF file", e);
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
