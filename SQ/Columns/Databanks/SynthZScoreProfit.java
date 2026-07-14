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
        setTooltip("Z-Score del Net Profit original vs media/stdev en 150 sintéticas (Custom Analysis).");
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        Object v = rg.specialValues().get("CA_SynthZScoreProfit");
        if (v == null) return NOT_AVAILABLE;

        double d = (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
        return formatDouble(d, 2);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        Object v = rg.specialValues().get("CA_SynthZScoreProfit");
        if (v == null) return 0.0;
        return (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
    }

    private String formatDouble(double v, int decimals) {
        double p = Math.pow(10, decimals);
        return Double.toString(Math.round(v * p) / p);
    }
}
