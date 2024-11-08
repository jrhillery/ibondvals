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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
   private static final int SEMIANNUAL_MONTHS = 6;
   private static final int MONTHS_TO_LOSE = 3;
   private static final int EARLY_YEARS = 5;
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
         Optional<Cell> iRateCell = getCellOfType(this.iRateCol, NUMBER, row);
         Optional<Cell> fRateCell = getCellOfType(this.fRateCol, NUMBER, row);
         Optional<Cell> sDateCell = getCellOfType(this.sDateCol, FORMULA, row);

         if (iRateCell.isPresent() && fRateCell.isPresent() && sDateCell.isPresent()) {
            BigDecimal inflateRate = getInterestRateClean(iRateCell.get());
            BigDecimal fixedRate = getInterestRateClean(fRateCell.get());
            LocalDate startDate = sDateCell.get().asDate().toLocalDate();
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
    * @return Optional cell with the desired type
    */
   private static Optional<Cell> getCellOfType(int colIndex, CellType desiredType, Row row) {
      Optional<Cell> cell = row.getOptionalCell(colIndex);

      if (cell.isPresent() && cell.get().getType() != desiredType) {
         cell = Optional.empty();
      }

      return cell;
   } // end getCellOfType(int, CellType, Row)

   /**
    * Determine I bond issue date by parsing the ticker symbol.
    *
    * @param tickerSymbol Ticker symbol in the format IBondYYYYMM
    * @return Date corresponding to the first day of the issue month
    */
   public static LocalDate getDateForTicker(String tickerSymbol) throws MduExcepcionito {
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
    * @param month        Any date in month for which to return I bond rate record
    * @param tickerSymbol Ticker symbol
    * @return Corresponding I bond rate record
    * @throws MduExcepcionito Problem getting interest rates for the supplied ticker symbol
    * @throws MduException    Problem retrieving or interpreting TreasuryDirect spreadsheet
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
    * @param fixedRate     Fixed interest rate
    * @param inflationRate Semiannual inflation interest rate
    * @return Composite interest rate
    */
   private static BigDecimal combineRate(BigDecimal fixedRate, BigDecimal inflationRate) {
      BigDecimal compositeRate =
         fixedRate.add(BigDecimal.TWO).multiply(inflationRate).add(fixedRate);

      if (compositeRate.signum() < 0) {
         compositeRate = BigDecimal.ZERO;
      }

      // Round composite rate to the fourth place past the decimal point
      compositeRate = compositeRate.setScale(INTEREST_RATE_DIGITS, HALF_EVEN);

      return compositeRate;
   } // end combineRate(BigDecimal, BigDecimal)

   /**
    * Add I bond interest payments for months that do not compound to a specified list.
    *
    * @param compositeRate      Composite interest rate to use
    * @param finalBal           Final balance in initial month
    * @param month              Initial month
    * @param iBondIntTxns       List of interest payment transactions
    * @param redemptionForMonth Function providing redemption total for a month
    * @return Final balance in ending month
    */
   private static BigDecimal addNonCompoundingMonths(BigDecimal compositeRate,
         BigDecimal finalBal, YearMonth month, List<InterestTxnRec> iBondIntTxns,
         Function<YearMonth, BigDecimal> redemptionForMonth) {
      BigDecimal monthlyRate = compositeRate.divide(MONTHS_PER_YEAR, DECIMAL64);
      BigDecimal eligibleBal = finalBal;

      for (int m = 0; m < SEMIANNUAL_MONTHS; ++m) {
         BigDecimal interest = eligibleBal.multiply(monthlyRate).setScale(2, HALF_EVEN);
         String memo = "%tb %<tY interest".formatted(month);
         month = month.plusMonths(1);
         BigDecimal startingBal = finalBal.add(interest);
         iBondIntTxns.add(new InterestTxnRec(month.atDay(1), interest, memo, startingBal));

         BigDecimal redemption = redemptionForMonth.apply(month);
         eligibleBal = eligibleBal.multiply(
            BigDecimal.ONE.add(redemption.divide(startingBal, DECIMAL64)), DECIMAL64);
         finalBal = startingBal.add(redemption);
      } // end for non-compounding months

      return finalBal;
   } // end addNonCompoundingMonths(BigDecimal, BigDecimal, YearMonth, List, Function)

   /**
    * Lose some interest in the early years of I bond life.
    * Bonds cashed-in in less than 5 years, lose the last 3 months of interest.
    *
    * @param issueDate    Date I bond was issued
    * @param iBondIntTxns List of interest payment transactions
    */
   private void deferInterestInFirstYears(YearMonth issueDate,
         List<InterestTxnRec> iBondIntTxns) {
      LocalDate year5Age = issueDate.plusYears(EARLY_YEARS).atDay(1);

      iBondIntTxns.forEach(iTxnRec -> {
         if (iTxnRec.payDate().isBefore(year5Age)) {
            iTxnRec.deferPayment(MONTHS_TO_LOSE, year5Age);
         }
      });
      LocalDate today = LocalDate.now();

      iBondIntTxns.removeIf(iTxnRec -> iTxnRec.payDate().isAfter(today));

   } // end deferInterestInFirstYears(YearMonth, List<InterestTxnRec>)

   /**
    * Calculate Series I savings bond interest payment transactions.
    *
    * @param tickerSymbol       Ticker symbol in the format IBondYYYYMM
    * @param month0FinalBal     Final balance in month issued
    * @param redemptionForMonth Function providing redemption total for a month
    * @param displayRates       Consumer of interest rate message producer lambdas
    * @return List of interest payment transactions
    * @throws MduExcepcionito Problem getting interest rates for the supplied ticker symbol
    * @throws MduException    Problem retrieving or interpreting TreasuryDirect spreadsheet
    */
   public List<InterestTxnRec> getIBondInterestTxns(String tickerSymbol,
         BigDecimal month0FinalBal, Function<YearMonth, BigDecimal> redemptionForMonth,
         Consumer<Supplier<String>> displayRates) throws MduExcepcionito, MduException {
      List<InterestTxnRec> iBondIntTxns = new ArrayList<>();
      YearMonth issueDate = YearMonth.from(getDateForTicker(tickerSymbol));
      YearMonth month = issueDate;

      YearMonth thisMonth = YearMonth.now();
      BigDecimal fixedRate = getRateForMonth(month.atDay(1), tickerSymbol).fixedRate();
      BigDecimal finalBal = month0FinalBal;

      while (month.isBefore(thisMonth)) {
         final YearMonth curMonth = month;
         BigDecimal inflateRate = getRateForMonth(curMonth.atDay(1), tickerSymbol).inflationRate();
         BigDecimal compositeRate = combineRate(fixedRate, inflateRate);
         displayRates.accept(() -> "For I bonds issued %s, starting %s composite rate is %s%%"
            .formatted(issueDate, curMonth, compositeRate.scaleByPowerOfTen(2)));
         finalBal = addNonCompoundingMonths(
            compositeRate, finalBal, curMonth, iBondIntTxns, redemptionForMonth);

         month = curMonth.plusMonths(SEMIANNUAL_MONTHS);
      } // end while before, or on, today

      deferInterestInFirstYears(issueDate, iBondIntTxns);

      return iBondIntTxns;
   } // end getIBondInterestTxns(String, BigDecimal, Function, Consumer)

   public static void main(String[] args) {
      try {
         IBondImporter importer = new IBondImporter();
         List<InterestTxnRec> iBondIntTxns = importer.getIBondInterestTxns("IBond202312",
            BigDecimal.valueOf(10000), (month) -> BigDecimal.ZERO,
            rates -> System.out.println(rates.get()));

         iBondIntTxns.forEach(ibIntTxn ->
            System.out.format("On %s pay %s for %s, balance %s%n",
            ibIntTxn.payDate(), ibIntTxn.payAmount(), ibIntTxn.memo(), ibIntTxn.startingBal()));
      } catch (Exception e) {
         throw new RuntimeException(e);
      }

   } // end main(String[])

} // end class IBondImporter
