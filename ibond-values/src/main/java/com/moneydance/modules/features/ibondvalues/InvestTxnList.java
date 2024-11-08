package com.moneydance.modules.features.ibondvalues;

import com.infinitekind.moneydance.model.AbstractTxn;
import com.infinitekind.moneydance.model.Account;
import com.infinitekind.moneydance.model.SplitTxn;
import com.infinitekind.moneydance.model.TransactionSet;
import com.leastlogic.moneydance.util.MdUtil;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static com.infinitekind.moneydance.model.InvestTxnType.DIVIDEND_REINVEST;

/**
 * Utility class to house a list of investment transactions for a Moneydance account.
 */
public class InvestTxnList {

    private final Account account;
    private final TreeMap<LocalDate, List<AbstractTxn>> transactions = new TreeMap<>();

    /**
     * Sole constructor.
     *
     * @param txnSet  Set of all transactions
     * @param account Moneydance security account for this instance
     */
    public InvestTxnList(TransactionSet txnSet, Account account) {
        this.account = account;

        txnSet.getTxnsForAccount(account).forEach(txn -> this.transactions
            .computeIfAbsent(MdUtil.convDateIntToLocal(txn.getDateInt()), k -> new ArrayList<>())
            .add(txn));

    } // end constructor

    /**
     * @param date Desired transaction date
     * @return Optional first dividend reinvest transaction on desired date
     */
    public Optional<SplitTxn> getDivReinvestTxnForDate(LocalDate date) {
        List<AbstractTxn> txns = this.transactions.get(date);

        if (txns != null) {
            for (AbstractTxn txn : txns) {
                if (txn.getParentTxn().getInvestTxnType() == DIVIDEND_REINVEST
                        && txn instanceof SplitTxn) {

                    return Optional.of((SplitTxn) txn);
                }
            }
        }

        return Optional.empty();
    } // end getDivReinvestTxnForDate(LocalDate)

    /**
     * @return Moneydance security account for this transaction
     */
    public Account account() {

        return this.account;
    } // end account()

} // end class InvestTxnList
