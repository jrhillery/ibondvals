package com.moneydance.modules.features.ibondvalues;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Data record to hold interest payment transaction details.
 */
public class InterestTxnRec {
    private LocalDate payDate;
    private final BigDecimal payAmount;
    private final String memo;
    private final BigDecimal startingBal;

    /**
     * Sole constructor.
     *
     * @param payDate     Payment date
     * @param payAmount   Payment amount
     * @param memo        Payment memo
     * @param startingBal Starting balance for month
     */
    public InterestTxnRec(
            LocalDate payDate, BigDecimal payAmount, String memo, BigDecimal startingBal) {
        this.payDate = payDate;
        this.payAmount = payAmount;
        this.memo = memo;
        this.startingBal = startingBal;
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
     * @return Starting balance for month
     */
    public BigDecimal startingBal() { return this.startingBal; }

    /**
     * Defer the payment transaction by the specified number of months.
     *
     * @param monthsToDefer Number of months to defer
     * @param latest        Latest date for deferred payment
     */
    public void deferPayment(int monthsToDefer, LocalDate latest) {
        LocalDate candidate = payDate().plusMonths(monthsToDefer);

        this.payDate = candidate.isBefore(latest) ? candidate : latest;

    } // end deferPayment(int, LocalDate)

} // end class InterestTxnRec
