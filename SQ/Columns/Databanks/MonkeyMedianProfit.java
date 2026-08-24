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
        setTooltip("Mediana de Net Profit obtenida en la simulacion del Monkey Test para el periodo seleccionado.");
    }

    @Override
    public String getValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        Double v = resolve(rg, sampleType);
        return (v == null) ? NOT_AVAILABLE : formatDouble(v.doubleValue(), 2);
    }

    @Override
    public double getNumericValue(ResultsGroup rg, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        Double v = resolve(rg, sampleType);
        return (v == null) ? 0.0 : v.doubleValue();
    }

    /**
     * Resuelve la mediana del periodo seleccionado en el Databank. La resolucion es estricta: si
     * ese periodo no se ha calculado se devuelve null (columna en N/A) en lugar de caer al valor de
     * otro periodo -- ese fallback era lo que hacia que Full Sample mostrase la mediana del OOS o
     * del IS como si fuera la del total. La unica excepcion es Full Sample, que acepta la clave
     * legacy sin sufijo escrita por versiones anteriores del Custom Analysis, exactamente igual que
     * MonkeyTestColumn y MonkeyTestZScoreColumn.
     */
    private Double resolve(ResultsGroup rg, byte sampleType) {
        Double v = readDouble(rg, "MonkeyTestMedianProfit" + getSuffix(sampleType));
        if (v != null) {
            return v;
        }
        if (sampleType == SampleTypes.FullSample) {
            return readDouble(rg, "MonkeyTestMedianProfit");
        }
        return null;
    }

    private Double readDouble(ResultsGroup rg, String key) {
        try {
            Object v = rg.specialValues().get(key);
            if (v == null) {
                return null;
            }
            if (v instanceof Number) {
                return Double.valueOf(((Number) v).doubleValue());
            }
            String s = v.toString().trim();
            if (s.isEmpty() || "N/A".equals(s)) {
                return null;
            }
            return Double.valueOf(Double.parseDouble(s));
        } catch (Exception e) {
            return null;
        }
    }

    private String getSuffix(byte sampleType) {
        if (sampleType == SampleTypes.InSample) return "_IS";
        if (sampleType == SampleTypes.OutOfSample) return "_OOS";
        if (sampleType == SampleTypes.InSampleValidation) return "_ISV";

        // Partes numeradas: OutOfSample1..10 == 21..30, InSampleValidation1..10 == 41..50.
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
