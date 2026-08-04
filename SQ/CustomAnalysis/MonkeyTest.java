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
import com.strategyquant.datalib.data.io.newDataFormat.RandomAccessReaderFile;
import com.strategyquant.datalib.data.io.newDataFormat.OhlcDataReader;
import com.strategyquant.datalib.data.io.VersatileData;

public class MonkeyTest extends CustomAnalysisMethod {
    public static final Logger Log = LoggerFactory.getLogger(MonkeyTest.class);

    private static final int MAX_PARTS = 10;

    // Claves publicadas por periodo. Se limpian antes de recalcular cada periodo en scope
    // para que un resultado de una ejecución anterior no sobreviva a un test que falla.
    private static final String[] PERIOD_KEYS = {
        "MonkeyTestResult", "MonkeyTestPercentile", "MonkeyTestZScore"
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
     * Un periodo a evaluar: el sample type de SQX y el sufijo con el que se publican sus
     * claves. Los sufijos son los mismos que resuelven las Databank Columns a partir del
     * selector de sample type ("_IS", "_OOS", "_ISV", "_OOS1".."_OOS10", "_Full").
     */
    private static class PeriodDef {
        final byte sampleType;
        final String suffix;
        /** Sufijo adicional bajo el que publicar el mismo resultado (ver caso de 1 sola parte OOS). */
        String alsoPublishAs = null;

        PeriodDef(byte sampleType, String suffix) {
            this.sampleType = sampleType;
            this.suffix = suffix;
        }

        /** Etiqueta legible ("FULL", "IS", "OOS", "OOS2") usada en logs y en el meta.json de la caché. */
        String label() {
            return suffix.substring(1).toUpperCase();
        }

        boolean isNumberedPart() {
            return (sampleType > SampleTypes.OutOfSample && sampleType <= (byte) (SampleTypes.OutOfSample + MAX_PARTS))
                || (sampleType > SampleTypes.InSampleValidation && sampleType <= (byte) (SampleTypes.InSampleValidation + MAX_PARTS));
        }
    }

    /** Resultado del test para un periodo concreto, incluido lo necesario para escribir la caché. */
    private static class PeriodResult {
        String status = "ERROR";
        String percentileText = null;
        String zScoreText = null;

        /** true sólo si la simulación se completó y hay estadísticas para la caché. */
        boolean hasFullStats = false;

        double[][] curves;
        double[] sortedProfits;
        int actualCount;
        double initialBalance;
        double realProfit;
        double thresholdVal;
        double meanMonkey;
        double stdMonkey;
        double zScore;
        double rankPercentile;
        double meanHoldingPeriod;
        long tMin;
        long tMax;
    }

    public MonkeyTest() {
        super("MonkeyTest", TYPE_FILTER_STRATEGY);
    }

    @Override
    public boolean filterStrategy(String projectName, String task, String databankName, ResultsGroup rg) throws Exception {
        // Configuration defaults — can be overridden via Input Args field in the project task
        // as "numMonkeys,percentile,period,replicationMode,shiftingMode" e.g. "500,95,OOS,IndivBars,Random" or "500,95,OOS2,IndivBars,Random"
        // Additionally, include the keyword "ResultsPluginCache" anywhere in the Input Args to make
        // the snippet write the cache artifacts (CSV + meta.json) used by the ResultsPlugin
        // DatabankMonkeyTest. If not present, no cache files are written.
        int numMonkeys = 500;
        double percentile = 95.0;
        PeriodDef requested = new PeriodDef(SampleTypes.FullSample, "_Full");
        String replicationMode = "IndivBars";
        String shiftingMode = "Random";
        boolean writeResultsPluginCache = false;
        // Por defecto filterStrategy nunca excluye la estrategia -- ni de una copia
        // Origen->Destino entre databanks distintos ni de un borrado in-place -- igual que
        // CVSintetica_V08. El checkbox nativo "Filter by results of custom analysis" de SQX
        // sólo es efectivo cuando Origen==Destino; en una tarea que copia entre databanks
        // distintos no evita que las estrategias fallidas se excluyan de la copia. AutoDiscard
        // es la única forma de pedir explícitamente el descarte real en ambos escenarios.
        boolean autoDiscard = false;

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
                if (args.length >= 4 && !args[3].trim().isEmpty()) {
                    String repArg = args[3].trim();
                    if (repArg.equalsIgnoreCase("SLTP")) {
                        replicationMode = "SLTP";
                    } else if (repArg.equalsIgnoreCase("AvgBars")) {
                        replicationMode = "AvgBars";
                    } else if (repArg.equalsIgnoreCase("IndivBars")) {
                        replicationMode = "IndivBars";
                    } else {
                        Log.warn("MonkeyTest: unrecognized replicationMode argument '" + repArg + "'. Valid values: SLTP, AvgBars, IndivBars. Defaulting to IndivBars.");
                    }
                }
                if (args.length >= 5 && !args[4].trim().isEmpty()) {
                    String shfArg = args[4].trim();
                    if (shfArg.equalsIgnoreCase("Constant")) {
                        shiftingMode = "Constant";
                    } else if (shfArg.equalsIgnoreCase("Random")) {
                        shiftingMode = "Random";
                    } else {
                        Log.warn("MonkeyTest: unrecognized shiftingMode argument '" + shfArg + "'. Valid values: Constant, Random. Defaulting to Random.");
                    }
                }
                if (inputArgs.toUpperCase().contains("RESULTSPLUGINCACHE")) {
                    writeResultsPluginCache = true;
                }
                if (inputArgs.toUpperCase().contains("AUTODISCARD")) {
                    autoDiscard = true;
                }
            }
        } catch (Exception e) {
            Log.warn("Could not read input args, using defaults (500 monkeys, 95%, FULL, IndivBars, Random). Reason: " + e.getMessage());
        }

