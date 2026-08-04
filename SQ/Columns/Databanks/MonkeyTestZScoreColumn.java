package SQ.Columns.Databanks;

import com.strategyquant.tradinglib.DatabankColumn;
import com.strategyquant.tradinglib.ResultsGroup;
import com.strategyquant.tradinglib.SampleTypes;
import com.strategyquant.tradinglib.ValueTypes;

public class MonkeyTestZScoreColumn extends DatabankColumn {

    public MonkeyTestZScoreColumn() {
        super("Monkey Z-Score",
              DatabankColumn.Decimal2,
              ValueTypes.Maximize,
              0, -10, 10);
        setWidth(80);
        setTooltip("Monkey Test Z-Score for the sample period selected in the Databank: number of standard deviations the strategy profit is above the average monkey profit.");
    }

    @Override
    public String getValue(ResultsGroup results, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String v = resolve(results, sampleType);
        return (v != null) ? v : "N/A";
    }

    @Override
    public double getNumericValue(ResultsGroup results, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        String v = resolve(results, sampleType);
        if (v != null && !"N/A".equals(v)) {
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    @Override
    public String exportValue(ResultsGroup results, String resultKey, byte direction, byte plType, byte sampleType) throws Exception {
        return getValue(results, resultKey, direction, plType, sampleType);
    }

    /**
     * Resuelve el Z-Score del periodo seleccionado en el Databank. Resolución estricta: si ese
     * periodo no se ha calculado se devuelve null (columna en N/A) en lugar de caer al valor de
     * otro periodo. La única excepción es Full Sample, que acepta la clave legacy sin sufijo
     * escrita por versiones anteriores del Custom Analysis.
     */
    private String resolve(ResultsGroup results, byte sampleType) {
        String v = readString(results, "MonkeyTestZScore" + getSuffix(sampleType));
        if (v != null) {
            return v;
        }
        if (sampleType == SampleTypes.FullSample) {
            return readString(results, "MonkeyTestZScore");
        }
        return null;
    }

    private String readString(ResultsGroup results, String key) {
        Object v = results.specialValues().get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.isEmpty() ? null : s;
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
}
