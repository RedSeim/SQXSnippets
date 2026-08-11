package SQ.Columns.Databanks;

import com.strategyquant.lib.*;
import com.strategyquant.datalib.*;
import com.strategyquant.tradinglib.*;

public class MonkeyMedianProfit extends DatabankColumn {

    public MonkeyMedianProfit() {
        super("MonkeyMedianProfit",
                DatabankColumn.Decimal2,
                ValueTypes.Maximize,
                0,
                0,
                0);

        setWidth(100);
        setTooltip("Mediana de Net Profit obtenida en la simulación del Monkey Test para el periodo seleccionado.");
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String suffix = getSuffix(sampleType);
        Object v = rg.specialValues().get("MonkeyTestMedianProfit" + suffix);
        
        if (v == null && (sampleType == SampleTypes.FullSample || "_Full".equals(suffix))) {
            v = rg.specialValues().get("MonkeyTestMedianProfit_OOS");
            if (v == null) {
                v = rg.specialValues().get("MonkeyTestMedianProfit_IS");
            }
            if (v == null) {
                v = rg.specialValues().get("MonkeyTestMedianProfit");
            }
        }
        
        if (v == null) return NOT_AVAILABLE;

        double d = (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
        return formatDouble(d, 2);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String suffix = getSuffix(sampleType);
        Object v = rg.specialValues().get("MonkeyTestMedianProfit" + suffix);
        
        if (v == null && (sampleType == SampleTypes.FullSample || "_Full".equals(suffix))) {
            v = rg.specialValues().get("MonkeyTestMedianProfit_OOS");
            if (v == null) {
                v = rg.specialValues().get("MonkeyTestMedianProfit_IS");
            }
            if (v == null) {
                v = rg.specialValues().get("MonkeyTestMedianProfit");
            }
        }
        
        if (v == null) return 0.0;
        return (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
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
