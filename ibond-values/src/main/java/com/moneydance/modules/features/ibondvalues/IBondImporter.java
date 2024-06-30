package com.moneydance.modules.features.ibondvalues;

import com.leastlogic.moneydance.util.MdUtil;
import com.leastlogic.moneydance.util.MduException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Properties;
import java.util.TreeMap;

import static java.math.RoundingMode.HALF_EVEN;
import static org.apache.poi.ss.usermodel.CellType.STRING;
import static org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL;

public class IBondImporter {
   private Properties props = null;
   private static final String propertiesFileName = "ibond-values.properties";

   /**
    * Sole constructor.
    */
   public IBondImporter() throws MduException {
      IBondHistColHdr.initializeColumns(
         getProperty("col.irate"), getProperty("col.frate"), getProperty("col.sdate"));

   } // end constructor

   /**
    * @param key Key for the desired property
    * @return One of our properties
    */
   private String getProperty(String key) throws MduException {
      if (this.props == null) {
         this.props = MdUtil.loadProps(propertiesFileName, getClass());
      }
      String property = this.props.getProperty(key);
      if (property == null)
         throw new MduException(null, "Property [%s] not found in %s",
            key, propertiesFileName);

      return property;
   } // end getProperty(String)

   /**
    * Retrieve a row iterator over the data sheet of the spreadsheet at a specified URI.
    * @param iBondRateHistory Spreadsheet location
    * @return Row iterator
    */
   private Iterator<Row> getDataRowIterator(URI iBondRateHistory) throws MduException {
      Workbook wb;

      try (InputStream iStream = iBondRateHistory.toURL().openStream()) {
         wb = WorkbookFactory.create(iStream);
      } catch (IOException e) {
         throw new MduException(e, "Problem accessing %s", iBondRateHistory);
      } // end try-with-resources

      String dataSheetName = getProperty("sheet.data");
      Sheet dataSheet = wb.getSheet(dataSheetName);

      if (dataSheet == null)
         throw new MduException(null, "Unable to find sheet %s in %s",
            dataSheetName, iBondRateHistory);

      return dataSheet.rowIterator();
   } // end getDataRowIterator(URI)

   /**
    * Load I bond interest rate history from the spreadsheet at a specified URI.
    * @param iBondRateHistory Spreadsheet location
    * @return Navigable map containing historical I bond interest rates
    */
   private NavigableMap<LocalDate, IBondRateRec> getIBondRates(URI iBondRateHistory)
         throws MduException {
      Iterator<Row> dataRowItr = getDataRowIterator(iBondRateHistory);
      Row row = dataRowItr.next();
      int iRateCol = -1, fRateCol = -1, sDateCol = -1;

      for (Cell cell : row) {
         if (cell.getCellType() == STRING) {
            switch (IBondHistColHdr.getEnum(cell.getStringCellValue())) {
               case iRate: iRateCol = cell.getColumnIndex(); break;
               case fRate: fRateCol = cell.getColumnIndex(); break;
               case sDate: sDateCol = cell.getColumnIndex(); break;
            }
         }
      } // end for each cell

      if (iRateCol < 0 || fRateCol < 0 || sDateCol < 0)
         throw new MduException(null, "Unable to locate column headers in %s",
            iBondRateHistory);
      TreeMap<LocalDate, IBondRateRec> iBondRates = new TreeMap<>();

      while (dataRowItr.hasNext()) {
         row = dataRowItr.next();
         Cell iRateCell = row.getCell(iRateCol, RETURN_BLANK_AS_NULL);
         Cell fRateCell = row.getCell(fRateCol, RETURN_BLANK_AS_NULL);
         Cell sDateCell = row.getCell(sDateCol, RETURN_BLANK_AS_NULL);

         if (iRateCell != null && fRateCell != null && sDateCell != null) {
            double inflateRate = iRateCell.getNumericCellValue();
            double fixedRate = fRateCell.getNumericCellValue();
            LocalDate startDate = sDateCell.getLocalDateTimeCellValue().toLocalDate();
            iBondRates.put(startDate, new IBondRateRec(inflateRate, fixedRate, startDate));
         }
      } // end while more rows

      return iBondRates;
   } // end getIBondRates(URI)

