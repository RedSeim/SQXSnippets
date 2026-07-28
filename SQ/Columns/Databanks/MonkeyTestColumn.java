package SQ.Columns.Databanks;

import com.strategyquant.lib.L;
import com.strategyquant.tradinglib.DatabankColumn;
import com.strategyquant.tradinglib.ResultsGroup;
import com.strategyquant.tradinglib.ValueTypes;

public class MonkeyTestColumn extends DatabankColumn {
    
    public MonkeyTestColumn() {
        super("Monkey Test", 
              DatabankColumn.Text, 
              ValueTypes.Minimize, 
              0, 0, 0);
        setWidth(100);
        setTooltip("Monkey Test result: Displays the percentile score achieved (e.g. 85.20%) or the failure status (LOW TRADES, ERROR, etc.)");
    }

    @Override
    public String getValue(ResultsGroup results, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        if (results.specialValues().containsKey("MonkeyTestPercentile")) {
            String pct = results.specialValues().getString("MonkeyTestPercentile");
            if (pct != null && !"N/A".equals(pct)) {
                return pct;
            }
        }
        return results.specialValues().getString("MonkeyTestResult", "N/A");
    }

    @Override
    public String exportValue(ResultsGroup results, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        if (results.specialValues().containsKey("MonkeyTestPercentile")) {
            String pct = results.specialValues().getString("MonkeyTestPercentile");
            if (pct != null && !"N/A".equals(pct)) {
                return pct;
            }
        }
        return results.specialValues().getString("MonkeyTestResult", "N/A");
    }
}
