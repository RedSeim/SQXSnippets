package SQ.Columns.Databanks;

import com.strategyquant.lib.*;
import com.strategyquant.tradinglib.*;

import java.util.ArrayList;
import java.util.Collections;

public class Xs extends DatabankColumn {

    public Xs() {
        super("Xs", DatabankColumn.Decimal2, ValueTypes.Maximize, 0, 0, 1);

        setWidth(80);
        setTooltip("Median Excursion Score");
    }

    @Override
    public double compute(SQStats stats, StatsTypeCombination combination, OrdersList ordersList, SettingsMap settings, SQStats statsLong, SQStats statsShort) throws Exception {
        if (ordersList == null || ordersList.isEmpty()) {
            return 0.0;
        }

        ArrayList<Double> xsList = new ArrayList<Double>();

        for (int i = 0; i < ordersList.size(); i++) {
            Order order = ordersList.get(i);

            if (order.isBalanceOrder() || !order.isRealOrder()) {
                continue;
            }

            double mfe = Math.abs(order.PipsMFE);
            double mae = Math.abs(order.PipsMAE);
            double denom = mfe + mae;

            if (denom <= 1e-9) {
                xsList.add(0.5);
            } else {
                xsList.add(mfe / denom);
            }
        }

        if (xsList.isEmpty()) {
            return 0.0;
        }

        Collections.sort(xsList);
        int size = xsList.size();
        double median;

        if (size % 2 == 1) {
            median = xsList.get(size / 2);
        } else {
            median = (xsList.get(size / 2 - 1) + xsList.get(size / 2)) / 2.0;
        }

        return round2(median);
    }
}
