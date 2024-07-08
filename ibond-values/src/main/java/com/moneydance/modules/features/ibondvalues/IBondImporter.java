package com.moneydance.modules.features.ibondvalues;

import com.leastlogic.moneydance.util.MdUtil;
import com.leastlogic.moneydance.util.MduException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Properties;
import java.util.TreeMap;

import static java.math.RoundingMode.HALF_EVEN;
import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.YEAR;
import static org.apache.poi.ss.usermodel.CellType.STRING;
import static org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL;

public class IBondImporter {
   /** Spreadsheet location */
   private URI iBondRateHistory = null;
   private Properties props = null;
   private static final String propertiesFileName = "ibond-values.properties";

   public static final int MONTHS_PER_YEAR = 12;
   public static final int INTEREST_DIGITS = 8;
   public static final int RATE_SET_INTERVAL = 6; // months

   /**
    * Data record to hold Series I savings bond interest rate history
    * data provided by TreasuryDirect.gov. The interest rate on a
    * Series I savings bond changes every 6 months, based on inflation.
    *
    * @param inflationRate Semiannual (1/2 year) inflation rate
    * @param fixedRate Fixed interest rate
    * @param startDate Date the rates took effect
    */
   public record IBondRateRec(double inflationRate, double fixedRate, LocalDate startDate) {

   } // end record IBondRateRec

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
    * Retrieve a row iterator over the data sheet of
    * a spreadsheet on the TreasuryDirect website.
    *
    * @return Row iterator over data sheet
    */
   private Iterator<Row> getDataRowIterator() throws MduException {
      String uriStr = getProperty("url.treasurydirect");
      try {
         this.iBondRateHistory = new URI(uriStr);
      } catch (URISyntaxException e) {
         throw new MduException(e, "Problem parsing URL [%s]", uriStr);
      }
      Workbook wb;

      try (InputStream iStream = this.iBondRateHistory.toURL().openStream()) {
         wb = new XSSFWorkbook(iStream);
      } catch (IOException e) {
         throw new MduException(e, "Problem accessing %s", this.iBondRateHistory);
      } // end try-with-resources

      String dataSheetName = getProperty("sheet.data");
      Sheet dataSheet = wb.getSheet(dataSheetName);

      if (dataSheet == null)
         throw new MduException(null, "Unable to find sheet %s in %s",
            dataSheetName, this.iBondRateHistory);

      return dataSheet.rowIterator();
   } // end getDataRowIterator()

   /**
    * Retrieve a numeric value from a spreadsheet cell
    * and clean it up to avoid lots of zeros and nines.
    *
    * @param cell Cell to interrogate
    * @return Double value rounded to the tenth place past the decimal point
    */
   private static double getNumericClean(Cell cell) {

      return MdUtil.roundPrice(cell.getNumericCellValue()).doubleValue();
   } // end getNumericClean(Cell)

   /**
    * Load I bond interest rate history from a spreadsheet on the TreasuryDirect website.
    *
    * @return Navigable map containing historical I bond interest rates
    */
   public NavigableMap<LocalDate, IBondRateRec> getIBondRates() throws MduException {
      Iterator<Row> dataRowItr = getDataRowIterator();
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
         throw new MduException(null, "Unable to locate column headers %s in %s",
            IBondHistColHdr.getColumnHeaders(), this.iBondRateHistory);

      return getIBondRates(dataRowItr, iRateCol, fRateCol, sDateCol);
   } // end getIBondRates()

   /**
    * Load I bond interest rate history from a spreadsheet on the TreasuryDirect website.
    *
    * @param dataRowItr Row iterator over the data sheet portion of the spreadsheet to use
    * @param iRateCol Column index of semiannual inflation interest rates
    * @param fRateCol Column index of fixed interest rates
    * @param sDateCol Column index of dates rates take effect
    * @return Navigable map containing historical I bond interest rates
    */
   private static NavigableMap<LocalDate, IBondRateRec> getIBondRates(
         Iterator<Row> dataRowItr, int iRateCol, int fRateCol, int sDateCol) {
      TreeMap<LocalDate, IBondRateRec> iBondRates = new TreeMap<>();

      while (dataRowItr.hasNext()) {
         Row row = dataRowItr.next();
         Cell iRateCell = row.getCell(iRateCol, RETURN_BLANK_AS_NULL);
         Cell fRateCell = row.getCell(fRateCol, RETURN_BLANK_AS_NULL);
         Cell sDateCell = row.getCell(sDateCol, RETURN_BLANK_AS_NULL);

         if (iRateCell != null && fRateCell != null && sDateCell != null) {
            double inflateRate = getNumericClean(iRateCell);
            double fixedRate = getNumericClean(fRateCell);
            LocalDate startDate = sDateCell.getLocalDateTimeCellValue().toLocalDate();
            iBondRates.put(startDate, new IBondRateRec(inflateRate, fixedRate, startDate));
         }
      } // end while more rows

      return iBondRates;
   } // end getIBondRates(Iterator<Row>, int, int, int)

