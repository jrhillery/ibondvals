package com.moneydance.modules.features.ibondvalues;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class CalcTxnList {
    private final TreeMap<YearMonth, List<CalcTxn>> txnListMap = new TreeMap<>();

    /**
     * Append a calculated interest payment transaction record to the end
     * of the list of such records for the transaction's payment month.
     *
     * @param txnRec Calculated interest payment transaction detail record to append
     */
    public void add(CalcTxn txnRec) {

        this.txnListMap.computeIfAbsent(txnRec.payMonth(), k -> new ArrayList<>()).add(txnRec);

    } // end add(CalcTxn)

    /**
     * Retrieve the calculated interest payment transactions for a specified month.
     * When a Series I savings bond turns 5 years old, it receives
     * interest for the prior 3 months, which are no longer lost.
     *
     * @param month Month for the transactions to return
     * @return List of calculated interest payment transaction detail records
     */
    public List<CalcTxn> getForMonth(YearMonth month) {

        return this.txnListMap.get(month);
    } // end getForMonth(YearMonth)

    /**
     * Performs the given action for each transaction in this collection.
     *
     * @param action Action to be performed for each transaction
     */
    public void forEach(Consumer<CalcTxn> action) {

        this.txnListMap.forEach(((month, calcTxns) -> calcTxns.forEach(action)));

    } // end forEach(Consumer<CalcTxn>)

    /**
     * Removes all the transactions of this collection that satisfy the given predicate.
     *
     * @param filter Predicate which returns {@code true} for transactions to be removed
     */
    public void removeIf(Predicate<CalcTxn> filter) {

        this.txnListMap.forEach(((month, calcTxns) -> calcTxns.removeIf(filter)));

    } // end removeIf(Predicate<CalcTxn>)

} // end class CalcTxnList
