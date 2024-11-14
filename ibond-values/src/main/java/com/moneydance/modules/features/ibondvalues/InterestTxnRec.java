package com.moneydance.modules.features.ibondvalues;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Data record to hold interest payment transaction details.
 */
public class InterestTxnRec {
    private final LocalDate payDate;
    private final BigDecimal payAmount;
    private final String memo;
    private BigDecimal endingBal = BigDecimal.ZERO;

    /**
     * Sole constructor.
     *
     * @param payDate   Payment date
     * @param payAmount Payment amount
     * @param memo      Payment memo
     */
    public InterestTxnRec(LocalDate payDate, BigDecimal payAmount, String memo) {
        this.payDate = payDate;
        this.payAmount = payAmount;
        this.memo = memo;

    } // end constructor

    /**
     * @return Payment date
     */
    public LocalDate payDate() { return this.payDate; }

    /**
     * @return Payment amount
     */
    public BigDecimal payAmount() { return this.payAmount; }

    /**
     * @return Payment memo
     */
    public String memo() { return this.memo; }

    /**
     * @return Ending balance for month
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

} // end class InterestTxnRec