   /**
    * Add I bond prices for months that do not compound to a specified list.
    * @param iBondPrice Initial I bond price
    * @param compositeRate Composite interest rate to use
    * @param month Initial month's starting date
    * @param iBondPrices List to which I bond prices are added
    */
   private static void addNonCompoundingMonths(BigDecimal iBondPrice, double compositeRate,
                                               LocalDate month, List<PriceRec> iBondPrices) {
      BigDecimal monthAccrual = iBondPrice
         .multiply(BigDecimal.valueOf(compositeRate / 12)).setScale(6, HALF_EVEN);
      BigDecimal accrual = iBondPrice;

      for (int m = 0; m < 5; ++m) {
         accrual = accrual.add(monthAccrual);
         month = month.plusMonths(1);
         iBondPrices.add(new PriceRec(accrual, month));
      } // end for non-compounding months

   } // end addNonCompoundingMonths(BigDecimal, double, LocalDate, List<PriceRec>)

   /**
    * Lose some interest in the first years of I bond life.
    * Bonds cashed-in in less than 5 years, lose the last 3 months of interest.
    * @param issueDate Date I bond was issued
    * @param iBondPrices List containing I bond prices
    */
   private static void loseInterestInFirstYears(
         LocalDate issueDate, List<PriceRec> iBondPrices) {

      for (int i = iBondPrices.size(); --i >= 3; ) {
         PriceRec priceRec = iBondPrices.get(i);

         if (priceRec.date().isBefore(issueDate.withDayOfMonth(1).plusYears(5))) {
            // overwrite price with one from 3 months earlier
            priceRec.sharePrice(iBondPrices.get(i - 3).sharePrice());
         }
      }
      iBondPrices.get(2).sharePrice(iBondPrices.get(0).sharePrice());
      iBondPrices.get(1).sharePrice(iBondPrices.get(0).sharePrice());

   } // end loseInterestInFirstYears(LocalDate, List<PriceRec>)

   /**
    * Make a list of I bond prices for each month for which interest rates are known.
    * @param issueDate Date I bond was issued
    * @param iBondRates Historical I bond interest rates
    * @return List containing I bond prices
    */
   private static List<PriceRec> getIBondPrices(
         LocalDate issueDate, NavigableMap<LocalDate, IBondRateRec> iBondRates) {
      ArrayList<PriceRec> iBondPrices = new ArrayList<>();
      LocalDate period = issueDate.withDayOfMonth(1);

      double fixedRate = iBondRates.floorEntry(period).getValue().fixedRate();
      BigDecimal iBondPrice = BigDecimal.ONE;
      iBondPrices.add(new PriceRec(iBondPrice, period));

      while (period.isBefore(iBondRates.lastKey().plusMonths(6))) {
         double inflationRate = iBondRates.floorEntry(period).getValue().inflationRate();
         double compositeRate = fixedRate + (2 + fixedRate) * inflationRate;
         System.out.println("Starting " + period + " composite rate=" + compositeRate);
         addNonCompoundingMonths(iBondPrice, compositeRate, period, iBondPrices);

         BigDecimal semiannualRate = BigDecimal.valueOf(compositeRate / 2);
         iBondPrice = iBondPrice.add(iBondPrice.multiply(semiannualRate).setScale(6, HALF_EVEN));
         period = period.plusMonths(6);
         iBondPrices.add(new PriceRec(iBondPrice, period));
      } // end while compounding periods

      loseInterestInFirstYears(issueDate, iBondPrices);

      return iBondPrices;
   } // end getIBondPrices(LocalDate, NavigableMap<LocalDate, IBondRateRec>)

   public static void main(String[] args) {
      try {
         IBondImporter importer = new IBondImporter();
         URI uri = new URI(importer.getProperty("url.treasurydirect"));
         NavigableMap<LocalDate, IBondRateRec> iBondRates = importer.getIBondRates(uri);
         LocalDate issueDate = LocalDate.of(2024, Month.APRIL, 13);
         List<PriceRec> iBondPrices = getIBondPrices(issueDate, iBondRates);
         BigDecimal shares = BigDecimal.valueOf(10000);

         for (PriceRec iBondPriceRec : iBondPrices) {
            System.out.println("Balance on " + iBondPriceRec.date() + " = "
               + shares.multiply(iBondPriceRec.sharePrice()));
         }
      } catch (URISyntaxException | MduException e) {
         throw new RuntimeException(e);
      }

   } // end main(String[])

} // end class IBondImporter
