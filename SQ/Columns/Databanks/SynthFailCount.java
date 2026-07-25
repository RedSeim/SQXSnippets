package SQ.Columns.Databanks;

import com.strategyquant.lib.*;
import com.strategyquant.datalib.*;
import com.strategyquant.tradinglib.*;

public class SynthFailCount extends DatabankColumn {

    public SynthFailCount() {
        super("SynthFailCount",
                DatabankColumn.Integer,
                ValueTypes.Minimize,
                0,
                0,
                150);

        setWidth(140);
        setTooltip("Número de sintéticas que fallaron durante el retest (Custom Analysis).");
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String key = "CA_SynthFailCount" + getSuffix(sampleType);
        Object v = rg.specialValues().get(key);
        
        if (v == null && sampleType == SampleTypes.FullSample) {
            v = rg.specialValues().get("CA_SynthFailCount");
        }
        
        if (v == null) return NOT_AVAILABLE;

        int n;
        if (v instanceof Number) {
            n = ((Number) v).intValue();
        } else {
            n = Double.valueOf(v.toString()).intValue();
        }
        return String.valueOf(n);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String key = "CA_SynthFailCount" + getSuffix(sampleType);
        Object v = rg.specialValues().get(key);
        
        if (v == null && sampleType == SampleTypes.FullSample) {
            v = rg.specialValues().get("CA_SynthFailCount");
        }
        
        if (v == null) return 0.0;
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return Double.parseDouble(v.toString());
    }

    private String getSuffix(byte sampleType) {
        if (sampleType == SampleTypes.InSample) return "_IS";
        if (sampleType == SampleTypes.OutOfSample) return "_OOS";
        if (sampleType == SampleTypes.InSampleValidation) return "_ISV";
        return "_Full";
    }
}
