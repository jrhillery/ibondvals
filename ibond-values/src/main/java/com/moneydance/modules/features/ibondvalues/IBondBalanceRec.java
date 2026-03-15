package com.moneydance.modules.features.ibondvalues;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Data class to hold intermediate balance data while calculating interest transactions.
 * This is a class rather than a record so it can have setters that make it modifiable in loops.
 */
class IBondBalanceRec {
    private BigDecimal redemptionVal;
    private BigDecimal eligibleBal;
    private BigDecimal unitVal;
    private YearMonth month;

    /**
     * Sole constructor.
     *
     * @param redemptionVal Redemption value
     * @param unitVal       Unit value for interest calculations
     * @param month         Corresponding month
     */
    public IBondBalanceRec(BigDecimal redemptionVal, BigDecimal unitVal, YearMonth month) {
        this.redemptionVal = redemptionVal;
        this.eligibleBal = redemptionVal;
        this.unitVal = unitVal;
        this.month = month;

    } // end constructor

    /**
     * {@return Redemption value}
     */
    public BigDecimal redemptionVal() { return this.redemptionVal; }

    /**
     * Set the redemption value.
     *
     * @param redemptionVal Redemption value to store
     */
    public void redemptionVal(BigDecimal redemptionVal) { this.redemptionVal = redemptionVal; }

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
