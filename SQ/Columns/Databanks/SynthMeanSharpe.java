package SQ.Columns.Databanks;

import com.strategyquant.lib.*;
import com.strategyquant.datalib.*;
import com.strategyquant.tradinglib.*;

public class SynthMeanSharpe extends DatabankColumn {

    public SynthMeanSharpe() {
        super("SynthMeanSharpe",
                DatabankColumn.Decimal2,
                ValueTypes.Maximize,
                0,
                -10,
                10);

        setWidth(120);
        setTooltip("Synth Mean Sharpe: Media de los Ratios de Sharpe de todas las simulaciones sintéticas (Custom Analysis).");
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String key = "CA_SynthMeanSharpe" + getSuffix(sampleType);
        Object v = rg.specialValues().get(key);
        if (v == null) {
            key = "CA_SyntheticRatio" + getSuffix(sampleType);
            v = rg.specialValues().get(key);
        }
        
        if (v == null && sampleType == SampleTypes.FullSample) {
            v = rg.specialValues().get("CA_SynthMeanSharpe_OOS");
            if (v == null) {
                v = rg.specialValues().get("CA_SyntheticRatio_OOS");
            }
            if (v == null) {
                v = rg.specialValues().get("CA_SynthMeanSharpe_IS");
            }
            if (v == null) {
                v = rg.specialValues().get("CA_SyntheticRatio_IS");
            }
        }
        
        if (v == null) return NOT_AVAILABLE;

        double d = (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
        return formatDouble(d, 2);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String key = "CA_SynthMeanSharpe" + getSuffix(sampleType);
        Object v = rg.specialValues().get(key);
        if (v == null) {
            key = "CA_SyntheticRatio" + getSuffix(sampleType);
            v = rg.specialValues().get(key);
        }
        
        if (v == null && sampleType == SampleTypes.FullSample) {
            v = rg.specialValues().get("CA_SynthMeanSharpe_OOS");
            if (v == null) {
                v = rg.specialValues().get("CA_SyntheticRatio_OOS");
            }
            if (v == null) {
                v = rg.specialValues().get("CA_SynthMeanSharpe_IS");
            }
            if (v == null) {
                v = rg.specialValues().get("CA_SyntheticRatio_IS");
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
