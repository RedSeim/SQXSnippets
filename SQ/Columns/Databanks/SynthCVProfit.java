package SQ.Columns.Databanks;

import com.strategyquant.lib.*;
import com.strategyquant.datalib.*;
import com.strategyquant.tradinglib.*;

public class SynthCVProfit extends DatabankColumn {

    public SynthCVProfit() {
        super("SynthCVProfit",
                DatabankColumn.Decimal2,
                ValueTypes.Minimize,
                0,
                0,
                10);

        setWidth(120);
        setTooltip("Coeficiente de variación de sintéticas: Stdev / |Mean|. Mide inestabilidad del rendimiento.");
    }

    private double calculateCV(ResultsGroup rg, byte sampleType) {
        String suffix = getSuffix(sampleType);
        Object meanObj = rg.specialValues().get("CA_SynthMeanProfit" + suffix);
        Object stdevObj = rg.specialValues().get("CA_SynthStdevProfit" + suffix);

        if ((meanObj == null || stdevObj == null) && sampleType == SampleTypes.FullSample) {
            meanObj = rg.specialValues().get("CA_SynthMeanProfit_OOS");
            stdevObj = rg.specialValues().get("CA_SynthStdevProfit_OOS");
            if (meanObj == null || stdevObj == null) {
                meanObj = rg.specialValues().get("CA_SynthMeanProfit_IS");
                stdevObj = rg.specialValues().get("CA_SynthStdevProfit_IS");
            }
            if (meanObj == null || stdevObj == null) {
                meanObj = rg.specialValues().get("CA_SynthMeanProfit");
                stdevObj = rg.specialValues().get("CA_SynthStdevProfit");
            }
        }

        if (meanObj == null || stdevObj == null) return -1.0;

        double mean = (meanObj instanceof Number) ? ((Number) meanObj).doubleValue() : Double.parseDouble(meanObj.toString());
        double stdev = (stdevObj instanceof Number) ? ((Number) stdevObj).doubleValue() : Double.parseDouble(stdevObj.toString());

        if (Math.abs(mean) < 1e-9) return -1.0;
        return stdev / Math.abs(mean);
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        double cv = calculateCV(rg, sampleType);
        if (cv < 0) return NOT_AVAILABLE;
        return formatDouble(cv, 2);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        double cv = calculateCV(rg, sampleType);
        return (cv < 0) ? 0.0 : cv;
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
