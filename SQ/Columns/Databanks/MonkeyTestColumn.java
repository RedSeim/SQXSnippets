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
        setTooltip("Monkey Test result: PASSED, FAILED or LOW TRADES");
    }

    @Override
    public String getValue(ResultsGroup results, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        return results.specialValues().getString("MonkeyTestResult", "N/A");
    }

    @Override
    public String exportValue(ResultsGroup results, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        return results.specialValues().getString("MonkeyTestResult", "N/A");
    }
}
