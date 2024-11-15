package com.moneydance.modules.features.ibondvalues;

import com.infinitekind.moneydance.model.*;
import com.leastlogic.moneydance.util.MdUtil;

import java.math.BigDecimal;

import static com.infinitekind.moneydance.model.InvestTxnType.DIVIDEND_REINVEST;

/**
 * Handles deferred creation of interest payment transactions.
 */
public class TxnHandler {
    private final AccountBook book;
    private final Account investAccount;
    private final Account securityAccount;
    private final CalcTxn txnRec;

    /**
     * Sole constructor.
     *
     * @param book            The root account for all transactions
     * @param investAccount   Investment account
     * @param securityAccount Investment subaccount for security generating interest
     * @param txnRec          Interest payment transaction details
     */
    public TxnHandler(AccountBook book, Account investAccount,
                      Account securityAccount, CalcTxn txnRec) {
        this.book = book;
        this.investAccount = investAccount;
        this.securityAccount = securityAccount;
        this.txnRec = txnRec;

    } // end constructor

    /**
     * @param value   Decimal value to convert
     * @param account Account where value will reside
     * @return Long integer suitable for the specified account
     */
    private long asLong(BigDecimal value, Account account) {
        int decimalPlaces = account.getCurrencyType().getDecimalPlaces();

        return value.movePointRight(decimalPlaces).longValueExact();
    } // end asLong(BigDecimal, Account)

    /**
     * Apply the stored update by creating a parent transaction in the
     * investment account with splits for a category and a security.
     */
    public void applyUpdate() {
        ParentTxn pTxn = new ParentTxn(this.book);
        pTxn.setEditingMode();
        pTxn.setAccount(this.investAccount);

        InvestFields invFields = new InvestFields();
        invFields.txnType = DIVIDEND_REINVEST;
        invFields.date = MdUtil.convLocalToDateInt(this.txnRec.payDate());
        invFields.taxDate = invFields.date;
        invFields.payee = "US Dept. of the Treasury";
        invFields.memo = this.txnRec.memo();
        invFields.shares = asLong(this.txnRec.payAmount(), this.securityAccount);
        invFields.hasShares = true;
        invFields.amount = asLong(this.txnRec.payAmount(), this.investAccount);
        invFields.hasAmount = true;
        invFields.category = AccountUtil.getDefaultCategoryForAcct(this.investAccount);
        invFields.hasCategory = true;
        invFields.price = 1.0;
        invFields.hasPrice = true;
        invFields.security = this.securityAccount;
        invFields.hasSecurity = true;
        invFields.storeFields(pTxn);
        pTxn.syncItem();

    } // end applyUpdate()

} // end class TxnHandler
