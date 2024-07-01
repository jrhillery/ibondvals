package com.moneydance.modules.features.ibondvalues;

import com.leastlogic.moneydance.util.StagedInterface;
import com.leastlogic.swing.util.HTMLPane;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.util.ArrayDeque;

import static javax.swing.GroupLayout.DEFAULT_SIZE;

public class IBondWindow extends JFrame {
   private JButton btnCommit;
   private HTMLPane pnOutputLog;
   private StagedInterface staged = null;
   private final ArrayDeque<AutoCloseable> closeableResources = new ArrayDeque<>();

   /**
    * Create the frame.
    */
   public IBondWindow() throws HeadlessException {
      super("I bond values");
      initComponents();
      wireEvents();

   } // end constructor

   /**
    * Initialize swing components.
    */
   private void initComponents() {
      setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
      setSize(705, 436);
      JPanel contentPane = new JPanel();
      contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));
      setContentPane(contentPane);

      this.btnCommit = new JButton("Commit");
      this.btnCommit.setEnabled(false);
      HTMLPane.reduceHeight(this.btnCommit, 20);
      this.btnCommit.setToolTipText("Commit changes to Moneydance");

      this.pnOutputLog = new HTMLPane();
      JScrollPane scrollPane = new JScrollPane(this.pnOutputLog);
      GroupLayout layoutContent = new GroupLayout(contentPane);
      layoutContent.setHorizontalGroup(
         layoutContent.createParallelGroup(GroupLayout.Alignment.TRAILING)
            .addGroup(layoutContent.createSequentialGroup()
               .addContainerGap(403, Short.MAX_VALUE)
               .addComponent(this.btnCommit))
            .addComponent(scrollPane, DEFAULT_SIZE, 532, Short.MAX_VALUE)
      );
      layoutContent.setVerticalGroup(
         layoutContent.createParallelGroup(GroupLayout.Alignment.LEADING)
            .addGroup(layoutContent.createSequentialGroup()
               .addComponent(this.btnCommit)
               .addPreferredGap(LayoutStyle.ComponentPlacement.RELATED)
               .addComponent(scrollPane, DEFAULT_SIZE, 271, Short.MAX_VALUE))
      );
      contentPane.setLayout(layoutContent);

   } // end initComponents()

   /**
    * Wire in our event listeners.
    */
   private void wireEvents() {
      this.btnCommit.addActionListener(event -> {
         // invoked when Commit is selected
         if (this.staged != null) {
            try {
               String changeSummary = this.staged.commitChanges();

               if (changeSummary != null)
                  addText(changeSummary);
               enableCommitButton(this.staged.isModified());
            } catch (Exception e) {
               addText(e.toString());
               enableCommitButton(false);
               e.printStackTrace(System.err);
            }
         }
      }); // end btnCommit.addActionListener

   } // end wireEvents()

   /**
    * @param text HTML-text to append to the output log text area
    */
   public void addText(String text) {
      this.pnOutputLog.addText(text);

   } // end addText(String)

   /**
    * Clear the output log text area.
    */
   public void clearText() {
      this.pnOutputLog.clearText();

   } // end clearText()

   /**
    * @param b true to enable the button, otherwise false
    */
   public void enableCommitButton(boolean b) {
      this.btnCommit.setEnabled(b);

   } // end enableCommitButton(boolean)

   /**
    * Store the object to manage staged changes.
    *
    * @param staged The object managing staged changes
    */
   public void setStaged(StagedInterface staged) {
      this.staged = staged;

   } // end setStaged(StagedInterface)

   /**
    * Store an object with resources to close.
    *
    * @param closeable The object managing closeable resources
    */
   public void addCloseableResource(AutoCloseable closeable) {
      this.closeableResources.addFirst(closeable);

   } // end addCloseableResource(AutoCloseable)

   /**
    * Remove an object that no longer has resources to close.
    *
    * @param closeable The object without closeable resources
    */
   public void removeCloseableResource(AutoCloseable closeable) {
      this.closeableResources.remove(closeable);

   } // end removeCloseableResource(AutoCloseable)

   /**
    * Processes events on this window.
    *
    * @param event The event to be processed
    */
   protected void processEvent(AWTEvent event) {
      if (event.getID() == WindowEvent.WINDOW_CLOSING) {
         goAway();
      } else {
         super.processEvent(event);
      }

   } // end processEvent(AWTEvent)

   /**
    * Remove this frame.
    *
    * @return null
    */
   public IBondWindow goAway() {
      Dimension winSize = getSize();
      System.err.format(getLocale(), "Closing %s with width=%.0f, height=%.0f.%n",
         getTitle(), winSize.getWidth(), winSize.getHeight());
      setVisible(false);
      dispose();

      while (!this.closeableResources.isEmpty()) {
         // Release any resources we acquired.
         try {
            this.closeableResources.removeFirst().close();
         } catch (Exception e) {
            e.printStackTrace(System.err);
         }
      }

      return null;
   } // end goAway()

   /**
    * Launch the application.
    */
   public static void main(String[] args) {
      EventQueue.invokeLater(() -> {
         try {
            IBondWindow frame = new IBondWindow();
            frame.setVisible(true);
            frame.enableCommitButton(true);
         } catch (Exception e) {
            e.printStackTrace(System.out);
         }
      });

   } // end main(String[])

} // end class IBondWindow
