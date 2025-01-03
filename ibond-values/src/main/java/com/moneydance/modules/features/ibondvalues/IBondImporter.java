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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.math.MathContext.DECIMAL64;
import static java.math.RoundingMode.HALF_EVEN;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.YEAR;
import static org.dhatim.fastexcel.reader.CellType.FORMULA;
import static org.dhatim.fastexcel.reader.CellType.NUMBER;
import static org.dhatim.fastexcel.reader.CellType.STRING;

public class IBondImporter {
   /** Spreadsheet location */
   private URI iBondRateHistory = null;
   private Properties props = null;
   /** Mapping from months to historical I bond interest rates */
   private TreeMap<YearMonth, IBondRateRec> iBondRates = null;
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
   private static final int RATE_SET_INTERVAL = 6; // months
   private static final DateTimeFormatter TICKER_DATE_FORMATTER = new DateTimeFormatterBuilder()
      .parseCaseInsensitive()
      .appendLiteral(MdUtil.IBOND_TICKER_PREFIX)
      .appendValue(YEAR)
      .appendValue(MONTH_OF_YEAR, 2).toFormatter();

   /**
    * Data record to hold Series I savings bond interest rate history
    * data provided by TreasuryDirect.gov. The interest rate on a
    * Series I savings bond changes every 6 months, based on inflation.
    *
    * @param inflationRate Semiannual (1/2 year) inflation rate
    * @param fixedRate     Fixed interest rate
    * @param startMonth    Month the rates took effect
    */
   public record IBondRateRec(
      BigDecimal inflationRate, BigDecimal fixedRate, YearMonth startMonth) {

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
    * Retrieve a year and month value from a spreadsheet cell.
    *
    * @param cell Cell to interrogate
    * @return Year-month value
    */
   private static YearMonth getMonthClean(Cell cell) {
      LocalDateTime date = cell.asDate();

      return YearMonth.of(date.getYear(), date.getMonthValue());
   } // end getMonthClean(Cell)

   /**
    * Load I bond interest rate history from a spreadsheet on the TreasuryDirect website.
    *
    * @throws MduException Problem retrieving or interpreting TreasuryDirect spreadsheet
    */
   public void loadIBondRates() throws MduException {
      if (this.iBondRates == null) {
         Spliterator<Row> dataRowItr = getDataRowIterator();
         loadColumnIndexes(dataRowItr);
         this.iBondRates = getIBondRates(dataRowItr);
      }

   } // end loadIBondRates()

   /**
    * {@return Mapping from months to historical I bond interest rates}
    */
   private TreeMap<YearMonth, IBondRateRec> getIBondRates() {
      if (this.iBondRates == null)
         throw new IllegalStateException("%s.loadIBondRates must be called earlier"
            .formatted(getClass().getSimpleName()));

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
   private TreeMap<YearMonth, IBondRateRec> getIBondRates(Spliterator<Row> dataRowItr) {
      TreeMap<YearMonth, IBondRateRec> iBondRates = new TreeMap<>();

      dataRowItr.forEachRemaining(row -> {
         Optional<Cell> iRateCell = getCellOfType(this.iRateCol, NUMBER, row);
         Optional<Cell> fRateCell = getCellOfType(this.fRateCol, NUMBER, row);
         Optional<Cell> sDateCell = getCellOfType(this.sDateCol, FORMULA, row);

         if (iRateCell.isPresent() && fRateCell.isPresent() && sDateCell.isPresent()) {
            BigDecimal inflateRate = getInterestRateClean(iRateCell.get());
            BigDecimal fixedRate = getInterestRateClean(fRateCell.get());
            YearMonth startMonth = getMonthClean(sDateCell.get());
            iBondRates.put(startMonth, new IBondRateRec(inflateRate, fixedRate, startMonth));
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
    * @param calcTxns list of calculated interest payment transactions
    * @return Sum of the listed payments' amounts
    */
   private static BigDecimal addAmounts(List<CalcTxn> calcTxns) {
      if (calcTxns == null)
         return BigDecimal.ZERO;

      return calcTxns.stream().map(CalcTxn::payAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
   } // end addAmounts(List<CalcTxn>)

   /**
    * Determine I bond issue year and month by parsing the ticker symbol.
    *
    * @param tickerSymbol Ticker symbol in the format IBondYYYYMM
    * @return Year-month corresponding to the issue month
    */
   public static YearMonth getDateForTicker(String tickerSymbol) throws MduExcepcionito {
      try {

         return YearMonth.parse(tickerSymbol, TICKER_DATE_FORMATTER);
      } catch (Exception e) {
         throw new MduExcepcionito(e, "Problem parsing date from ticker symbol; %s",
            e.getLocalizedMessage());
      }
   } // end getDateForTicker(String)

   /**
    * Find the Series I savings bond interest rate history data for a given month.
    * Note: The caller must ensure the specified month is not earlier than
    * the earliest historical I bond interest rate (currently Nov. 1997).
    *
    * @param month Month for which to return I bond rate record
    * @return Corresponding I bond rate record
    */
   private IBondRateRec getRateForMonth(YearMonth month) {

      return getIBondRates().floorEntry(month).getValue();
   } // end getRateForMonth(YearMonth)

   /**
    * Compose the interest rate that will apply for the specified fixed and semiannual
    * inflation interest rates using the rules for Series I savings bonds. See
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
    * Update current balances for a specified month.
    *
    * @param current        Current balances to update
    * @param month          The month to add
    * @param iBondIntTxns   Collection of interest payment transactions
    * @param changeForMonth Function providing total net deposits and redemptions for a month
    */
   private static void updateBalances(IBondBalanceRec current, YearMonth month,
         CalcTxnList iBondIntTxns, Function<YearMonth, BigDecimal> changeForMonth) {
      // Start by adding calculated interest for this month
      List<CalcTxn> curIntTxns = iBondIntTxns.getForMonth(month);
      BigDecimal startingBal = current.totalBal().add(addAmounts(curIntTxns));

      // Add redemption total (typically zero or negative value) for this month
      BigDecimal change = changeForMonth.apply(month);
      current.totalBal(startingBal.add(change));

      // reduce the interest-eligible balance by the portion of the starting balance redeemed
      current.eligibleBal(current.eligibleBal().multiply(
         BigDecimal.ONE.add(change.divide(startingBal, DECIMAL64)), DECIMAL64));

      if (curIntTxns != null) {
         curIntTxns.forEach(txn -> txn.endingBal(current.totalBal()));
      }

   } // end updateBalances(IBondBalanceRec, YearMonth, CalcTxnList, Function)

   /**
    * Add I bond interest payments for months that do not compound to a specified list.
    * Bonds cashed-in in less than 5 years, lose the last 3 months of interest.
    *
    * @param curBals        Current balances in calculation
    * @param compositeRate  Composite interest rate to use
    * @param year5Age       Date I bond stops losing the last 3 months of interest
    * @param iBondIntTxns   Collection of interest payment transactions
    * @param changeForMonth Function providing total net deposits and redemptions for a month
    */
   private static void addNonCompoundingMonths(IBondBalanceRec curBals,
         BigDecimal compositeRate, YearMonth year5Age, CalcTxnList iBondIntTxns,
         Function<YearMonth, BigDecimal> changeForMonth) {
      BigDecimal monthlyRate = compositeRate.divide(MONTHS_PER_YEAR, DECIMAL64);

      for (int m = 0; m < SEMIANNUAL_MONTHS; ++m) {
         BigDecimal interest = curBals.eligibleBal().multiply(monthlyRate)
            .setScale(2, HALF_EVEN);
         String memo = "%tb %<tY interest".formatted(curBals.month());
         curBals.month(curBals.month().plusMonths(1));

         if (interest.signum() > 0) {
            YearMonth candidate = curBals.month().plusMonths(MONTHS_TO_LOSE);
            YearMonth payMonth = curBals.month().isBefore(year5Age)
               ? candidate.isBefore(year5Age) ? candidate : year5Age
               : curBals.month();
            iBondIntTxns.add(new CalcTxn(payMonth, interest, memo));
         }

         updateBalances(curBals, curBals.month(), iBondIntTxns, changeForMonth);
      } // end for non-compounding months

   } // end addNonCompoundingMonths(IBondBalanceRec, BigDecimal, YearMonth, CalcTxnList, Function)

   /**
    * Calculate Series I savings bond interest payment transactions.
    * Note: {@code loadIBondRates} must have been called on this instance earlier.
    *
    * @param tickerSymbol   Ticker symbol in the format IBondYYYYMM
    * @param changeForMonth Function providing total net deposits and redemptions for a month
    * @param displayRates   Consumer of interest rate message producer lambdas
    * @return Collection of calculated interest payment transactions
    * @throws MduExcepcionito Problem getting interest rates for the supplied ticker symbol
    */
   public CalcTxnList calcIBondInterestTxns(String tickerSymbol,
         Function<YearMonth, BigDecimal> changeForMonth,
         Consumer<Supplier<String>> displayRates) throws MduExcepcionito {
      YearMonth issueMonth = getDateForTicker(tickerSymbol);

      if (issueMonth.isBefore(getIBondRates().firstKey()))
         throw new MduExcepcionito(null,
            "No interest rates for I bonds issued as early as %tY-%<tm (%s)",
            issueMonth, tickerSymbol);
      CalcTxnList iBondIntTxns = new CalcTxnList();
      YearMonth year5Age = issueMonth.plusYears(EARLY_YEARS);

      YearMonth firstUnknownMonth = getIBondRates().lastKey().plusMonths(RATE_SET_INTERVAL);
      BigDecimal fixedRate = getRateForMonth(issueMonth).fixedRate();
      BigDecimal finalBal = changeForMonth.apply(issueMonth);
      IBondBalanceRec curBals = new IBondBalanceRec(finalBal, finalBal, issueMonth);

      while (curBals.month().isBefore(firstUnknownMonth)) {
         BigDecimal inflateRate = getRateForMonth(curBals.month()).inflationRate();
         BigDecimal compositeRate = combineRate(fixedRate, inflateRate);
         displayRates.accept(() -> "For I bonds issued %s, starting %s composite rate is %s%%"
            .formatted(issueMonth, curBals.month(), compositeRate.scaleByPowerOfTen(2)));
         addNonCompoundingMonths(curBals, compositeRate, year5Age, iBondIntTxns, changeForMonth);
         curBals.eligibleBal(curBals.totalBal());
      } // end while before first unknown month

      iBondIntTxns.tailKeys(curBals.month()).forEach(tailingMonth ->
         updateBalances(curBals, tailingMonth, iBondIntTxns, changeForMonth));

      return iBondIntTxns;
   } // end calcIBondInterestTxns(String, Function, Consumer)

   public static void main(String[] args) {
      try {
         IBondImporter importer = new IBondImporter();
         importer.loadIBondRates();
         CalcTxnList iBondIntTxns = importer.calcIBondInterestTxns("IBond202312",
            month -> switch (month.toString()) {
               case "2023-12" -> BigDecimal.valueOf(10000);
               case "2024-07" -> BigDecimal.ZERO; // new BigDecimal("-1221.00");
               case "2024-11" -> BigDecimal.ZERO; // new BigDecimal("-250.00");
               default -> BigDecimal.ZERO;
            }, rates -> System.out.println(rates.get()));

         iBondIntTxns.forEach(ibIntTxn -> System.out.format("On %s pay %s for %s, balance %s%n",
            ibIntTxn.payDate(), ibIntTxn.payAmount(), ibIntTxn.memo(), ibIntTxn.endingBal()));
      } catch (Exception e) {
         throw new RuntimeException(e);
      }

   } // end main(String[])

} // end class IBondImporter
