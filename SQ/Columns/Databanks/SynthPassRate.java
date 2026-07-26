package SQ.Columns.Databanks;

import com.strategyquant.lib.*;
import com.strategyquant.datalib.*;
import com.strategyquant.tradinglib.*;

public class SynthPassRate extends DatabankColumn {

    public SynthPassRate() {
        super("SynthPassRate",
                DatabankColumn.Decimal2Pct,
                ValueTypes.Maximize,
                0,
                0,
                100);

        setWidth(100);
        setTooltip("Pass Rate (Survival Rate): Porcentaje de simulaciones sintéticas con beneficio estrictamente mayor que cero (Custom Analysis).");
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String key = "CA_PassRate" + getSuffix(sampleType);
        Object v = rg.specialValues().get(key);
        
        if (v == null && sampleType == SampleTypes.FullSample) {
            v = rg.specialValues().get("CA_PassRate_OOS");
            if (v == null) {
                v = rg.specialValues().get("CA_PassRate_IS");
            }
            if (v == null) {
                v = rg.specialValues().get("CA_PassRate");
            }
        }
        
        if (v == null) return NOT_AVAILABLE;

        double d = (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
        return formatDouble(d * 100.0, 2);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String key = "CA_PassRate" + getSuffix(sampleType);
        Object v = rg.specialValues().get(key);
        
        if (v == null && sampleType == SampleTypes.FullSample) {
            v = rg.specialValues().get("CA_PassRate_OOS");
            if (v == null) {
                v = rg.specialValues().get("CA_PassRate_IS");
            }
            if (v == null) {
                v = rg.specialValues().get("CA_PassRate");
            }
        }
        
        if (v == null) return 0.0;
        return (v instanceof Number) ? ((Number) v).doubleValue() * 100.0 : Double.parseDouble(v.toString()) * 100.0;
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
