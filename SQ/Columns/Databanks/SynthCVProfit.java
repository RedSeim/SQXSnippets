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

    private double calculateCV(ResultsGroup rg) {
        Object meanObj = rg.specialValues().get("CA_SynthMeanProfit");
        Object stdevObj = rg.specialValues().get("CA_SynthStdevProfit");

        if (meanObj == null || stdevObj == null) return -1.0;

        double mean = (meanObj instanceof Number) ? ((Number) meanObj).doubleValue() : Double.parseDouble(meanObj.toString());
        double stdev = (stdevObj instanceof Number) ? ((Number) stdevObj).doubleValue() : Double.parseDouble(stdevObj.toString());

        if (Math.abs(mean) < 1e-9) return -1.0;
        return stdev / Math.abs(mean);
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        double cv = calculateCV(rg);
        if (cv < 0) return NOT_AVAILABLE;
        return formatDouble(cv, 2);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        double cv = calculateCV(rg);
        return (cv < 0) ? 0.0 : cv;
    }

    private String formatDouble(double v, int decimals) {
        double p = Math.pow(10, decimals);
        return Double.toString(Math.round(v * p) / p);
    }
}
