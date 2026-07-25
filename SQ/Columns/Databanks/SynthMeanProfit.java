package SQ.Columns.Databanks;

import com.strategyquant.lib.*;
import com.strategyquant.datalib.*;
import com.strategyquant.tradinglib.*;

public class SynthMeanProfit extends DatabankColumn {

    public SynthMeanProfit() {
        super("SynthMeanProfit",
                DatabankColumn.Decimal2,
                ValueTypes.Maximize,
                0,
                -1000000,
                1000000);

        setWidth(140);
        setTooltip("Media del Net Profit obtenida al retestear la estrategia en las sintéticas (Custom Analysis).");
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String key = "CA_SynthMeanProfit" + getSuffix(sampleType);
        Object v = rg.specialValues().get(key);
        
        if (v == null && sampleType == SampleTypes.FullSample) {
            v = rg.specialValues().get("CA_SynthMeanProfit");
        }
        
        if (v == null) return NOT_AVAILABLE;

        double d = (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
        return formatDouble(d, 2);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String key = "CA_SynthMeanProfit" + getSuffix(sampleType);
        Object v = rg.specialValues().get(key);
        
        if (v == null && sampleType == SampleTypes.FullSample) {
            v = rg.specialValues().get("CA_SynthMeanProfit");
        }
        
        if (v == null) return 0.0;
        return (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
    }

    private String getSuffix(byte sampleType) {
        if (sampleType == SampleTypes.InSample) return "_IS";
        if (sampleType == SampleTypes.OutOfSample) return "_OOS";
        if (sampleType == SampleTypes.InSampleValidation) return "_ISV";
        return "_Full";
    }

    private String formatDouble(double v, int decimals) {
        double p = Math.pow(10, decimals);
        return Double.toString(Math.round(v * p) / p);
    }
}
