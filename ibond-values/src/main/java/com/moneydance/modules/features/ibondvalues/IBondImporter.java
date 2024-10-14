package com.moneydance.modules.features.ibondvalues;

import com.leastlogic.moneydance.util.MdUtil;
import com.leastlogic.moneydance.util.MduExcepcionito;
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
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.Properties;
import java.util.Spliterator;
import java.util.TreeMap;

import static java.math.MathContext.DECIMAL64;
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
   /** Mapping from dates to historical I bond interest rates */
   private TreeMap<LocalDate, IBondRateRec> iBondRates = null;
   /** Column index of semiannual inflation interest rates */
   private int iRateCol = -1;
   /** Column index of fixed interest rates */
   private int fRateCol = -1;
   /** Column index of dates rates take effect */
   private int sDateCol = -1;
   private static final String propertiesFileName = "ibond-values.properties";

   private static final int INTEREST_RATE_DIGITS = 4;
   private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);
   private static final int PRICE_DIGITS = 4;
   private static final int SEMIANNUAL_MONTHS = 6;
   private static final int MONTHS_TO_LOSE = 3;
   private static final int EARLY_YEARS = 5;
   private static final int RATE_SET_INTERVAL = 6; // months
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
    * Retrieve a row spliterator over the data sheet
    * of a spreadsheet on the TreasuryDirect website.
    *
    * @return Row spliterator over data sheet
    * @throws MduException Problem retrieving TreasuryDirect spreadsheet data sheet
    */
   private Spliterator<Row> getDataRowIterator() throws MduException {
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
         return dataSheet.openStream().spliterator();
      } catch (Exception e) {
         throw new MduException(e, "Problem accessing rows in %s", this.iBondRateHistory);
      }
   } // end getDataRowIterator()

   /**
    * Retrieve an interest rate value from a spreadsheet
    * cell and clean it up to avoid lots of zeros and nines.
    *
    * @param cell Cell to interrogate
    * @return BigDecimal value rounded to the fourth place past the decimal point
    */
   private static BigDecimal getInterestRateClean(Cell cell) {
      BigDecimal bd = cell.asNumber();

      return bd.setScale(INTEREST_RATE_DIGITS, HALF_EVEN);
   } // end getInterestRateClean(Cell)

   /**
    * Load I bond interest rate history from a spreadsheet on the TreasuryDirect website.
    *
    * @return Mapping from dates to historical I bond interest rates
    * @throws MduException Problem retrieving or interpreting TreasuryDirect spreadsheet
    */
   public TreeMap<LocalDate, IBondRateRec> getIBondRates() throws MduException {
      if (this.iBondRates == null) {
         Spliterator<Row> dataRowItr = getDataRowIterator();
         loadColumnIndexes(dataRowItr);
         this.iBondRates = getIBondRates(dataRowItr);
      }

      return this.iBondRates;
   } // end getIBondRates()

   /**
    * Load the column indexes of interest into our corresponding fields. Find
    * these column headers in the next row provided by the supplied spliterator.
    *
    * @param dataRowItr Row spliterator over the data sheet portion of the spreadsheet to use
    * @throws MduException Problem finding all interesting column headers
    */
   private void loadColumnIndexes(Spliterator<Row> dataRowItr) throws MduException {
      dataRowItr.tryAdvance(row -> {
         for (Cell cell : row) {
            if (cell.getType() == STRING) {
               switch (IBondHistColHdr.getEnum(cell.asString())) {
                  case iRate: this.iRateCol = cell.getColumnIndex(); break;
                  case fRate: this.fRateCol = cell.getColumnIndex(); break;
                  case sDate: this.sDateCol = cell.getColumnIndex(); break;
               }
            }
         } // end for each cell in the next row
      });

      if (this.iRateCol < 0 || this.fRateCol < 0 || this.sDateCol < 0)
         throw new MduException(null, "Unable to locate column headers %s in %s",
            IBondHistColHdr.getColumnHeaders(), this.iBondRateHistory);

   } // end loadColumnIndexes(Spliterator<Row>)

   /**
    * Load I bond interest rate history from a spreadsheet on the TreasuryDirect website.
    *
    * @param dataRowItr Row spliterator over the data sheet portion of the spreadsheet to use
    * @return Mapping from dates to historical I bond interest rates
    */
   private TreeMap<LocalDate, IBondRateRec> getIBondRates(Spliterator<Row> dataRowItr) {
      TreeMap<LocalDate, IBondRateRec> iBondRates = new TreeMap<>();

      dataRowItr.forEachRemaining(row -> {
         Cell iRateCell = getCellOfType(this.iRateCol, NUMBER, row);
         Cell fRateCell = getCellOfType(this.fRateCol, NUMBER, row);
         Cell sDateCell = getCellOfType(this.sDateCol, FORMULA, row);

         if (iRateCell != null && fRateCell != null && sDateCell != null) {
            BigDecimal inflateRate = getInterestRateClean(iRateCell);
            BigDecimal fixedRate = getInterestRateClean(fRateCell);
            LocalDate startDate = sDateCell.asDate().toLocalDate();
            iBondRates.put(startDate, new IBondRateRec(inflateRate, fixedRate, startDate));
         }
      }); // end for each remaining row

      return iBondRates;
   } // end getIBondRates(Spliterator<Row>)

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
   private static LocalDate getDateForTicker(String tickerSymbol) throws MduExcepcionito {
      try {

         return LocalDate.parse(tickerSymbol, TICKER_DATE_FORMATTER);
      } catch (Exception e) {
         throw new MduExcepcionito(e, "Problem parsing date from ticker symbol; %s",
            e.getLocalizedMessage());
      }
   } // end getDateForTicker(String)

   /**
    * Find the Series I savings bond interest rate history data for a given month.
    *
    * @param month Any date in month for which to return I bond rate record
    * @param tickerSymbol Ticker symbol
    * @return Corresponding I bond rate record
    * @throws MduExcepcionito Problem getting interest rates for the supplied ticker symbol
    * @throws MduException Problem retrieving or interpreting TreasuryDirect spreadsheet
    */
   private IBondRateRec getRateForMonth(LocalDate month, String tickerSymbol)
         throws MduExcepcionito, MduException {
      LocalDate rateDate = getIBondRates().floorKey(month);

      if (rateDate == null)
         throw new MduExcepcionito(null,
            "No interest rates for I bonds issued as early as %tF (%s)",
            month, tickerSymbol);

      return getIBondRates().get(rateDate);
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
    * @param month Starting date of month for which the interest rate is being composed
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
    * @param iBondPrices Mapping from dates to I bond prices to which I bond prices are added
    */
   private static void addNonCompoundingMonths(
         BigDecimal iBondPrice, BigDecimal compositeRate,
         LocalDate month, TreeMap<LocalDate, BigDecimal> iBondPrices) {
      BigDecimal monthlyRate = compositeRate.divide(MONTHS_PER_YEAR, DECIMAL64);
      BigDecimal monthAccrual = iBondPrice.multiply(monthlyRate, DECIMAL64);
      BigDecimal accrual = iBondPrice;

      for (int m = 1; m < SEMIANNUAL_MONTHS; ++m) {
         accrual = accrual.add(monthAccrual);
         month = month.plusMonths(1);
         iBondPrices.put(month, accrual.setScale(PRICE_DIGITS, HALF_EVEN));
      } // end for non-compounding months

   } // end addNonCompoundingMonths(BigDecimal, BigDecimal, LocalDate, TreeMap<LocalDate, BigDecimal>)

   /**
    * Lose some interest in the early years of I bond life.
    * Bonds cashed-in in less than 5 years, lose the last 3 months of interest.
    *
    * @param issueDate Date I bond was issued
    * @param iBondPrices Mapping from dates to I bond prices
    */
   private static void loseInterestInFirstYears(
         LocalDate issueDate, TreeMap<LocalDate, BigDecimal> iBondPrices) {

      if (iBondPrices.size() >= MONTHS_TO_LOSE) {
         NavigableSet<LocalDate> iBondDates = iBondPrices.navigableKeySet();
         Iterator<LocalDate> current = iBondDates.descendingIterator();
         Iterator<LocalDate> threePrior = iBondDates.descendingIterator();

         for (int i = 0; i < MONTHS_TO_LOSE; i++)
            threePrior.next();
         LocalDate year5Age = issueDate.withDayOfMonth(1).plusYears(EARLY_YEARS);

         while (threePrior.hasNext()) {
            LocalDate currentDate = current.next();
            LocalDate threePriorDate = threePrior.next();

            if (currentDate.isBefore(year5Age)) {
               // overwrite price with one from 3 months earlier
               iBondPrices.put(currentDate, iBondPrices.get(threePriorDate));
            }
         } // end while more prior months

         for (int i = 0; i < MONTHS_TO_LOSE; i++)
            iBondPrices.put(current.next(), BigDecimal.ONE);
      }

   } // end loseInterestInFirstYears(LocalDate, TreeMap<LocalDate, BigDecimal>)

   /**
    * Make a list of I bond prices for each month for which interest rates are known.
    *
    * @param tickerSymbol Ticker symbol in the format IBondYYYYMM
    * @return Mapping from dates to I bond prices
    * @throws MduExcepcionito Problem getting interest rates for the supplied ticker symbol
    * @throws MduException Problem retrieving or interpreting TreasuryDirect spreadsheet
    */
   public TreeMap<LocalDate, BigDecimal> getIBondPrices(String tickerSymbol)
         throws MduExcepcionito, MduException {
      TreeMap<LocalDate, BigDecimal> iBondPrices = new TreeMap<>();
      LocalDate issueDate = getDateForTicker(tickerSymbol);
      LocalDate month = issueDate.withDayOfMonth(1);

      LocalDate firstUnknownDate = getIBondRates().lastKey().plusMonths(RATE_SET_INTERVAL);
      BigDecimal fixedRate = getRateForMonth(month, tickerSymbol).fixedRate();
      BigDecimal iBondPrice = BigDecimal.ONE;

      while (month.isBefore(firstUnknownDate)) {
         BigDecimal inflateRate = getRateForMonth(month, tickerSymbol).inflationRate();
         BigDecimal compositeRate = combineRate(fixedRate, inflateRate, issueDate, month);
         addNonCompoundingMonths(iBondPrice, compositeRate, month, iBondPrices);

         BigDecimal semiannualRate = compositeRate.divide(BigDecimal.TWO, DECIMAL64);
         iBondPrice = iBondPrice.add(iBondPrice.multiply(semiannualRate, DECIMAL64));
         month = month.plusMonths(SEMIANNUAL_MONTHS);
         iBondPrices.put(month, iBondPrice.setScale(PRICE_DIGITS, HALF_EVEN));
      } // end while semiannual compounding periods

      if (iBondPrices.isEmpty())
         throw new MduExcepcionito(null,
            "No interest rates for I bonds issued as late as %tF (%s)",
            issueDate, tickerSymbol);

      loseInterestInFirstYears(issueDate, iBondPrices);

      return iBondPrices;
   } // end getIBondPrices(String)

   public static void main(String[] args) {
      try {
         IBondImporter importer = new IBondImporter();
         TreeMap<LocalDate, BigDecimal> iBondPrices = importer.getIBondPrices("IBond201901");
         BigDecimal shares = BigDecimal.valueOf(25);

         iBondPrices.forEach((date, price) ->
            System.out.format("Balance on %s = %s%n", date, shares.multiply(price)));
      } catch (Exception e) {
         throw new RuntimeException(e);
      }

   } // end main(String[])

} // end class IBondImporter
