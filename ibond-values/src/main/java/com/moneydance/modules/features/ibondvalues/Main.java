package com.moneydance.modules.features.ibondvalues;

import com.moneydance.apps.md.controller.FeatureModule;

/**
 * Module to pull I bond interest rates from a remote site and derive I bond securities prices.
 */
@SuppressWarnings("unused")
public class Main extends FeatureModule implements AutoCloseable {
   private IBondWindow iBondWindow = null;
   private IBondWorker iBondWorker = null;

   /**
    * Register this module to be invoked via the Extensions menu.
    *
    * @see com.moneydance.apps.md.controller.FeatureModule#init()
    */
   public void init() {
      getContext().registerFeature(this, "do:i:bond:values", null, getName());

   } // end init()

   /**
    * This is called when this extension is invoked.
    *
    * @see com.moneydance.apps.md.controller.FeatureModule#invoke(java.lang.String)
    */
   public void invoke(String uri) {
      System.err.format("%s invoked with uri [%s].%n", getName(), uri);

      try {
         if (this.iBondWorker != null) {
            this.iBondWorker.stopExecute();
         }
         showConsole();
         this.iBondWindow.clearText();

         // SwingWorker instances are not reusable, so make a new one
         this.iBondWorker = new IBondWorker(this.iBondWindow, getName(), getContext());
         this.iBondWorker.execute();
      } catch (Throwable e) {
         handleException(e);
      }

   } // end invoke(String)

   private void handleException(Throwable e) {
      this.iBondWindow.addText(e.toString());
      this.iBondWindow.enableCommitButton(false);
      e.printStackTrace(System.err);

   } // end handleException(Throwable)

   /**
    * Stop execution, close our console window and release resources.
    */
   public synchronized void cleanup() {
      if (this.iBondWindow != null)
         this.iBondWindow = this.iBondWindow.goAway();

      if (this.iBondWorker != null)
         this.iBondWorker = this.iBondWorker.stopExecute();

   } // end cleanup()

   public String getName() {

      return "I Bond Values";
   } // end getName()

   /**
    * Show our console window.
    */
   private synchronized void showConsole() {
      if (this.iBondWindow == null) {
         this.iBondWindow = new IBondWindow(getName(),
            getContext().getCurrentAccountBook().getLocalStorage());
         this.iBondWindow.addCloseableResource(this);
         this.iBondWindow.setVisible(true);
      } else {
         this.iBondWindow.setVisible(true);
         this.iBondWindow.toFront();
         this.iBondWindow.requestFocus();
      }

   } // end showConsole()

   /**
    * Closes this resource, relinquishing any underlying resources.
    */
   public void close() {
      this.iBondWindow = null;
      this.iBondWorker = null;

   } // end close()

} // end class Main
