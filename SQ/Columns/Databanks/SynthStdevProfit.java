package SQ.Columns.Databanks;

import com.strategyquant.lib.*;
import com.strategyquant.datalib.*;
import com.strategyquant.tradinglib.*;

public class SynthStdevProfit extends DatabankColumn {

    public SynthStdevProfit() {
        super("SynthStdevProfit",
                DatabankColumn.Decimal2,
                ValueTypes.Minimize,
                0,
                0,
                1000000);

        setWidth(140);
        setTooltip("Desviación estándar del Net Profit obtenida en el retest (Custom Analysis).");
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        Object v = rg.specialValues().get("CA_SynthStdevProfit");
        if (v == null) return NOT_AVAILABLE;

        double d = (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
        return formatDouble(d, 2);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        Object v = rg.specialValues().get("CA_SynthStdevProfit");
        if (v == null) return 0.0;
        return (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString());
    }

    private String formatDouble(double v, int decimals) {
        double p = Math.pow(10, decimals);
        return Double.toString(Math.round(v * p) / p);
    }
}
