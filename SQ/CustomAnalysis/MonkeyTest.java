package SQ.CustomAnalysis;

import com.strategyquant.lib.*;
import com.strategyquant.datalib.*;
import com.strategyquant.tradinglib.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.strategyquant.datalib.data.io.newDataFormat.RandomAccessReaderFile;
import com.strategyquant.datalib.data.io.newDataFormat.OhlcDataReader;
import com.strategyquant.datalib.data.io.VersatileData;

public class MonkeyTest extends CustomAnalysisMethod {
    public static final Logger Log = LoggerFactory.getLogger(MonkeyTest.class);
    
    public static class Candle {
        public long time;
        public double open;
        public double high;
        public double low;
        public double close;
        public double volume;
    }
    
    public MonkeyTest() {
        super("MonkeyTest", TYPE_PROCESS_DATABANK);
    }

    @Override
    public boolean filterStrategy(String project, String task, String databankName, ResultsGroup rg) throws Exception {
        return true;
    }

    @Override
    public ArrayList<ResultsGroup> processDatabank(String projectName, String task, String databankName, ArrayList<ResultsGroup> databankRG) throws Exception {
        // Configuration defaults — can be overridden via Input Args field in the project task
        // as "numMonkeys,percentile,period" e.g. "500,95,OOS" (period: FULL | IS | OOS, default FULL)
        int numMonkeys = 500;
        double percentile = 95.0;
        byte sampleType = SampleTypes.FullSample;
        String sampleLabel = "FULL";

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
                    String periodArg = args[2].trim().toUpperCase();
                    if ("IS".equals(periodArg)) {
                        sampleType = SampleTypes.InSample;
                        sampleLabel = "IS";
                    } else if ("OOS".equals(periodArg)) {
                        sampleType = SampleTypes.OutOfSample;
                        sampleLabel = "OOS";
                    } else if ("FULL".equals(periodArg)) {
                        sampleType = SampleTypes.FullSample;
                        sampleLabel = "FULL";
                    } else {
                        Log.warn("MonkeyTest: unrecognized period argument '" + periodArg + "'. Valid values: FULL, IS, OOS. Defaulting to FULL.");
                    }
                }
            }
        } catch (Exception e) {
            Log.warn("Could not read input args, using defaults (500 monkeys, 95%, FULL). Reason: " + e.getMessage());
        }

        Random rng = new Random();
        ArrayList<ResultsGroup> targetRG = new ArrayList<>();

        for (ResultsGroup rg : databankRG) {
            ResultsGroup rgClone = rg.clone();
            String status = "FAILED";
            
            java.io.PrintWriter csvWriter = null;
            java.io.PrintWriter metaWriter = null;
            try {
                Result mainResult = rgClone.mainResult();
                String mainResultKey = rgClone.getMainResultKey();
                
                // Retrieve all trades
                OrdersList orders = rgClone.orders().filterWithClone(mainResultKey, Directions.Both, sampleType);
                
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
                
                // Load candles from .dat file
                ArrayList<Candle> candles = loadCandles(symbolConnection, timeframe, mainResult);
                
                if (orders.size() < 20) {
                    if (orders.size() == 0 && sampleType != SampleTypes.FullSample) {
                        Log.warn("MonkeyTest: strategy [" + rgClone.getName() + "] has no trades in the " + sampleLabel + " period. Verify that the last backtest has that sample period (IS/OOS) configured. -> LOW TRADES.");
                    } else {
                        Log.warn("MonkeyTest: strategy [" + rgClone.getName() + "] has too few trades in the " + sampleLabel + " period (" + orders.size() + " trades, minimum 20). -> LOW TRADES.");
                    }
                    status = "LOW TRADES";
                } else if (candles == null || candles.isEmpty()) {
                    status = "FAILED (NO DATA)";
                    Log.warn("No BDF candles loaded for strategy: " + rgClone.getName() + " on " + symbolConnection + " " + timeframe);
                } else {
                    int barsCount = candles.size();
                    long tfMs = inferTimeframeMs(candles);
                    
                    // Detect Friday exit
                    boolean hasFriday = false;
                    int FridayExitHour = 21;
                    int FridayExitMinute = 0;
                    for (int i = 0; i < orders.size(); i++) {
                        Order o = orders.get(i);
                        if (o.isBalanceOrder()) continue;
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
                    for (int i = 0; i < orders.size(); i++) {
                        Order o = orders.get(i);
                        if (o.isBalanceOrder()) continue;
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
                    
                    // Pre-align original orders to bar offsets
                    int[] t_i = new int[orders.size()];
                    int[] r_i = new int[orders.size()];
                    for (int i = 0; i < orders.size(); i++) {
                        Order o = orders.get(i);
                        int originalIdx = findBarIndex(candles, o.OpenTime);
                        if (originalIdx < idxMin || originalIdx > idxMax) {
                            originalIdx = idxMin + (idxMax - idxMin) / 2;
                        }
                        t_i[i] = originalIdx;
                        r_i[i] = originalIdx - idxMin;
                    }
                    
                    // Calculate real strategy net profit
                    double realProfit = 0;
                    for (int i = 0; i < orders.size(); i++) {
                        realProfit += orders.get(i).PL;
                    }

                    // Open CSV writer for monkey simulation data
                    new java.io.File("user/extend/ResultsPlugins/DatabankMonkeyTest/cache").mkdirs();
                    String csvPath = "user/extend/ResultsPlugins/DatabankMonkeyTest/cache/" + rgClone.getName() + "_monkey_simulation_data.csv";
                    csvWriter = new java.io.PrintWriter(new java.io.FileWriter(csvPath));
                    csvWriter.println("\"Monkey ID\";\"Ticket\";\"Symbol\";\"Type\";\"Open time\";\"Open price\";\"Size\";\"Close time\";\"Close price\";\"Profit/Loss\";\"Balance\";\"Sample type\";\"Close type\";\"MAE ($)\";\"MFE ($)\";\"Time in trade\";\"Comment\";\"Comm/Swap\";\"Stop Loss price level\";\"Profit/Loss\";\"Profit/Loss Pips\";\"Balance\";\"Profit Target price level\"");

                    // Run Monte Carlo simulations
                    double[] monkeyProfits = new double[numMonkeys];
                    
                    for (int m = 0; m < numMonkeys; m++) {
                        int shift = rng.nextInt(M - 1) + 1;
                        double runningBalance = orders.get(0).AccountBalance - orders.get(0).PL;
                        
                        for (int k = 0; k < orders.size(); k++) {
                            Order o = orders.get(k);
                            if (o.isBalanceOrder()) continue;
                            
                            // Circular shift relative bar index
                            int r_prime = (r_i[k] - shift) % M;
                            if (r_prime < 0) r_prime += M;
                            int t_prime = idxMin + r_prime;
                            
                            double entryPrice = candles.get(t_prime).open;
                            
                            // Parse original SL / TP relative percentages
                            boolean hasSL = o.StopLoss > 0 && o.StopLoss != o.OpenPrice && o.StopLoss != Order.NOT_DEFINED;
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
                            
                            double exitPrice = -1;
                            long exitTime = 0;
                            String closeTypeStr = "Time exit";
                            
                            int maxBars = holdingBars(o, tfMs);
                            boolean useBarLimit = !hasSL && !hasTP;
                            int maxLoopBars = useBarLimit ? maxBars : (barsCount - t_prime);
                            if (maxLoopBars <= 0) maxLoopBars = 1;
                            
                            double worstLow = entryPrice;
                            double bestHigh = entryPrice;
                            
                            // Step-by-step path evaluation
                            for (int b = 0; b < maxLoopBars; b++) {
                                int candleIdx = (t_prime + b) % barsCount;
                                Candle c = candles.get(candleIdx);
                                
                                if (c.low < worstLow) worstLow = c.low;
                                if (c.high > bestHigh) bestHigh = c.high;
                                
                                if (hasFriday && isAfterFridayExit(c.time, FridayExitHour, FridayExitMinute)) {
                                    exitPrice = c.open;
                                    exitTime = c.time;
                                    closeTypeStr = "Friday Exit";
                                    break;
                                }

                                double low = c.low;
                                double high = c.high;
                                
                                if (direction == 1) { // Long
                                    if (hasSL && low <= slPrice) {
                                        exitPrice = slPrice;
                                        exitTime = c.time;
                                        closeTypeStr = "SL";
                                        break;
                                    }
                                    if (hasTP && high >= tpPrice) {
                                        exitPrice = tpPrice;
                                        exitTime = c.time;
                                        closeTypeStr = "PT";
                                        break;
                                    }
                                } else { // Short
                                    if (hasSL && high >= slPrice) {
                                        exitPrice = slPrice;
                                        exitTime = c.time;
                                        closeTypeStr = "SL";
                                        break;
                                    }
                                    if (hasTP && low <= tpPrice) {
                                        exitPrice = tpPrice;
                                        exitTime = c.time;
                                        closeTypeStr = "PT";
                                        break;
                                    }
                                }
                            }
                            
                            if (exitPrice == -1) {
                                int exitIdx = (t_prime + maxLoopBars - 1) % barsCount;
                                exitPrice = candles.get(exitIdx).close;
                                exitTime = candles.get(exitIdx).time;
                                if (candles.get(exitIdx).low < worstLow) worstLow = candles.get(exitIdx).low;
                                if (candles.get(exitIdx).high > bestHigh) bestHigh = candles.get(exitIdx).high;
                            }
                            
                            // P&L lot scaling and pip mapping
                            double origPriceDiff = o.ClosePrice - o.OpenPrice;
                            double grossOrigPL = o.PL - o.CommSwap;
                            double pipMult = Math.abs(origPriceDiff) > 1e-8 ? grossOrigPL / (o.Size * origPriceDiff) : 0.0;
                            
                            double priceCorrection = hasSL ? (o.OpenPrice / entryPrice) : 1.0;
                            double monkeySize = o.Size * priceCorrection;
                            if (monkeySize < 0.01) monkeySize = 0.01;
                            
                            double simPriceDiff = exitPrice - entryPrice;
                            double grossPL = monkeySize * simPriceDiff * pipMult;
                            
                            double monkeyCommSwap = (o.Size > 1e-9) ? (o.CommSwap / o.Size) * monkeySize : 0.0;
                            double tradePL = grossPL + monkeyCommSwap;
                            
                            runningBalance += tradePL;
                            
                            // MAE/MFE calculation
                            double moneyAtLow = monkeySize * (worstLow - entryPrice) * pipMult;
                            double moneyAtHigh = monkeySize * (bestHigh - entryPrice) * pipMult;
                            double maeMoney = Math.min(0.0, Math.min(moneyAtLow, moneyAtHigh));
                            double mfeMoney = Math.max(0.0, Math.max(moneyAtLow, moneyAtHigh));
                            
                            // Pips calculation
                            double plPips = 0.0;
                            double tickSize = 0.0001;
                            if (Math.abs(o.PipsPL) > 1e-9 && Math.abs(origPriceDiff) > 1e-8) {
                                double pipsPerPriceUnit = o.PipsPL / origPriceDiff;
                                plPips = simPriceDiff * pipsPerPriceUnit;
                            } else {
                                plPips = simPriceDiff / tickSize;
                            }
                            
                            // Formatting strings for CSV row
                            String openTimeStr = fmtDate(candles.get(t_prime).time);
                            String closeTimeStr = fmtDate(exitTime);
                            String durStr = formatDuration(exitTime - candles.get(t_prime).time);
                            
                            String typeStr = o.isShort() ? "Sell" : "Buy";
                            String sampleTypeStr = (o.SampleType == 10) ? "IST" : "OOT";
                            
                            csvWriter.println(String.format(java.util.Locale.US,
                                "%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s",
                                fmtText(String.valueOf(m + 1)), // Monkey ID
                                fmtText(String.format("M%d_%d", m + 1, o.Ticket)), // Ticket
                                fmtText(symbolConnection), // Symbol
                                fmtText(typeStr), // Type
                                openTimeStr, // Open time
                                fmtPrice(entryPrice), // Open price
                                fmtPrice(monkeySize), // Size
                                closeTimeStr, // Close time
                                fmtPrice(exitPrice), // Close price
                                fmtMoney(tradePL), // Profit/Loss
                                fmtMoney(runningBalance), // Balance
                                fmtText(sampleTypeStr), // Sample type
                                fmtText(closeTypeStr), // Close type
                                fmtMoney(maeMoney), // MAE ($)
                                fmtMoney(mfeMoney), // MFE ($)
                                durStr, // Time in trade
                                fmtText(String.format("Monkey %d", m + 1)), // Comment
                                fmtMoney(monkeyCommSwap), // Comm/Swap
                                fmtLevel(slPrice > 0 ? slPrice : 0.0), // Stop Loss price level
                                fmtMoney(tradePL), // Profit/Loss (duplicate)
                                fmtMoney(plPips), // Profit/Loss Pips
                                fmtMoney(runningBalance), // Balance (duplicate)
                                fmtLevel(tpPrice > 0 ? tpPrice : 0.0) // Profit Target price level
                            ));
                        }
                        
                        monkeyProfits[m] = runningBalance - (orders.get(0).AccountBalance - orders.get(0).PL);
                    }
                    
                    // Sort profits to compute percentile cutoff
                    Arrays.sort(monkeyProfits);
                    int thresholdIndex = (int) Math.floor(numMonkeys * (percentile / 100.0));
                    if (thresholdIndex >= numMonkeys) thresholdIndex = numMonkeys - 1;
                    double thresholdVal = monkeyProfits[thresholdIndex];
                    
                    if (realProfit > thresholdVal) {
                        status = "PASSED";
                    } else {
                        status = "FAILED";
                    }

                    // Write sidecar fingerprint for ResultsPlugin temporal verification
                    try {
                        String metaPath = "user/extend/ResultsPlugins/DatabankMonkeyTest/cache/" + rgClone.getName() + "_monkey_simulation_data.meta.json";
                        metaWriter = new java.io.PrintWriter(new java.io.FileWriter(metaPath));
                        String escapedName = rgClone.getName().replace("\\", "\\\\").replace("\"", "\\\"");
                        metaWriter.println("{");
                        metaWriter.println("  \"strategyName\": \"" + escapedName + "\",");
                        metaWriter.println("  \"period\": \"" + sampleLabel + "\",");
                        metaWriter.println("  \"tradeFromMs\": " + tMin + ",");
                        metaWriter.println("  \"tradeToMs\": " + tMax + ",");
                        metaWriter.println("  \"numTrades\": " + orders.size() + ",");
                        metaWriter.println("  \"realProfit\": " + String.format(java.util.Locale.US, "%.2f", realProfit) + ",");
                        metaWriter.println("  \"percentile\": " + String.format(java.util.Locale.US, "%.1f", percentile) + ",");
                        metaWriter.println("  \"numMonkeys\": " + numMonkeys + ",");
                        metaWriter.println("  \"generatedAtUtc\": " + System.currentTimeMillis() + ",");
                        metaWriter.println("  \"source\": \"CustomAnalysis\"");
                        metaWriter.println("}");
                    } catch (Exception metaEx) {
                        Log.warn("MonkeyTest: could not write .meta.json sidecar for " + rgClone.getName() + ": " + metaEx.getMessage());
                    }
                }
            } catch (Exception e) {
                status = "ERROR";
                Log.error("Error computing Monkey Test for strategy " + rgClone.getName(), e);
            } finally {
                if (csvWriter != null) {
                    try { csvWriter.close(); } catch (Exception ex) {}
                }
                if (metaWriter != null) {
                    try { metaWriter.close(); } catch (Exception ex) {}
                }
            }
            
            // Set the outcome back to the cloned strategy
            rgClone.specialValues().setString("MonkeyTestResult", status);
            
            // Update the generic FilterResult column to allow automatic filtering
            boolean passFilters = "PASSED".equals(status);
            boolean existingFilterResult = true;
            try {
                existingFilterResult = rgClone.specialValues().getBoolean("FilterResult", true);
            } catch (Exception e) {
                // Default to true if not present or unreadable
            }
            rgClone.specialValues().set("FilterResult", existingFilterResult && passFilters);
            
            // Update the FiltersResult visual column in Databank
            String existingReason = null;
            if (rgClone.specialValues().containsKey("FiltersResultFailedReason")) {
                existingReason = rgClone.specialValues().getString("FiltersResultFailedReason");
            }
            
            if (!passFilters) {
                rgClone.specialValues().setString("FiltersResultFailedReason", "Failed Monkey Test");
            } else if (existingReason == null || "".equals(existingReason) || "Passed".equals(existingReason)) {
                rgClone.specialValues().setString("FiltersResultFailedReason", "Passed");
            }
            
            targetRG.add(rgClone);
        }

        return targetRG;
    }

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

    private String fmtText(String val) {
        if (val == null) return "\"\"";
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }

    private String fmtPrice(double val) {
        String strVal = String.format(java.util.Locale.US, "%.5f", val);
        if (strVal.contains(".")) {
            strVal = strVal.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return "\"" + strVal + "\"";
    }

    private String fmtMoney(double val) {
        return "\"" + String.format(java.util.Locale.US, "%.2f", val).replace(".", ",") + "\"";
    }

    private String fmtLevel(double val) {
        return "\"" + String.format(java.util.Locale.US, "%.5f", val).replace(".", ",") + "\"";
    }

    private String fmtDate(long timestamp) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy.MM.dd HH:mm:ss");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return fmtText(sdf.format(new java.util.Date(timestamp)));
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "\"0s\"";
        long totalSecs = ms / 1000;
        long days = totalSecs / (24 * 3600);
        totalSecs %= (24 * 3600);
        long hours = totalSecs / 3600;
        totalSecs %= 3600;
        long mins = totalSecs / 60;
        long secs = totalSecs % 60;
        
        ArrayList<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (mins > 0) parts.add(mins + "m");
        if (parts.isEmpty() || secs > 0) parts.add(secs + "s");
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            sb.append(parts.get(i));
            if (i < parts.size() - 1) sb.append(" ");
        }
        return fmtText(sb.toString());
    }
}
