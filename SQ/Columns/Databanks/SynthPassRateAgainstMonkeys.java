package SQ.Columns.Databanks;

import com.strategyquant.lib.*;
import com.strategyquant.datalib.*;
import com.strategyquant.tradinglib.*;

public class SynthPassRateAgainstMonkeys extends DatabankColumn {

    public SynthPassRateAgainstMonkeys() {
        super("SynthPassRateAgainstMonkeys",
                DatabankColumn.Decimal2Pct,
                ValueTypes.Maximize,
                0,
                0,
                100);

        setWidth(100);
        setTooltip("Pass Rate vs Monkeys: Porcentaje de simulaciones sintéticas con beneficio estrictamente mayor que la mediana del Monkey Test.");
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String key = "CA_PassRateAgainstMonkeys" + getSuffix(sampleType);
        Object v = rg.specialValues().get(key);
        
        if (v == null && sampleType == SampleTypes.FullSample) {
            v = rg.specialValues().get("CA_PassRateAgainstMonkeys_OOS");
            if (v == null) {
                v = rg.specialValues().get("CA_PassRateAgainstMonkeys_IS");
            }
            if (v == null) {
                v = rg.specialValues().get("CA_PassRateAgainstMonkeys");
            }
        }
        
        if (v == null) return NOT_AVAILABLE;

        double d = (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
        return formatDouble(d * 100.0, 2);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String key = "CA_PassRateAgainstMonkeys" + getSuffix(sampleType);
        Object v = rg.specialValues().get(key);
        
        if (v == null && sampleType == SampleTypes.FullSample) {
            v = rg.specialValues().get("CA_PassRateAgainstMonkeys_OOS");
            if (v == null) {
                v = rg.specialValues().get("CA_PassRateAgainstMonkeys_IS");
            }
            if (v == null) {
                v = rg.specialValues().get("CA_PassRateAgainstMonkeys");
            }
        }
        
        if (v == null) return 0.0;
        return (v instanceof Number) ? ((Number) v).doubleValue() * 100.0 : Double.parseDouble(v.toString()) * 100.0;
    }

    private String getSuffix(byte sampleType) {
        if (sampleType == SampleTypes.InSample) return "_IS";
        if (sampleType == SampleTypes.OutOfSample) return "_OOS";
        if (sampleType == SampleTypes.InSampleValidation) return "_ISV";

        if (sampleType > SampleTypes.OutOfSample && sampleType <= (byte) (SampleTypes.OutOfSample + 10)) {
            return "_OOS" + (sampleType - SampleTypes.OutOfSample);
        }
        if (sampleType > SampleTypes.InSampleValidation && sampleType <= (byte) (SampleTypes.InSampleValidation + 10)) {
            return "_ISV" + (sampleType - SampleTypes.InSampleValidation);
        }

        return "_Full";
    }

    private String formatDouble(double v, int decimals) {
        double p = Math.pow(10, decimals);
        return Double.toString(Math.round(v * p) / p);
    }
}
