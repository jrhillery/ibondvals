package com.moneydance.modules.features.ibondvalues;

import com.infinitekind.moneydance.model.CurrencyType;
import com.leastlogic.mdimport.util.SecurityHandler;
import com.leastlogic.moneydance.util.MduException;
import com.leastlogic.moneydance.util.StagedInterface;
import com.moneydance.apps.md.controller.FeatureModuleContext;

import javax.swing.SwingWorker;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

public class IBondWorker extends SwingWorker<Boolean, String>
      implements StagedInterface, AutoCloseable {
   private final IBondWindow iBondWindow;
   private final String extensionName;
   private final IBondImporter importer;
   private final CountDownLatch finishedLatch = new CountDownLatch(1);

   private final LinkedHashMap<CurrencyType, SecurityHandler> priceChanges = new LinkedHashMap<>();

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
      iBondWindow.setStaged(this);
      iBondWindow.addCloseableResource(this);

   } // end constructor

   /**
    * Commit any changes to Moneydance.
    *
    * @return A summary of the changes committed
    */
   public String commitChanges() {
      int numPricesSet = this.priceChanges.size();

      for (SecurityHandler sHandler : this.priceChanges.values()) {
         sHandler.applyUpdate();
      }

      return String.format(this.iBondWindow.getLocale(),
         "Changed %d security price%s.", numPricesSet, numPricesSet == 1 ? "" : "s");
   } // end commitChanges()

   /**
    * @return True when we have uncommitted changes in memory
    */
   public boolean isModified() {

      return !this.priceChanges.isEmpty();
   } // end isModified()

   /**
    * Long-running routine to pull I bond interest rates from a remote
    * site and derive I bond securities prices. Runs on worker thread.
    *
    * @return true when changes have been detected
    */
   protected Boolean doInBackground() {
      try {
         NavigableMap<LocalDate, IBondRateRec> iBondRates = this.importer.getIBondRates();
         LocalDate issueDate = IBondImporter.getDateForTicker("ibond202304");
         List<PriceRec> iBondPrices = IBondImporter.getIBondPrices(issueDate, iBondRates);

         return isModified();
      } catch (Throwable e) {
         display(e.toString());
         e.printStackTrace(System.err);

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
         this.iBondWindow.addText(e.toString());
         e.printStackTrace(System.err);
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
   } // end stopExecute(String)

   /**
    * Close this resource, relinquishing any underlying resources.
    * Cancel this worker, wait for it to complete, discard its results and close odsAcc.
    */
   public void close() {
      if (getState() != StateValue.DONE) {
         System.err.format(this.iBondWindow.getLocale(),
            "Cancelling running %s invocation.%n", this.extensionName);
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
