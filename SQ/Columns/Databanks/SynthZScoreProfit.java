package SQ.Columns.Databanks;

import com.strategyquant.lib.*;
import com.strategyquant.datalib.*;
import com.strategyquant.tradinglib.*;

public class SynthZScoreProfit extends DatabankColumn {

    public SynthZScoreProfit() {
        super("SynthZScoreProfit",
                DatabankColumn.Decimal2,
                ValueTypes.Maximize,
                0,
                -5,
                5);

        setWidth(110);
        setTooltip("Overfitting Ratio: Z-Score (con signo) del Net Profit original vs media/stdev en 150 sintéticas (Custom Analysis).");
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String key = "CA_OverfittingRatio" + getSuffix(sampleType);
        Object v = rg.specialValues().get(key);
        
        if (v == null && sampleType == SampleTypes.FullSample) {
            v = rg.specialValues().get("CA_OverfittingRatio_OOS");
            if (v == null) {
                v = rg.specialValues().get("CA_OverfittingRatio_IS");
            }
            if (v == null) {
                v = rg.specialValues().get("CA_SynthZScoreProfit");
            }
        }
        
        if (v == null) return NOT_AVAILABLE;

        double d = (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
        return formatDouble(d, 2);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String key = "CA_OverfittingRatio" + getSuffix(sampleType);
        Object v = rg.specialValues().get(key);
        
        if (v == null && sampleType == SampleTypes.FullSample) {
            v = rg.specialValues().get("CA_OverfittingRatio_OOS");
            if (v == null) {
                v = rg.specialValues().get("CA_OverfittingRatio_IS");
            }
            if (v == null) {
                v = rg.specialValues().get("CA_SynthZScoreProfit");
            }
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