        Random rng = new Random();
        LinkedHashMap<String, PeriodResult> resultsBySuffix = new LinkedHashMap<>();
        ArrayList<PeriodDef> periods = new ArrayList<>();

        try {
            String mainResultKey = rg.getMainResultKey();
            Result mainResult = rg.mainResult();

            // Get the symbol and timeframe from main result key, e.g. "Main: USATECHIDXUSD_ftmo/M15"
            String symbolConnection = "";
            String timeframe = "";
            if (mainResultKey != null && mainResultKey.startsWith("Main: ")) {
                String cleanKey = mainResultKey.substring(6); // Remove "Main: "
                String[] parts = cleanKey.split("/");
                if (parts.length >= 2) {
                    symbolConnection = parts[0];
                    timeframe = parts[1];
                }
            }

            if (symbolConnection.isEmpty() || timeframe.isEmpty()) {
                throw new Exception("Could not parse symbol and timeframe from main result key: " + mainResultKey);
            }

            // Las velas son las mismas para todos los periodos: se cargan una única vez.
            ArrayList<Candle> candles = loadCandles(symbolConnection, timeframe, mainResult);

            periods = buildPeriodTable(rg, mainResultKey, requested);

            for (PeriodDef pd : periods) {
                clearPeriodKeys(rg, pd);

                PeriodResult res;
                if (candles == null || candles.isEmpty()) {
                    res = new PeriodResult();
                    res.status = "FAILED (NO DATA)";
                    Log.warn("No BDF candles loaded for strategy: " + rg.getName() + " on " + symbolConnection + " " + timeframe);
                } else {
                    OrdersList orders = resolveOrders(rg, mainResultKey, pd);
                    res = runMonkeyTestForPeriod(rg, pd, orders, candles, numMonkeys, percentile, replicationMode, shiftingMode, rng);
                }

                publishPeriodResult(rg, pd, res);
                resultsBySuffix.put(pd.suffix, res);
            }
        } catch (Exception e) {
            Log.error("Error computing Monkey Test for strategy " + rg.getName(), e);
        }

        // Claves legacy sin sufijo: sólo tienen sentido cuando lo pedido es el agregado FULL.
        // En cualquier otro caso se limpian, para que nunca muestren el valor de un periodo
        // concreto etiquetado como si fuera el total.
        PeriodResult primary = resultsBySuffix.get(requested.suffix);
        if (requested.sampleType == SampleTypes.FullSample && primary != null) {
            writeKeys(rg, "", primary);
        } else {
            for (String base : PERIOD_KEYS) {
                rg.specialValues().set(base, null);
            }
        }

