package com.moneydance.modules.features.ibondvalues;

import com.leastlogic.moneydance.util.MdUtil;
import com.leastlogic.moneydance.util.MduException;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Properties;
import java.util.TreeMap;

import static java.math.RoundingMode.HALF_EVEN;
import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.YEAR;
import static org.dhatim.fastexcel.reader.CellType.FORMULA;
import static org.dhatim.fastexcel.reader.CellType.NUMBER;
import static org.dhatim.fastexcel.reader.CellType.STRING;

public class IBondImporter {
   /** Spreadsheet location */
   private URI iBondRateHistory = null;
   private Properties props = null;
   private NavigableMap<LocalDate, IBondRateRec> iBondRates = null;
   private static final String propertiesFileName = "ibond-values.properties";

   private static final int INTEREST_RATE_DIGITS = 4;
   private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);
   private static final int INTEREST_DIGITS = 8;
   private static final int RATE_SET_INTERVAL = 6; // months
   private static final int SEMIANNUAL_MONTHS = 6;
   private static final DateTimeFormatter TICKER_DATE_FORMATTER =
      new DateTimeFormatterBuilder()
         .parseCaseInsensitive()
         .appendLiteral(MdUtil.IBOND_TICKER_PREFIX)
         .appendValue(YEAR)
         .appendValue(MONTH_OF_YEAR, 2)
         .parseDefaulting(DAY_OF_MONTH, 1).toFormatter();

   /**
    * Data record to hold Series I savings bond interest rate history
    * data provided by TreasuryDirect.gov. The interest rate on a
    * Series I savings bond changes every 6 months, based on inflation.
    *
    * @param inflationRate Semiannual (1/2 year) inflation rate
    * @param fixedRate Fixed interest rate
    * @param startDate Date the rates took effect
    */
   public record IBondRateRec(
      BigDecimal inflationRate, BigDecimal fixedRate, LocalDate startDate) {

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
      } catch (Exception e) {
         throw new MduException(e, "Problem parsing URL [%s]", uriStr);
      }
      ReadableWorkbook wb;

      try (InputStream iStream = this.iBondRateHistory.toURL().openStream()) {
         wb = new ReadableWorkbook(iStream);
      } catch (Exception e) {
         throw new MduException(e, "Problem accessing %s", this.iBondRateHistory);
      } // end try-with-resources

      String dataSheetName = getProperty("sheet.data");
      Sheet dataSheet = wb.findSheet(dataSheetName).orElseThrow(
         () -> new MduException(null, "Unable to find sheet %s in %s",
            dataSheetName, this.iBondRateHistory));

      try {
         return dataSheet.openStream().iterator();
      } catch (Exception e) {
         throw new MduException(e, "Problem accessing rows in %s", this.iBondRateHistory);
      }
   } // end getDataRowIterator()

   /**
    * Retrieve a numeric value from a spreadsheet cell
    * and clean it up to avoid lots of zeros and nines.
    *
    * @param cell Cell to interrogate
    * @return BigDecimal value rounded to the fourth place past the decimal point
    */
   private static BigDecimal getNumericClean(Cell cell) {
      BigDecimal bd = cell.asNumber();

      return bd.setScale(INTEREST_RATE_DIGITS, HALF_EVEN);
   } // end getNumericClean(Cell)

   /**
    * Load I bond interest rate history from a spreadsheet on the TreasuryDirect website.
    *
    * @return Navigable map containing historical I bond interest rates
    */
   public NavigableMap<LocalDate, IBondRateRec> getIBondRates() throws MduException {
      if (this.iBondRates == null) {
         Iterator<Row> dataRowItr = getDataRowIterator();
         Row row = dataRowItr.next();
         int iRateCol = -1, fRateCol = -1, sDateCol = -1;

         for (Cell cell : row) {
            if (cell.getType() == STRING) {
               switch (IBondHistColHdr.getEnum(cell.asString())) {
                  case iRate: iRateCol = cell.getColumnIndex(); break;
                  case fRate: fRateCol = cell.getColumnIndex(); break;
                  case sDate: sDateCol = cell.getColumnIndex(); break;
               }
            }
         } // end for each cell

         if (iRateCol < 0 || fRateCol < 0 || sDateCol < 0)
            throw new MduException(null, "Unable to locate column headers %s in %s",
               IBondHistColHdr.getColumnHeaders(), this.iBondRateHistory);

         this.iBondRates = getIBondRates(dataRowItr, iRateCol, fRateCol, sDateCol);
      }

      return this.iBondRates;
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

      dataRowItr.forEachRemaining(row -> {
         Cell iRateCell = getCellOfType(iRateCol, NUMBER, row);
         Cell fRateCell = getCellOfType(fRateCol, NUMBER, row);
         Cell sDateCell = getCellOfType(sDateCol, FORMULA, row);

         if (iRateCell != null && fRateCell != null && sDateCell != null) {
            BigDecimal inflateRate = getNumericClean(iRateCell);
            BigDecimal fixedRate = getNumericClean(fRateCell);
            LocalDate startDate = sDateCell.asDate().toLocalDate();
            iBondRates.put(startDate, new IBondRateRec(inflateRate, fixedRate, startDate));
         }
      }); // end for each remaining row

      return iBondRates;
   } // end getIBondRates(Iterator<Row>, int, int, int)

   /**
    * Get the cell at the specified column index with the desired type, otherwise null.
    *
    * @param colIndex column index to get
    * @param desiredType desired type of cell
    * @param row row containing cell
    * @return cell with the desired type, otherwise null
    */
   private static Cell getCellOfType(int colIndex, CellType desiredType, Row row) {
      Cell cell = row.getCell(colIndex);

      if (cell != null && cell.getType() != desiredType) {
         cell = null;
      }

      return cell;
   } // end getCellOfType(int, CellType, Row)

   /**
    * Determine I bond issue date by parsing the ticker symbol.
    *
    * @param tickerSymbol Ticker symbol in the format IBondYYYYMM
    * @return Date corresponding to the first day of the issue month
    */
   private static LocalDate getDateForTicker(String tickerSymbol) throws MduException {
      try {

         return LocalDate.parse(tickerSymbol, TICKER_DATE_FORMATTER);
      } catch (Exception e) {
         throw new MduException(e, "Problem parsing date from ticker symbol; %s",
            e.getLocalizedMessage());
      }
   } // end getDateForTicker(String)

   /**
    * Find the Series I savings bond interest rate history data for a given month.
    *
    * @param month Initial month's starting date
    * @param tickerSymbol Ticker symbol
    * @return Corresponding I bond rate record
    */
   private IBondRateRec getRateForMonth(LocalDate month, String tickerSymbol)
         throws MduException {
      Map.Entry<LocalDate, IBondRateRec> fixedRateEntry = getIBondRates().floorEntry(month);

      if (fixedRateEntry == null)
         throw new MduException(null, "No interest rates for I bonds issued %tF (%s)",
            month, tickerSymbol);

      return fixedRateEntry.getValue();
   } // end getRateForMonth(LocalDate, String)

   /**
    * Compose the interest rate that will apply for the specified fixed and semiannual
    * inflation interest rates using the rules for Series I saving bonds. See
    * <a href="https://www.treasurydirect.gov/savings-bonds/i-bonds/i-bonds-interest-rates">
    *    TreasuryDirect website</a> for details.
    *
    * @param fixedRate Fixed interest rate
    * @param inflationRate Semiannual inflation interest rate
    * @param issueDate Date I bond was issued
    * @param month Initial month's starting date
    * @return Composite interest rate
    */
   private static BigDecimal combineRate(BigDecimal fixedRate, BigDecimal inflationRate,
                                         LocalDate issueDate, LocalDate month) {
      BigDecimal compositeRate =
         fixedRate.add(BigDecimal.TWO).multiply(inflationRate).add(fixedRate);

      if (compositeRate.signum() < 0) {
         compositeRate = BigDecimal.ZERO;
      }

      // Round composite rate to the fourth place past the decimal point
      compositeRate = compositeRate.setScale(INTEREST_RATE_DIGITS, HALF_EVEN);

      System.err.format("For I bonds issued %tF, starting %tF composite rate=%s%%%n",
         issueDate, month, compositeRate.scaleByPowerOfTen(2));

      return compositeRate;
   } // end composeRate(BigDecimal, BigDecimal, LocalDate, LocalDate)

   /**
    * Add I bond prices for months that do not compound to a specified list.
    *
    * @param iBondPrice Initial I bond price
    * @param compositeRate Composite interest rate to use
    * @param month Initial month's starting date
    * @param iBondPrices List to which I bond prices are added
    */
   private static void addNonCompoundingMonths(BigDecimal iBondPrice, BigDecimal compositeRate,
                                               LocalDate month, List<PriceRec> iBondPrices) {
      BigDecimal monthlyRate = compositeRate.divide(MONTHS_PER_YEAR, HALF_EVEN);
      BigDecimal monthAccrual =
         iBondPrice.multiply(monthlyRate).setScale(INTEREST_DIGITS, HALF_EVEN);
      BigDecimal accrual = iBondPrice;

      for (int m = 1; m < SEMIANNUAL_MONTHS; ++m) {
         accrual = accrual.add(monthAccrual);
         month = month.plusMonths(1);
         iBondPrices.add(new PriceRec(accrual, month));
      } // end for non-compounding months

   } // end addNonCompoundingMonths(BigDecimal, BigDecimal, LocalDate, List<PriceRec>)

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

      if (iBondPrices.size() > 2) {
         iBondPrices.get(2).sharePrice(iBondPrices.get(0).sharePrice());
         iBondPrices.get(1).sharePrice(iBondPrices.get(0).sharePrice());
      }

   } // end loseInterestInFirstYears(LocalDate, List<PriceRec>)

   /**
    * Make a list of I bond prices for each month for which interest rates are known.
    *
    * @param tickerSymbol Ticker symbol in the format IBondYYYYMM
    * @return List containing I bond prices
    */
   public List<PriceRec> getIBondPrices(String tickerSymbol) throws MduException {
      ArrayList<PriceRec> iBondPrices = new ArrayList<>();
      LocalDate issueDate = getDateForTicker(tickerSymbol);
      LocalDate period = issueDate.withDayOfMonth(1);

      BigDecimal fixedRate = getRateForMonth(period, tickerSymbol).fixedRate();
      BigDecimal iBondPrice = BigDecimal.ONE;
      iBondPrices.add(new PriceRec(iBondPrice, period));

      while (period.isBefore(getIBondRates().lastKey().plusMonths(RATE_SET_INTERVAL))) {
         BigDecimal inflateRate = getRateForMonth(period, tickerSymbol).inflationRate();
         BigDecimal compositeRate = combineRate(fixedRate, inflateRate, issueDate, period);
         addNonCompoundingMonths(iBondPrice, compositeRate, period, iBondPrices);

         BigDecimal semiannualRate = compositeRate.divide(BigDecimal.TWO, HALF_EVEN);
         iBondPrice = iBondPrice.add(
            iBondPrice.multiply(semiannualRate).setScale(INTEREST_DIGITS, HALF_EVEN));
         period = period.plusMonths(SEMIANNUAL_MONTHS);
         iBondPrices.add(new PriceRec(iBondPrice, period));
      } // end while semiannual compounding periods

      loseInterestInFirstYears(issueDate, iBondPrices);

      return iBondPrices;
   } // end getIBondPrices(String)

   public static void main(String[] args) {
      try {
         IBondImporter importer = new IBondImporter();
         List<PriceRec> iBondPrices = importer.getIBondPrices("IBond201901");
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
