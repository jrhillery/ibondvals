package com.moneydance.modules.features.ibondvalues;

import java.math.BigDecimal;

public class TestIBondValues {

    @SuppressWarnings("ConstantValue")
    public static void main(String[] args) {
        try {
            IBondImporter importer = new IBondImporter();
            importer.loadIBondRates();
            boolean withdraw = false;
            CalcTxnList iBondIntTxns = importer.calcIBondInterestTxns("IBond202312",
                    month -> switch (month.toString()) {
                        case "2023-12" -> BigDecimal.valueOf(10000);
                        case "2024-07" -> withdraw ? BigDecimal.valueOf(-1221.00) : BigDecimal.ZERO;
                        case "2024-11" -> withdraw ? BigDecimal.valueOf(-250.00) : BigDecimal.ZERO;
                        default -> BigDecimal.ZERO;
                    }, rates -> System.out.println(rates.get()));

            iBondIntTxns.forEach(ibIntTxn -> System.out.format("On %s pay %s for %s, balance %s%n",
                    ibIntTxn.payDate(), ibIntTxn.payAmount(), ibIntTxn.memo(), ibIntTxn.endingBal()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    } // end main(String[])

} // end class TestIBondValues
