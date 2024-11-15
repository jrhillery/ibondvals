package com.moneydance.modules.features.ibondvalues;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Data record to hold calculated interest payment transaction details.
 */
public class CalcTxn {
    private final YearMonth payMonth;
    private final LocalDate payDate;
    private final BigDecimal payAmount;
    private final String memo;
    private BigDecimal endingBal = BigDecimal.ZERO;

    /**
     * Sole constructor.
     *
     * @param payMonth  Payment month
     * @param payAmount Payment amount
     * @param memo      Payment memo
     */
    public CalcTxn(YearMonth payMonth, BigDecimal payAmount, String memo) {
        this.payMonth = payMonth;
        this.payDate = payMonth.atDay(1);
        this.payAmount = payAmount;
        this.memo = memo;

    } // end constructor

    /**
     * {@return Payment month}
     */
    public YearMonth payMonth() { return this.payMonth; }

    /**
     * {@return Payment date}
     */
    public LocalDate payDate() { return this.payDate; }

    /**
     * {@return Payment amount}
     */
    public BigDecimal payAmount() { return this.payAmount; }

    /**
     * {@return Payment memo}
     */
    public String memo() { return this.memo; }

    /**
     * {@return Ending balance for month}
     */
    public BigDecimal endingBal() { return this.endingBal; }

    /**
     * Set the ending balance for month
     *
     * @param endingBal Ending balance to store
     */
    public void endingBal(BigDecimal endingBal) { this.endingBal = endingBal; }

    /**
     * {@return a string representation of this instance}
     */
    public String toString() {

        return "%s pay %s for %s".formatted(payDate(), payAmount(), memo());
    }

} // end class CalcTxn
