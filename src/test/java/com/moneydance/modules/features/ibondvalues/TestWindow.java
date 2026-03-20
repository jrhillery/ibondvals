package com.moneydance.modules.features.ibondvalues;

import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestWindow implements AutoCloseable {

   private final LinkedHashMap<String, String> testStorage = new LinkedHashMap<>();
   private static final Path TEST_STORAGE = Path.of("TestStorage.txt");
   private static final Pattern eParse = Pattern.compile("(.+?);(.+)");

   private void startUp() {
      try (BufferedReader reader = Files.newBufferedReader(TEST_STORAGE)) {
         String line;

         while ((line = reader.readLine()) != null) {
            Matcher m = eParse.matcher(line);

            if (m.find()) {
               this.testStorage.put(m.group(1), m.group(2));
            }
         }
      } catch (Exception e) {
         System.err.format("%s%n", e);
      } // end try with resources

      EventQueue.invokeLater(() -> {
         try {
            IBondWindow frame = new IBondWindow("IBondWindow title", this.testStorage);
            frame.addCloseableResource(this);
            frame.setVisible(true);
            frame.enableCommitButton(true);
         } catch (Exception e) {
            e.printStackTrace(System.out);
         }
      });

   } // end startUp()

   public void close() throws Exception {
      try (BufferedWriter writer = Files.newBufferedWriter(TEST_STORAGE)) {

         this.testStorage.forEach((key, data) -> {
            try {
               writer.write(key);
               writer.write(';');
               writer.write(data);
               writer.write('\n');
            } catch (Exception e) {
               throw new RuntimeException(e);
            }
         });
      } // end try with resources

   } // end close()

   /**
    * Launch the application.
    */
   public static void main(String[] args) {
      new TestWindow().startUp();

   } // end main(String[])

} // end class TestWindow
