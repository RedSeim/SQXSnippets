package SQ.Columns.Databanks;

import com.strategyquant.tradinglib.DatabankColumn;
import com.strategyquant.tradinglib.ResultsGroup;
import com.strategyquant.tradinglib.ValueTypes;

public class MonkeyTestZScoreColumn extends DatabankColumn {
    
    public MonkeyTestZScoreColumn() {
        super("Monkey Z-Score", 
              DatabankColumn.Decimal2, 
              ValueTypes.Maximize, 
              0, -10, 10);
        setWidth(80);
        setTooltip("Monkey Test Z-Score: Number of standard deviations the strategy profit is above the average monkey profit.");
    }

    @Override
    public String getValue(ResultsGroup results, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        if (results.specialValues().containsKey("MonkeyTestZScore")) {
            return results.specialValues().getString("MonkeyTestZScore");
        }
        return "N/A";
    }

    @Override
    public double getNumericValue(ResultsGroup results, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        if (results.specialValues().containsKey("MonkeyTestZScore")) {
            String val = results.specialValues().getString("MonkeyTestZScore");
            if (val != null && !"N/A".equals(val)) {
                try {
                    return Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            }
        }
        return 0.0;
    }

    @Override
    public String exportValue(ResultsGroup results, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        return getValue(results, resultKey, direction, plType, sampleType);
    }
}
