package com.moneydance.modules.features.ibondvalues;

import com.infinitekind.moneydance.model.AbstractTxn;
import com.infinitekind.moneydance.model.Account;
import com.infinitekind.moneydance.model.SplitTxn;
import com.infinitekind.moneydance.model.TransactionSet;
import com.leastlogic.moneydance.util.MdUtil;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static com.infinitekind.moneydance.model.InvestTxnType.DIVIDEND_REINVEST;

/**
 * Utility class to house a list of investment transactions for a Moneydance security account.
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
     * @param txnRec Desired interest payment transaction details
     * @return Optional first matching dividend reinvest destination side transaction
     */
    public Optional<SplitTxn> getMatchingDivReinvestTxn(CalcTxn txnRec) {
        List<AbstractTxn> txns = this.transactions.get(txnRec.payDate());

        if (txns != null) {
            for (AbstractTxn txn : txns) {
                if (txn.getParentTxn().getInvestTxnType() == DIVIDEND_REINVEST
                        && txn instanceof SplitTxn
                        && txnRec.memo().equalsIgnoreCase(txn.getParentTxn().getMemo())) {

                    return Optional.of((SplitTxn) txn);
                }
            }
        }

        return Optional.empty();
    } // end getMatchingDivReinvestTxn(CalcTxn)

    /**
     * @param month Month for the redemptions to return
     * @return List of redemption transactions for the specified month
     */
    public List<SplitTxn> getRedemptionsForMonth(YearMonth month) {
        List<SplitTxn> txns = new ArrayList<>();

        this.transactions.subMap(month.atDay(1), true, month.atEndOfMonth(), true)
                .forEach((date, txnList) -> txnList.forEach(txn -> {

            if (txn.getParentTxn().getInvestTxnType() != DIVIDEND_REINVEST
                    && txn instanceof SplitTxn) {
                txns.add((SplitTxn) txn);
            }
        }));

        return txns;
    } // end getRedemptionsForMonth(YearMonth)

    /**
     * @return Moneydance security account for this transaction
     */
    public Account account() {

        return this.account;
    } // end account()

} // end class InvestTxnList