   /**
    * Determine I bond issue date by parsing the ticker symbol.
    *
    * @param tickerSymbol Ticker symbol in the format ibondYYYYMM
    * @return Date corresponding to the first day of the issue month
    */
   public static LocalDate getDateForTicker(String tickerSymbol) {
      DateTimeFormatterBuilder formatterBuilder = new DateTimeFormatterBuilder()
         .parseCaseInsensitive()
         .appendLiteral("ibond")
         .appendValue(YEAR)
         .appendValue(MONTH_OF_YEAR, 2)
         .parseDefaulting(DAY_OF_MONTH, 1);

      return LocalDate.parse(tickerSymbol, formatterBuilder.toFormatter());
   } // end getDateForTicker(String)

   /**
    * Add I bond prices for months that do not compound to a specified list.
    *
    * @param iBondPrice Initial I bond price
    * @param compositeRate Composite interest rate to use
    * @param month Initial month's starting date
    * @param iBondPrices List to which I bond prices are added
    */
   private static void addNonCompoundingMonths(BigDecimal iBondPrice, double compositeRate,
                                               LocalDate month, List<PriceRec> iBondPrices) {
      BigDecimal monthlyRate = BigDecimal.valueOf(compositeRate / MONTHS_PER_YEAR);
      BigDecimal monthAccrual =
         iBondPrice.multiply(monthlyRate).setScale(INTEREST_DIGITS, HALF_EVEN);
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
    *
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
    *
    * @param issueDate Date I bond was issued
    * @param iBondRates Historical I bond interest rates
    * @return List containing I bond prices
    */
   public static List<PriceRec> getIBondPrices(
         LocalDate issueDate, NavigableMap<LocalDate, IBondRateRec> iBondRates) {
      ArrayList<PriceRec> iBondPrices = new ArrayList<>();
      LocalDate period = issueDate.withDayOfMonth(1);

      double fixedRate = iBondRates.floorEntry(period).getValue().fixedRate();
      BigDecimal iBondPrice = BigDecimal.ONE;
      iBondPrices.add(new PriceRec(iBondPrice, period));

      while (period.isBefore(iBondRates.lastKey().plusMonths(RATE_SET_INTERVAL))) {
         double inflationRate = iBondRates.floorEntry(period).getValue().inflationRate();
         double compositeRate = fixedRate + (2 + fixedRate) * inflationRate;

         System.err.format("For I bonds issued %tF, starting %tF composite rate=%.8f%n",
            issueDate, period, compositeRate);
         addNonCompoundingMonths(iBondPrice, compositeRate, period, iBondPrices);

         BigDecimal semiannualRate = BigDecimal.valueOf(compositeRate / 2);
         iBondPrice = iBondPrice.add(
            iBondPrice.multiply(semiannualRate).setScale(INTEREST_DIGITS, HALF_EVEN));
         period = period.plusMonths(MONTHS_PER_YEAR / 2);
         iBondPrices.add(new PriceRec(iBondPrice, period));
      } // end while semiannual compounding periods

      loseInterestInFirstYears(issueDate, iBondPrices);

      return iBondPrices;
   } // end getIBondPrices(LocalDate, NavigableMap<LocalDate, IBondRateRec>)

   public static void main(String[] args) {
      try {
         IBondImporter importer = new IBondImporter();
         NavigableMap<LocalDate, IBondRateRec> iBondRates = importer.getIBondRates();
         LocalDate issueDate = getDateForTicker("ibond202404");
         List<PriceRec> iBondPrices = getIBondPrices(issueDate, iBondRates);
         BigDecimal shares = BigDecimal.valueOf(25);

         for (PriceRec iBondPriceRec : iBondPrices) {
            System.out.println("Balance on " + iBondPriceRec.date() + " = "
               + shares.multiply(iBondPriceRec.sharePrice()));
         }
      } catch (MduException e) {
         throw new RuntimeException(e);
      }

   } // end main(String[])

} // end class IBondImporter