        // El veredicto lo dicta únicamente el periodo pedido en los Input Args.
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
            } else {
                rg.specialValues().setString("FiltersResultFailedReason", "Failed Monkey Test");
            }
        } else if (existingReason == null || "".equals(existingReason) || "Passed".equals(existingReason)) {
            rg.specialValues().setString("FiltersResultFailedReason", "Passed");
        }

        // Sólo con AutoDiscard el resultado real se traslada al motor de SQX para que pueda
        // excluir la estrategia (de la copia Origen->Destino, o del databank si Origen==Destino
        // y además el checkbox nativo está activado). Por defecto se devuelve siempre true: la
        // marca visual de arriba (FiltersResultFailedReason/FilterResult) ya refleja el
        // resultado real sin necesidad de excluir nada.
        boolean passFilters = autoDiscard ? testPassed : true;

        // La caché del ResultsPlugin corresponde sólo al periodo pedido: el plugin localiza los
        // ficheros por nombre de estrategia (sin periodo), así que sólo puede haber un par por
        // estrategia. El campo "period" del meta.json identifica a cuál corresponde.
        if (writeResultsPluginCache && primary != null && primary.hasFullStats) {
            writeCacheArtifacts(rg, requested, primary, numMonkeys, percentile, replicationMode, shiftingMode);
        }

        return passFilters;
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

        Log.warn("MonkeyTest: unrecognized period argument '" + periodArg + "'. Valid values: FULL, IS, OOS, ISV, OOS1..OOS10, ISV1..ISV10. Defaulting to FULL.");
        return new PeriodDef(SampleTypes.FullSample, "_Full");
    }

    /** Devuelve 1..MAX_PARTS para "<prefix>N", o -1 si no encaja o está fuera de rango. */
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
            Log.warn("MonkeyTest: period part number out of range 1.." + MAX_PARTS + ": '" + periodArg + "'.");
        } catch (NumberFormatException e) {
            // cae al -1 de abajo
        }
        return -1;
    }

    /**
     * Periodos a calcular en esta ejecución. Si se pidió un periodo concreto, sólo ese. Si se
     * pidió FULL, además del agregado se sondean todos los periodos con operaciones para poder
     * consultarlos aislados: cada uno simula únicamente sus propios trades.
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
        // Con una sola parte OOS, SQX copia sus stats sobre el OOS agregado: son el mismo
        // periodo. Se simula una vez y se publica bajo ambos sufijos, en vez de repetir el
        // cómputo o dejar la columna OOS1 en N/A.
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
     * Órdenes del periodo, filtradas directamente por su sample type (incluidos los numerados
     * OutOfSample1..10 / InSampleValidation1..10). No se sustituyen nunca por las de otro
     * periodo: si el periodo pedido no tiene operaciones, se devuelve vacío y el llamante lo
     * reporta como LOW TRADES / FAILED (INVALID PERIOD).
     */
    private OrdersList resolveOrders(ResultsGroup rg, String mainResultKey, PeriodDef pd) {
        OrdersList orders = null;
        try {
            orders = rg.orders().filterWithClone(mainResultKey, Directions.Both, pd.sampleType);
        } catch (Exception e) {
            Log.warn("MonkeyTest: error filtering orders for " + pd.label() + ": " + e.getMessage());
        }

        if (orders != null && orders.size() > 0) {
            return orders;
        }

        // Única equivalencia admitida, y no es una sustitución por otro periodo: con una sola
        // parte OOS, OOS1 y el OOS agregado son literalmente el mismo tramo (SQX copia las
        // stats 21 -> 20). Si las órdenes sólo están etiquetadas como OOS, OOS1 debe usarlas.
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
                        Log.info("MonkeyTest: strategy [" + rg.getName() + "] has a single OOS segment, so OOS1 is the same period as OOS.");
                        return aggregated;
                    }
                } catch (Exception e) {
                    Log.warn("MonkeyTest: error filtering aggregated OOS orders: " + e.getMessage());
                }
            }
        }

        return orders;
    }

    // =========================================================
    // Publicación de resultados
    // =========================================================

    private void clearPeriodKeys(ResultsGroup rg, PeriodDef pd) {
        for (String base : PERIOD_KEYS) {
            rg.specialValues().set(base + pd.suffix, null);
            if (pd.alsoPublishAs != null) {
                rg.specialValues().set(base + pd.alsoPublishAs, null);
            }
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
    }

    // =========================================================
    // Cálculo del test para un periodo
    // =========================================================

    private PeriodResult runMonkeyTestForPeriod(ResultsGroup rg, PeriodDef pd, OrdersList orders,
                                                ArrayList<Candle> candles, int numMonkeys, double percentile,
                                                String replicationMode, String shiftingMode, Random rng) {
        PeriodResult res = new PeriodResult();

        try {
            // Filtered trade list: exclude balance orders and zero-length/zero-PL pseudo-trades.
            // Used consistently everywhere below so numTrades, curve length, and the
            // LOW TRADES threshold all agree with each other.
            ArrayList<Order> tradeOrders = new ArrayList<>();
            if (orders != null) {
                for (int i = 0; i < orders.size(); i++) {
                    Order o = orders.get(i);
                    if (o.isBalanceOrder()) continue;
                    if (o.OpenPrice == o.ClosePrice && Math.abs(o.PL) < 1e-9) continue;
                    tradeOrders.add(o);
                }
            }
            int tradeCount = tradeOrders.size();

            if (tradeCount == 0 && pd.isNumberedPart()) {
                res.status = "FAILED (INVALID PERIOD)";
                Log.warn("MonkeyTest: strategy [" + rg.getName() + "] has no trades in period " + pd.label() + " - that segment does not exist on this strategy. -> FAILED (INVALID PERIOD)");
                return res;
            }

            if (tradeCount < 20) {
                if (tradeCount == 0 && pd.sampleType != SampleTypes.FullSample) {
                    Log.warn("MonkeyTest: strategy [" + rg.getName() + "] has no trades in the " + pd.label() + " period. Verify that the last backtest has that sample period (IS/OOS) configured. -> LOW TRADES.");
                } else {
                    Log.warn("MonkeyTest: strategy [" + rg.getName() + "] has too few trades in the " + pd.label() + " period (" + tradeCount + " trades, minimum 20). -> LOW TRADES.");
                }
                res.status = "LOW TRADES";
                return res;
            }

            int barsCount = candles.size();
            long tfMs = inferTimeframeMs(candles);

            // Detect Friday exit
            boolean hasFriday = false;
            int FridayExitHour = 21;
            int FridayExitMinute = 0;
            for (int i = 0; i < tradeCount; i++) {
                Order o = tradeOrders.get(i);
                if (o.CloseType == 14 || o.CloseType == 16 || o.CloseType == 55) {
                    hasFriday = true;
                    java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                    cal.setTimeInMillis(o.CloseTime);
                    FridayExitHour = cal.get(java.util.Calendar.HOUR_OF_DAY);
                    FridayExitMinute = cal.get(java.util.Calendar.MINUTE);
                    break;
                }
            }

            // Find bar index range of trades
            long tMin = Long.MAX_VALUE;
            long tMax = Long.MIN_VALUE;
            for (int i = 0; i < tradeCount; i++) {
                Order o = tradeOrders.get(i);
                if (o.OpenTime < tMin) tMin = o.OpenTime;
                if (o.CloseTime > tMax) tMax = o.CloseTime;
            }

            int idxMin = findBarIndex(candles, tMin);
            int idxMax = findBarIndex(candles, tMax);

            if (idxMin == -1 || idxMax == -1 || idxMax <= idxMin) {
                idxMin = 0;
                idxMax = barsCount - 1;
            }

            int M = idxMax - idxMin + 1;

            // Pre-align trade orders to bar offsets
            int[] t_i = new int[tradeCount];
            int[] r_i = new int[tradeCount];
            for (int i = 0; i < tradeCount; i++) {
                Order o = tradeOrders.get(i);
                int originalIdx = findBarIndex(candles, o.OpenTime);
                if (originalIdx < idxMin || originalIdx > idxMax) {
                    originalIdx = idxMin + (idxMax - idxMin) / 2;
                }
                t_i[i] = originalIdx;
                r_i[i] = originalIdx - idxMin;
            }

            // Initial balance, derived from tradeOrders
            double initialBalance = tradeOrders.get(0).AccountBalance - tradeOrders.get(0).PL;

            // Precompute avgHoldingBars and meanHoldingPeriod
            int avgHoldingBars = 4;
            double totalHoldingBars = 0.0;
            for (int i = 0; i < tradeCount; i++) {
                totalHoldingBars += holdingBars(tradeOrders.get(i), tfMs);
            }
            if (tradeCount > 0) {
                avgHoldingBars = (int) Math.round(totalHoldingBars / tradeCount);
                if (avgHoldingBars < 1) avgHoldingBars = 1;
            }
            double meanHoldingPeriod = tradeCount > 0 ? (totalHoldingBars / tradeCount) : 0.0;

            int actualCount = tradeCount;
            if ("Random".equals(shiftingMode)) {
                actualCount = Math.min(tradeCount, (int) Math.floor((double) M / avgHoldingBars));
                if (actualCount <= 0) actualCount = 1;
            }

            double realProfit = 0;
            for (int i = 0; i < actualCount; i++) {
                realProfit += tradeOrders.get(i).PL;
            }

            // Run Monte Carlo simulations, keeping each monkey's full equity curve
            double[] monkeyProfits = new double[numMonkeys];
            double[][] curves = new double[numMonkeys][];

            for (int m = 0; m < numMonkeys; m++) {
                int globalShift = (M > 1) ? (rng.nextInt(M - 1) + 1) : 0;
                double runningBalance = initialBalance;
                double[] curve = new double[actualCount + 1];
                curve[0] = initialBalance;

                int[] entries = new int[actualCount];
                if ("Random".equals(shiftingMode)) {
                    double[] raws = new double[actualCount];
                    for (int i = 0; i < actualCount; i++) {
                        raws[i] = rng.nextDouble();
                    }
                    java.util.Arrays.sort(raws);

                    int usable = M - (actualCount - 1) * avgHoldingBars;
                    if (usable < 1) usable = 1;

                    for (int i = 0; i < actualCount; i++) {
                        int relativeOffset = (int) Math.floor(raws[i] * usable) + i * avgHoldingBars;
                        if (relativeOffset >= M) {
                            relativeOffset = M - 1;
                        }
                        entries[i] = idxMin + relativeOffset;
                    }
                } else {
                    for (int k = 0; k < actualCount; k++) {
                        int r_prime = (r_i[k] - globalShift) % M;
                        if (r_prime < 0) r_prime += M;
                        entries[k] = idxMin + r_prime;
                    }
                }

                for (int k = 0; k < actualCount; k++) {
                    Order o = tradeOrders.get(k);
                    int t_prime = entries[k];

                    double entryPrice = candles.get(t_prime).open;

                    boolean hasSL = false;
                    double exitPrice = -1;
                    long exitTime = 0;

                    if ("SLTP".equals(replicationMode)) {
                        // Parse original SL / TP relative percentages
                        hasSL = o.StopLoss > 0 && o.StopLoss != o.OpenPrice && o.StopLoss != Order.NOT_DEFINED;
                        boolean hasTP = o.TakeProfit > 0 && o.TakeProfit != o.OpenPrice && o.TakeProfit != Order.NOT_DEFINED;

                        double sl_pct = hasSL ? Math.abs(o.StopLoss - o.OpenPrice) / o.OpenPrice : 0.0;
                        double tp_pct = hasTP ? Math.abs(o.TakeProfit - o.OpenPrice) / o.OpenPrice : 0.0;
                        int direction = o.isShort() ? -1 : 1;

                        double slPrice = -1;
                        double tpPrice = -1;
                        if (hasSL) {
                            slPrice = direction == 1 ? entryPrice * (1.0 - sl_pct) : entryPrice * (1.0 + sl_pct);
                        }
                        if (hasTP) {
                            tpPrice = direction == 1 ? entryPrice * (1.0 + tp_pct) : entryPrice * (1.0 - tp_pct);
                        }

                        int maxBars = holdingBars(o, tfMs);
                        boolean useBarLimit = !hasSL && !hasTP;
                        int maxLoopBars = useBarLimit ? maxBars : (barsCount - t_prime);
                        if (maxLoopBars <= 0) maxLoopBars = 1;

                        // Step-by-step path evaluation
                        for (int b = 0; b < maxLoopBars; b++) {
                            int candleIdx = (t_prime + b) % barsCount;
                            Candle c = candles.get(candleIdx);

                            if (hasFriday && isAfterFridayExit(c.time, FridayExitHour, FridayExitMinute)) {
                                exitPrice = c.open;
                                exitTime = c.time;
                                break;
                            }

                            double low = c.low;
                            double high = c.high;

                            if (direction == 1) { // Long
                                if (hasSL && low <= slPrice) {
                                    exitPrice = slPrice;
                                    exitTime = c.time;
                                    break;
                                }
                                if (hasTP && high >= tpPrice) {
                                    exitPrice = tpPrice;
                                    exitTime = c.time;
                                    break;
                                }
                            } else { // Short
                                if (hasSL && high >= slPrice) {
                                    exitPrice = slPrice;
                                    exitTime = c.time;
                                    break;
                                }
                                if (hasTP && low <= tpPrice) {
                                    exitPrice = tpPrice;
                                    exitTime = c.time;
                                    break;
                                }
                            }
                        }

                        if (exitPrice == -1) {
                            int exitIdx = (t_prime + maxLoopBars - 1) % barsCount;
                            exitPrice = candles.get(exitIdx).close;
                            exitTime = candles.get(exitIdx).time;
                        }
                    } else {
                        // "AvgBars" or "IndivBars" replication modes
                        int maxBars = "AvgBars".equals(replicationMode) ? avgHoldingBars : holdingBars(o, tfMs);
                        int maxLoopBars = Math.min(maxBars, barsCount - t_prime);
                        if (maxLoopBars <= 0) maxLoopBars = 1;

                        for (int b = 0; b < maxLoopBars; b++) {
                            int candleIdx = (t_prime + b) % barsCount;
                            Candle c = candles.get(candleIdx);

                            if (hasFriday && isAfterFridayExit(c.time, FridayExitHour, FridayExitMinute)) {
                                exitPrice = c.open;
                                exitTime = c.time;
                                break;
                            }
                        }

                        if (exitPrice == -1) {
                            int exitIdx = (t_prime + maxLoopBars - 1) % barsCount;
                            exitPrice = candles.get(exitIdx).close;
                            exitTime = candles.get(exitIdx).time;
                        }
                    }

                    // P&L lot scaling and pip mapping
                    double origPriceDiff = o.ClosePrice - o.OpenPrice;
                    double grossOrigPL = o.PL - o.CommSwap;
                    double pipMult = Math.abs(origPriceDiff) > 1e-7 ? grossOrigPL / (o.Size * origPriceDiff) : 0.0;

                    double priceCorrection = hasSL ? (o.OpenPrice / entryPrice) : 1.0;
                    double monkeySize = o.Size * priceCorrection;
                    if (monkeySize < 0.01) monkeySize = 0.01;

                    double simPriceDiff = exitPrice - entryPrice;
                    double grossPL = monkeySize * simPriceDiff * pipMult;

                    double monkeyCommSwap = (o.Size > 1e-9) ? (o.CommSwap / o.Size) * monkeySize : 0.0;
                    double tradePL = grossPL + monkeyCommSwap;

                    runningBalance += tradePL;
                    curve[k + 1] = runningBalance;
                }

                curves[m] = curve;
                monkeyProfits[m] = curve[actualCount] - curve[0];
            }

            // Statistics over all N monkeys (mean/std/zScore/threshold/rankPercentile/status)
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

            res.status = (realProfit > thresholdVal) ? "PASSED" : "FAILED";
            res.percentileText = String.format(java.util.Locale.US, "%.2f%%", rankPercentile);
            res.zScoreText = String.format(java.util.Locale.US, "%.2f", zScore);

            res.hasFullStats = true;
            res.curves = curves;
            res.sortedProfits = sortedProfits;
            res.actualCount = actualCount;
            res.initialBalance = initialBalance;
            res.realProfit = realProfit;
            res.thresholdVal = thresholdVal;
            res.meanMonkey = mean;
            res.stdMonkey = std;
            res.zScore = zScore;
            res.rankPercentile = rankPercentile;
            res.meanHoldingPeriod = meanHoldingPeriod;
            res.tMin = tMin;
            res.tMax = tMax;

        } catch (Exception e) {
            res.status = "ERROR";
            res.hasFullStats = false;
            Log.error("Error computing Monkey Test for strategy " + rg.getName() + " period " + pd.label(), e);
        }

        return res;
    }

    // =========================================================
    // Caché del ResultsPlugin
    // =========================================================

    private void writeCacheArtifacts(ResultsGroup rg, PeriodDef pd, PeriodResult res, int numMonkeys,
                                     double percentile, String replicationMode, String shiftingMode) {
        java.io.PrintWriter csvWriter = null;
        java.io.PrintWriter metaWriter = null;
        try {
            java.io.File cacheDir = new java.io.File("user/extend/ResultsPlugins/DatabankMonkeyTest/cache");
            cacheDir.mkdirs();

            // Rank monkeys by profit without losing the index <-> curve correspondence
            final double[] profitsForSort = new double[numMonkeys];
            for (int i = 0; i < numMonkeys; i++) {
                profitsForSort[i] = res.curves[i][res.actualCount] - res.curves[i][0];
            }
            Integer[] order = new Integer[numMonkeys];
            for (int i = 0; i < numMonkeys; i++) order[i] = i;
            Arrays.sort(order, new java.util.Comparator<Integer>() {
                public int compare(Integer a, Integer b) {
                    return Double.compare(profitsForSort[a], profitsForSort[b]);
                }
            });

            // Select up to 50 representative curves: min, max, and uniform percentile steps
            int numCurves = Math.min(50, numMonkeys);
            java.util.TreeSet<Integer> positions = new java.util.TreeSet<>();
            positions.add(0);
            positions.add(numMonkeys - 1);
            for (int k = 1; k <= numCurves - 2; k++) {
                int pos = (int) Math.round(k * (numMonkeys - 1) / (double) (numCurves - 1));
                positions.add(pos);
            }

            String csvPath = cacheDir.getPath() + "/" + rg.getName() + "_monkey_simulation_data.csv";
            csvWriter = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(csvPath), java.nio.charset.StandardCharsets.UTF_8));

            StringBuilder header = new StringBuilder("monkey_id");
            for (int b = 0; b <= res.actualCount; b++) header.append(";b").append(b);
            csvWriter.println(header.toString());

            int qLabel = 1;
            for (int pos : positions) {
                String label;
                if (pos == 0) label = "min";
                else if (pos == numMonkeys - 1) label = "max";
                else label = String.format("q%02d", qLabel++);

                double[] curve = res.curves[order[pos]];
                StringBuilder row = new StringBuilder(label);
                for (int b = 0; b <= res.actualCount; b++) {
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
            metaWriter.println("  \"schemaVersion\": 3,");
            metaWriter.println("  \"strategyName\": \"" + escapedName + "\",");
            metaWriter.println("  \"period\": \"" + pd.label() + "\",");
            metaWriter.println("  \"tradeFromMs\": " + res.tMin + ",");
            metaWriter.println("  \"tradeToMs\": " + res.tMax + ",");
            metaWriter.println("  \"numTrades\": " + res.actualCount + ",");
            metaWriter.println("  \"numMonkeys\": " + numMonkeys + ",");
            metaWriter.println("  \"percentile\": " + String.format(java.util.Locale.US, "%.1f", percentile) + ",");
            metaWriter.println("  \"replicationMode\": \"" + replicationMode + "\",");
            metaWriter.println("  \"shiftingMode\": \"" + shiftingMode + "\",");
            metaWriter.println("  \"initialBalance\": " + String.format(java.util.Locale.US, "%.2f", res.initialBalance) + ",");
            metaWriter.println("  \"realProfit\": " + String.format(java.util.Locale.US, "%.2f", res.realProfit) + ",");
            metaWriter.println("  \"monkeyThreshold\": " + String.format(java.util.Locale.US, "%.2f", res.thresholdVal) + ",");
            metaWriter.println("  \"meanMonkey\": " + String.format(java.util.Locale.US, "%.2f", res.meanMonkey) + ",");
            metaWriter.println("  \"stdMonkey\": " + String.format(java.util.Locale.US, "%.2f", res.stdMonkey) + ",");
            metaWriter.println("  \"zScore\": " + String.format(java.util.Locale.US, "%.2f", res.zScore) + ",");
            metaWriter.println("  \"rankPercentile\": " + String.format(java.util.Locale.US, "%.2f", res.rankPercentile) + ",");
            metaWriter.println("  \"status\": \"" + res.status + "\",");
            metaWriter.println("  \"meanHoldingPeriod\": " + String.format(java.util.Locale.US, "%.1f", res.meanHoldingPeriod) + ",");
            metaWriter.println("  \"monkeyProfits\": " + profitsArr.toString() + ",");
            metaWriter.println("  \"generatedAtUtc\": " + System.currentTimeMillis() + ",");
            metaWriter.println("  \"source\": \"CustomAnalysis\"");
            metaWriter.println("}");
        } catch (Exception cacheEx) {
            Log.warn("MonkeyTest: could not write cache artifacts for " + rg.getName() + ": " + cacheEx.getMessage());
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
    // Datos históricos y utilidades
    // =========================================================

    private ArrayList<Candle> loadCandles(String symbolConnection, String timeframe, Result mainResult) {
        ArrayList<Candle> candles = new ArrayList<>();
        RandomAccessReaderFile reader = null;
        try {
            // Find dat file path
            String path = "user/data/History/" + symbolConnection + "/" + symbolConnection + "_" + timeframe + ".dat";
            java.io.File file = new java.io.File(path);
            if (!file.exists()) {
                // Try case-insensitive search or fallback
                java.io.File historyDir = new java.io.File("user/data/History");
                if (historyDir.exists() && historyDir.isDirectory()) {
                    for (java.io.File sub : historyDir.listFiles()) {
                        if (sub.isDirectory() && sub.getName().equalsIgnoreCase(symbolConnection)) {
                            java.io.File[] datFiles = sub.listFiles();
                            if (datFiles != null) {
                                for (java.io.File f : datFiles) {
                                    if (f.getName().equalsIgnoreCase(symbolConnection + "_" + timeframe + ".dat")) {
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
                Log.error("BDF history file not found for " + symbolConnection + " " + timeframe);
                return null;
            }

            long dataStartPos = findDataStartPosition(file.getAbsolutePath());
            Log.info("Found SnRbTs magic header for BDF file at position: " + dataStartPos);

            reader = new RandomAccessReaderFile(file.getAbsolutePath());
            reader.openFile();

            OhlcDataReader ohlcReader = new OhlcDataReader(true);
            ohlcReader.setDataStartPosition(dataStartPos);
            ohlcReader.overrideDecimals(6); // Standard scale precision inside SQX BDF binary files is 6
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

            Log.info(String.format("Loaded %d candles for %s %s",
                candles.size(), symbolConnection, timeframe));

        } catch (Exception e) {
            Log.error("Error loading BDF candles", e);
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
            Log.error("Error finding SnRbTs position in BDF file", e);
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Exception e) {}
            }
        }
        return 94; // Standard fallback offset
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

    private int holdingBars(Order o, long tfMs) {
        long dur = o.CloseTime - o.OpenTime;
        if (dur > 0 && tfMs > 0) {
            return (int) Math.min(5000, Math.max(1, Math.round((double) dur / tfMs)));
        }
        return o.BarsInTrade > 0 ? o.BarsInTrade : 4;
    }

    private boolean isAfterFridayExit(long time, int exitHour, int exitMinute) {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(time);
        int day = cal.get(java.util.Calendar.DAY_OF_WEEK);
        if (day == java.util.Calendar.FRIDAY) {
            int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int m = cal.get(java.util.Calendar.MINUTE);
            if (h > exitHour || (h == exitHour && m >= exitMinute)) {
                return true;
            }
        } else if (day == java.util.Calendar.SATURDAY || day == java.util.Calendar.SUNDAY) {
            return true;
        }
        return false;
    }
}
