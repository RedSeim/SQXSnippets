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
            v = rg.specialValues().get("CA_SynthMeanProfit_OOS");
            if (v == null) {
                v = rg.specialValues().get("CA_SynthMeanProfit_IS");
            }
            if (v == null) {
                v = rg.specialValues().get("CA_SynthMeanProfit");
            }
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
            v = rg.specialValues().get("CA_SynthMeanProfit_OOS");
            if (v == null) {
                v = rg.specialValues().get("CA_SynthMeanProfit_IS");
            }
            if (v == null) {
                v = rg.specialValues().get("CA_SynthMeanProfit");
            }
        }
        
        if (v == null) return 0.0;
        return (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
    }

    private String getSuffix(byte sampleType) {
        if (sampleType == SampleTypes.InSample) return "_IS";
        if (sampleType == SampleTypes.OutOfSample) return "_OOS";
        if (sampleType == SampleTypes.InSampleValidation) return "_ISV";

        // Partes numeradas: OutOfSample1..10 == 21..30, InSampleValidation1..10 == 41..50.
        // Sin estas ramas caerían en el "_Full" final y la columna mostraría los valores de
        // Full Sample como si fueran los de la parte seleccionada.
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
