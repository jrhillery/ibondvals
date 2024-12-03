package com.moneydance.modules.features.ibondvalues;

import java.math.BigDecimal;

/**
 * Data record to hold intermediate balance data while calculating interest transactions.
 */
class IBondBalanceRec {
    private BigDecimal totalBal;
    private BigDecimal eligibleBal;

    /**
     * Sole constructor.
     *
     * @param totalBal    Total balance
     * @param eligibleBal Balance eligible to earn interest
     */
    public IBondBalanceRec(BigDecimal totalBal, BigDecimal eligibleBal) {
        this.totalBal = totalBal;
        this.eligibleBal = eligibleBal;

    } // end constructor

    /**
     * {@return Total balance}
     */
    public BigDecimal totalBal() { return totalBal; }

    /**
     * Set the total balance
     *
     * @param totalBal Total balance to store
     */
    public void totalBal(BigDecimal totalBal) { this.totalBal = totalBal; }

    /**
     * {@return Balance eligible to earn interest}
     */
    public BigDecimal eligibleBal() { return eligibleBal; }

    /**
     * Set the balance eligible to earn interest
     *
     * @param eligibleBal Balance eligible to earn interest to store
     */
    public void eligibleBal(BigDecimal eligibleBal) { this.eligibleBal = eligibleBal; }

} // end class IBondBalanceRec
