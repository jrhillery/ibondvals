package com.moneydance.modules.features.ibondvalues;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Data record to hold intermediate balance data while calculating interest transactions.
 */
class IBondBalanceRec {
    private BigDecimal totalBal;
    private BigDecimal eligibleBal;
    private BigDecimal unitVal;
    private YearMonth month;

    /**
     * Sole constructor.
     *
     * @param totalBal    Total balance
     * @param eligibleBal Balance eligible to earn interest
     * @param unitVal     Unit value for interest calculations
     * @param month       Corresponding month
     */
    public IBondBalanceRec(BigDecimal totalBal, BigDecimal eligibleBal,
                           BigDecimal unitVal, YearMonth month) {
        this.totalBal = totalBal;
        this.eligibleBal = eligibleBal;
        this.unitVal = unitVal;
        this.month = month;

    } // end constructor

    /**
     * {@return Total balance}
     */
    public BigDecimal totalBal() { return this.totalBal; }

    /**
     * Set the total balance.
     *
     * @param totalBal Total balance to store
     */
    public void totalBal(BigDecimal totalBal) { this.totalBal = totalBal; }

    /**
     * {@return Balance eligible to earn interest}
     */
    public BigDecimal eligibleBal() { return this.eligibleBal; }

    /**
     * Set the balance eligible to earn interest.
     *
     * @param eligibleBal Balance eligible to earn interest to store
     */
    public void eligibleBal(BigDecimal eligibleBal) { this.eligibleBal = eligibleBal; }

    /**
     * {@return Unit value for interest calculations}
     */
    public BigDecimal unitVal() { return this.unitVal; }

    /**
     * Set the unit value for interest calculations.
     *
     * @param unitVal Unit value for interest calculations to store
     */
    public void unitVal(BigDecimal unitVal) { this.unitVal = unitVal; }

    /**
     * {@return Corresponding month}
     */
    public YearMonth month() { return this.month; }

    /**
     * Set the corresponding month.
     *
     * @param month Corresponding month to store
     */
    public void month(YearMonth month) { this.month = month; }

} // end class IBondBalanceRec
