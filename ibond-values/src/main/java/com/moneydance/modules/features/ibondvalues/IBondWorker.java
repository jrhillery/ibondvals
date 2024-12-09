package com.moneydance.modules.features.ibondvalues;

import com.infinitekind.moneydance.model.*;
import com.leastlogic.moneydance.util.*;
import com.moneydance.apps.md.controller.FeatureModuleContext;

import javax.swing.SwingWorker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.infinitekind.moneydance.model.Account.AccountType.INVESTMENT;
import static com.leastlogic.moneydance.util.MdUtil.IBOND_TICKER_PREFIX;

public class IBondWorker extends SwingWorker<Boolean, String>
      implements StagedInterface, AutoCloseable {
   private final IBondWindow iBondWindow;
   private final String extensionName;
   private final IBondImporter importer;
   private final AccountBook book;
   private final CurrencyTable securities;
   private final TransactionSet txnSet;
   private boolean haveIBondSecurities = false;
   private final CountDownLatch finishedLatch = new CountDownLatch(1);

   private final List<TxnHandler> interestTransactions = new ArrayList<>();

   /**
    * Sole constructor.
    *
    * @param iBondWindow Our I bond window
    * @param extensionName This extension's name
    * @param fmContext Moneydance context
    */
   public IBondWorker(IBondWindow iBondWindow, String extensionName,
                      FeatureModuleContext fmContext) throws MduException {
      super();
      this.iBondWindow = iBondWindow;
      this.extensionName = extensionName;
      this.importer = new IBondImporter();
      this.book = fmContext.getCurrentAccountBook();
      this.securities = this.book.getCurrencies();
      this.txnSet = this.book.getTransactionSet();
      iBondWindow.setStaged(this);
      iBondWindow.addCloseableResource(this);

   } // end constructor

   /**
    * Add an interest payment transaction handler to our collection.
    *
    * @param handler A deferred update interest payment transaction handler to store
    */
   private void addHandler(TxnHandler handler) {
      this.interestTransactions.add(handler);

   } // end addHandler(TxnHandler)

   /**
    * Commit any changes to Moneydance.
    *
    * @return Optional summary of the changes committed
    */
   public Optional<String> commitChanges() {
      int numInterestTxns = this.interestTransactions.size();

      this.interestTransactions.forEach(TxnHandler::applyUpdate);
      this.interestTransactions.clear();

      return Optional.of("Recorded %d interest payment transaction%s"
         .formatted(numInterestTxns, numInterestTxns == 1 ? "" : "s"));
   } // end commitChanges()

   /**
    * {@return True when we have uncommitted changes in memory}
    */
   public boolean isModified() {

      return !this.interestTransactions.isEmpty();
   } // end isModified()

   /**
    * Store a handler for a deferred transaction if it differs from Moneydance data.
    *
    * @param txn           Interest payment transaction details
    * @param investAccount Investment account
    * @param investTxns    List of investment transactions for this transaction's security account
    */
   private void storeInterestTxnIfDiff(
         CalcTxn txn, Account investAccount, InvestTxnList investTxns) {
      Account secAccount = investTxns.account();
      Optional<SplitTxn> divTxn = investTxns.getMatchingDivReinvestTxn(txn);

      if (divTxn.isEmpty()) {
         if (!this.isModified()) {
            Account category = AccountUtil.getDefaultCategoryForAcct(investAccount);
            display("Will use category %s (the default) for new interest payments in %s"
               .formatted(category.getAccountName(), investAccount.getAccountName()));
         }
         // store a new transaction
         display("On %tF %s:%s pay %s for %s, bal %.2f".formatted(txn.payDate(),
            investAccount.getAccountName(), secAccount.getAccountName(),
            txn.payAmount(), txn.memo(), txn.endingBal()));

         addHandler(new TxnHandler(this.book, investAccount, secAccount, txn));
      } else {
         // verify transaction information
         BigDecimal oldAmount = MdUtil.getTxnAmount(divTxn.get());

         if (txn.payAmount().compareTo(oldAmount) != 0) {
            display("Found a different interest amount on %s %s:%s: have %s, calculate %s for %s"
               .formatted(txn.payDate(), investAccount.getAccountName(),
               secAccount.getAccountName(), oldAmount, txn.payAmount(), txn.memo()));
         }
         oldAmount = MdUtil.getBalanceAsOf(this.book, secAccount, txn.payMonth().atEndOfMonth());

         if (txn.endingBal().compareTo(oldAmount) != 0) {
            display("Found a different ending balance for %s in %s:%s: have %s, calculate %s"
               .formatted(txn.payMonth(), investAccount.getAccountName(),
               secAccount.getAccountName(), oldAmount, txn.endingBal()));
         }
      }

   } // end storeInterestTxnIfDiff(CalcTxn, Account, InvestTxnList)

   /**
    * Provide total net deposits and redemptions for a month.
    *
    * @param month         Month to total
    * @param investAccount Investment account
    * @param txnList       List of investment transactions for a securities account
    * @return Sum of deposits and redemptions in the given month
    */
   private BigDecimal changeForMonth(
         YearMonth month, Account investAccount, InvestTxnList txnList) {
      List<SplitTxn> changes = txnList.getChangesForMonth(month);

      long amountTotal = changes.stream().mapToLong(SplitTxn::getAmount).sum();
      int decimalPlaces = investAccount.getCurrencyType().getDecimalPlaces();
      BigDecimal changeTotal = BigDecimal.valueOf(amountTotal).movePointLeft(decimalPlaces);

      if (!changes.isEmpty()) {
         MdLog.debug(() -> changes.stream().map(txn -> "%s on %s"
            .formatted(MdUtil.getTxnAmount(txn), MdUtil.convDateIntToLocal(txn.getDateInt())))
            .collect(Collectors.joining("; ", "From %s:%s add "
               .formatted(investAccount.getAccountName(), txnList.account().getAccountName()),
               " => %s for the month".formatted(changeTotal))));
      }

      return changeTotal;
   } // end changeForMonth(YearMonth, Account, InvestTxnList)

   /**
    * Discard future transactions -- they would change if redemptions occur.
    *
    * @param iBondIntTxns Collection of interest payment transactions
    */
   private static void discardFutureTxns(CalcTxnList iBondIntTxns) {
      YearMonth thisMonth = YearMonth.now();

      iBondIntTxns.removeIf(ibIntTxn -> ibIntTxn.payMonth().isAfter(thisMonth));

   } // end discardFutureTxns(CalcTxnList)

   /**
    * Check if this security has a ticker symbol for Series I savings bonds and if shares
    * exist in an investment account. If so, store any new interest payments for this security.
    *
    * @param security Moneydance security
    */
   private void storeNewIBondTxns(CurrencyType security) {
      String ticker = security.getTickerSymbol();
      String secName = security.getName();

      if (MdUtil.isIBondTickerPrefix(ticker)) {
         Consumer<Supplier<String>> displayRates = MdLog::debug;
         try {
            LocalDate endOfIssueMonth = IBondImporter.getDateForTicker(ticker).atEndOfMonth();
            Iterable<Account> invAccounts = MdUtil.getAccounts(this.book, INVESTMENT)::iterator;

            for (Account invAccount : invAccounts) {
               Optional<Account> secAccount = MdUtil.getSubAccountByName(invAccount, secName);

               if (secAccount.isPresent()) {
                  BigDecimal balance =
                     MdUtil.getBalanceAsOf(this.book, secAccount.get(), endOfIssueMonth);

                  if (balance.signum() > 0) {
                     InvestTxnList txnList = new InvestTxnList(this.txnSet, secAccount.get());
                     CalcTxnList txns = this.importer.calcIBondInterestTxns(ticker,
                        month -> changeForMonth(month, invAccount, txnList), displayRates);

                     // avoid repeat display of interest rates
                     displayRates = msgSupplier -> {};
                     discardFutureTxns(txns);

                     txns.forEach(txn -> storeInterestTxnIfDiff(txn, invAccount, txnList));

                     this.haveIBondSecurities = true;
                  }
               }
            } // for investment accounts
         } catch (MduExcepcionito e) {
            display(e.getLocalizedMessage());
         }
      }

   } // end storeNewIBondTxns(CurrencyType)

   /**
    * Long-running routine to pull I bond interest rates from a remote site and
    * derive I bond securities interest payment transactions. Runs on worker thread.
    *
    * @return true when changes have been detected
    */
   protected Boolean doInBackground() {
      try {
         this.importer.loadIBondRates();
         this.securities.getAllCurrencies().forEach(this::storeNewIBondTxns);

         if (!this.haveIBondSecurities) {
            display("Unable to locate any security with an I bond ticker symbol",
               "Such ticker symbols should start with '" + IBOND_TICKER_PREFIX
                  + "' (in any case) followed by the<br>year followed by a 2 digit "
                  + "month number in the format " + IBOND_TICKER_PREFIX + "YYYYMM",
               "Examples: " + IBOND_TICKER_PREFIX + "201901, "
                  + IBOND_TICKER_PREFIX.toUpperCase() + "202212, "
                  + IBOND_TICKER_PREFIX.toLowerCase() + "202304");
         } else if (!isModified()) {
            display("No new interest payment data found");
         }

         return isModified();
      } catch (Throwable e) {
         MdLog.all("Problem running %s".formatted(this.extensionName), e);
         display(e.toString());

         return false;
      } finally {
         this.finishedLatch.countDown();
      }
   } // end doInBackground()

   /**
    * Enable the commit button if we have changes.
    * Runs on event dispatch thread after the doInBackground method is finished.
    */
   protected void done() {
      try {
         this.iBondWindow.enableCommitButton(get());
      } catch (CancellationException e) {
         // ignore
      } catch (Exception e) {
         MdLog.all("Problem enabling commit button", e);
         this.iBondWindow.addText(e.toString());
      }

   } // end done()

   /**
    * Runs on worker thread.
    *
    * @param msgs Messages to display
    */
   public void display(String... msgs) {
      publish(msgs);

   } // end display(String...)

   /**
    * Runs on event dispatch thread.
    *
    * @param chunks Messages to process
    */
   protected void process(List<String> chunks) {
      if (!isCancelled()) {
         for (String msg: chunks) {
            this.iBondWindow.addText(msg);
         }
      }

   } // end process(List<String>)

   /**
    * Stop a running execution.
    *
    * @return null
    */
   public IBondWorker stopExecute() {
      close();

      // we no longer need closing
      this.iBondWindow.removeCloseableResource(this);

      return null;
   } // end stopExecute()

   /**
    * Close this resource, relinquishing any underlying resources.
    * Cancel this worker, wait for it to complete and discard its results.
    */
   public void close() {
      if (getState() != StateValue.DONE) {
         MdLog.all("Cancelling running %s invocation".formatted(this.extensionName));
         cancel(false);

         // wait for prior worker to complete
         try {
            this.finishedLatch.await();
         } catch (InterruptedException e) {
            // ignore
         }

         // discard results and some exceptions
         try {
            get();
         } catch (CancellationException | InterruptedException | ExecutionException e) {
            // ignore
         }
      }

   } // end close()

} // end class IBondWorker
