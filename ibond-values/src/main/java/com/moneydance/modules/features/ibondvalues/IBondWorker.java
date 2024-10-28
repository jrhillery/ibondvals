package com.moneydance.modules.features.ibondvalues;

import com.infinitekind.moneydance.model.AccountBook;
import com.infinitekind.moneydance.model.CurrencySnapshot;
import com.infinitekind.moneydance.model.CurrencyTable;
import com.infinitekind.moneydance.model.CurrencyType;
import com.leastlogic.moneydance.util.*;
import com.moneydance.apps.md.controller.FeatureModuleContext;

import javax.swing.SwingWorker;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import static com.infinitekind.moneydance.model.Account.AccountType.INVESTMENT;
import static com.leastlogic.moneydance.util.MdUtil.IBOND_TICKER_PREFIX;

public class IBondWorker extends SwingWorker<Boolean, String>
      implements StagedInterface, AutoCloseable {
   private final IBondWindow iBondWindow;
   private final Locale locale;
   private final String extensionName;
   private final IBondImporter importer;
   private final AccountBook book;
   private final CurrencyTable securities;
   private final LocalDate today = LocalDate.now();
   private boolean haveIBondSecurities = false;
   private final CountDownLatch finishedLatch = new CountDownLatch(1);

   private final List<SecurityHandler> priceChanges = new ArrayList<>();

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
      this.locale = iBondWindow.getLocale();
      this.extensionName = extensionName;
      this.importer = new IBondImporter();
      this.book = fmContext.getCurrentAccountBook();
      this.securities = this.book.getCurrencies();
      iBondWindow.setStaged(this);
      iBondWindow.addCloseableResource(this);

   } // end constructor

   /**
    * Add a security handler to our collection.
    *
    * @param handler A deferred update security handler to store
    */
   private void addHandler(SecurityHandler handler) {
      this.priceChanges.add(handler);

   } // end addHandler(SecurityHandler)

   /**
    * Commit any changes to Moneydance.
    *
    * @return Optional summary of the changes committed
    */
   public Optional<String> commitChanges() {
      int numPricesSet = this.priceChanges.size();

      this.priceChanges.forEach(SecurityHandler::applyUpdate);
      this.priceChanges.clear();

      return Optional.of(String.format(this.locale,
         "Recorded %d security price%s", numPricesSet, numPricesSet == 1 ? "" : "s"));
   } // end commitChanges()

   /**
    * @return True when we have uncommitted changes in memory
    */
   public boolean isModified() {

      return !this.priceChanges.isEmpty();
   } // end isModified()

   /**
    * Determine if a given security currently has shares in any investment account.
    *
    * @param security Moneydance security
    * @return true when an investment account contains some shares
    */
   private boolean haveShares(CurrencyType security) {
      String securityName = security.getName();

      return MdUtil.getAccounts(this.book, INVESTMENT)
         .anyMatch(invAccount -> MdUtil.getSubAccountByName(invAccount, securityName).stream()
         .anyMatch(securityAccount -> securityAccount.getUserBalance() != 0));
   } // end haveShares(CurrencyType)

   /**
    * Store a handler for a deferred price quote if it differs Moneydance data.
    *
    * @param ssList    Snapshot list to use
    * @param priceDate Date for this price quote
    * @param price     Security price for the specified date
    * @param current   The price is current
    */
   private void storePriceQuoteIfDiff(
           SnapshotList ssList, LocalDate priceDate, BigDecimal price, boolean current) {
      CurrencyType security = ssList.getSecurity();
      int priceDateInt = MdUtil.convLocalToDateInt(priceDate);
      Optional<CurrencySnapshot> snapshot = ssList.getSnapshotForDate(priceDateInt);
      BigDecimal oldPrice = snapshot.map(SnapshotList::getPrice).orElse(BigDecimal.ONE);

      // store this quote if it differs
      if (snapshot.isEmpty() || priceDateInt != snapshot.get().getDateInt()
              || price.compareTo(oldPrice) != 0) {
         NumberFormat priceFmt = MdUtil.getCurrencyFormat(this.locale, oldPrice, price);
         display(String.format(this.locale, "Set %s (%s) price to %s for %tF",
            security.getName(), security.getTickerSymbol(),
            priceFmt.format(price), priceDate));
         SecurityHandler sh = new SecurityHandler(ssList);

         if (!current)
            sh.priceNotCurrent();
         addHandler(sh.storeNewPrice(price.doubleValue(), priceDateInt));
      }

   } // end storePriceQuoteIfDiff(SnapshotList, LocalDate, BigDecimal, boolean)

   /**
    * Validate the current price.
    *
    * @param ssList The list of snapshots to use
    */
   private void validateTodaysPrice(SnapshotList ssList) {

      ssList.getTodaysSnapshot().ifPresent(currentSnapshot ->
              MdUtil.getAndValidateCurrentSnapshotPrice(ssList.getSecurity(),
                      currentSnapshot, this.locale, this::display));

   } // end validateTodaysPrice(SnapshotList)

   /**
    * Check if this security has a ticker symbol for Series I savings bonds and if shares
    * exist in an investment account. If so, store any new prices for this security.
    *
    * @param security Moneydance security
    * @throws MduException Problem retrieving or interpreting TreasuryDirect spreadsheet
    */
   private void storeNewIBondPrices(CurrencyType security) throws MduException {
      String ticker = security.getTickerSymbol();

      if (MdUtil.isIBondTickerPrefix(ticker) && haveShares(security)) {
         try {
            SnapshotList ssList = new SnapshotList(security);
            validateTodaysPrice(ssList);
            TreeMap<LocalDate, BigDecimal> prices =
                    this.importer.getIBondPrices(ticker, MdLog::debug);
            LocalDate priceDateForToday = prices.floorKey(this.today);

            prices.forEach((date, price) ->
               storePriceQuoteIfDiff(ssList, date, price, date.equals(priceDateForToday)));

            this.haveIBondSecurities = true;
         } catch (MduExcepcionito e) {
            display(e.getLocalizedMessage());
         }
      }

   } // end storeNewIBondPrices(CurrencyType)

   /**
    * Long-running routine to pull I bond interest rates from a remote
    * site and derive I bond securities prices. Runs on worker thread.
    *
    * @return true when changes have been detected
    */
   protected Boolean doInBackground() {
      try {
         List<CurrencyType> securityList = this.securities.getAllCurrencies();

         for (CurrencyType security : securityList) {
            storeNewIBondPrices(security);
         } // end for each security

         if (!this.haveIBondSecurities) {
            display("Unable to locate any security with an I bond ticker symbol",
               "Such ticker symbols should start with '" + IBOND_TICKER_PREFIX
                  + "' (in any case) followed by the<br>year followed by a 2 digit "
                  + "month number in the format " + IBOND_TICKER_PREFIX + "YYYYMM",
               "Examples: " + IBOND_TICKER_PREFIX + "201901, "
                  + IBOND_TICKER_PREFIX.toUpperCase() + "202212, "
                  + IBOND_TICKER_PREFIX.toLowerCase() + "202304");
         } else if (!isModified()) {
            display("No new price history data found");
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
