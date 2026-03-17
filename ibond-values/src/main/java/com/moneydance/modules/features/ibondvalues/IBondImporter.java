package com.moneydance.modules.features.ibondvalues;

import com.leastlogic.moneydance.util.MdUtil;
import com.leastlogic.moneydance.util.MduExcepcionito;
import com.leastlogic.moneydance.util.MduException;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;

import java.io.IOException;
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
import static java.math.RoundingMode.HALF_UP;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.YEAR;
import static org.dhatim.fastexcel.reader.CellType.FORMULA;
import static org.dhatim.fastexcel.reader.CellType.NUMBER;
import static org.dhatim.fastexcel.reader.CellType.STRING;

public class IBondImporter {
   /** Module properties */
   private final Properties props;
   /** Spreadsheet location */
   private final URI iBondRateHistory;
   /** Mapping from months to historical I bond interest rates */
   private TreeMap<YearMonth, IBondRateRec> iBondRates = null;
   /** History column header handlers */
   private final HashMap<String, Consumer<Integer>> histColHdrHandlers = new HashMap<>();
   /** Column index of semiannual inflation interest rates */
   private int iRateCol = -1;
   /** Column index of fixed interest rates */
   private int fRateCol = -1;
   /** Column index of dates rates take effect */
   private int sDateCol = -1;
   private static final String propertiesFileName = "ibond-values.properties";

   private static final int INTEREST_RATE_DIGITS = 4;
   private static final int SEMIANNUAL_MONTHS = 6;
   private static final int PENALTY_MONTHS = 3;
   private static final BigDecimal INITIAL_UNIT_VALUE = BigDecimal.valueOf(25);
   private static final int MATURITY_YEARS = 30;
   private static final int PENALTY_YEARS = 5;

   private static final Consumer<Integer> NOOP = ignoredInteger -> {};
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
      this.props = MdUtil.loadProps(propertiesFileName, getClass());
      this.histColHdrHandlers.put(getProperty("col.irate"), colIdx -> this.iRateCol = colIdx);
      this.histColHdrHandlers.put(getProperty("col.frate"), colIdx -> this.fRateCol = colIdx);
      this.histColHdrHandlers.put(getProperty("col.sdate"), colIdx -> this.sDateCol = colIdx);

      String uriStr = getProperty("url.treasurydirect");
      try {
         this.iBondRateHistory = new URI(uriStr);
      } catch (Exception e) {
         throw new MduException(e, "Problem parsing URL [%s]", uriStr);
      }

   } // end constructor

   /**
    * @param key Key for the desired property
    * @return One of our properties
    */
   private String getProperty(String key) throws MduException {
      String property = this.props.getProperty(key);
      if (property == null)
         throw new MduException(null, "Property [%s] not found in %s",
            key, propertiesFileName);

      return property;
   } // end getProperty(String)

   /**
    * {@return fastexcel-reader ReadableWorkbook of a spreadsheet on the TreasuryDirect website}
    */
   private ReadableWorkbook getIBondRateHistoryWorkbook() throws MduException {
      try (InputStream iStream = this.iBondRateHistory.toURL().openStream()) {

         // fastexcel-reader Javadoc explains the following constructor loads the whole
         // xlsx file into memory, so it's okay that we close the stream after construction
         // https://javadoc.io/doc/org.dhatim/fastexcel-reader
         return new ReadableWorkbook(iStream);
      } catch (Exception e) {
         throw new MduException(e, "Problem accessing %s", this.iBondRateHistory);
      } // end try-with-resources
   } // end getIBondRateHistoryWorkbook()

   /**
    * Retrieve an interest rate value from a spreadsheet
    * cell and clean it up to avoid lots of zeros and nines.
    *
    * @param cell Cell to interrogate
    * @return BigDecimal value rounded to the fourth place past the decimal point
    */
   private static BigDecimal getInterestRateClean(Cell cell) {
      BigDecimal bd = cell.asNumber();

      return bd.setScale(INTEREST_RATE_DIGITS, HALF_UP);
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
    * Runs on worker thread.
    *
    * @throws MduException Problem retrieving or interpreting TreasuryDirect spreadsheet
    */
   public void loadIBondRates() throws MduException {
      if (this.iBondRates == null) {
         try (ReadableWorkbook wb = getIBondRateHistoryWorkbook()) {
            String dataSheetName = getProperty("sheet.data");
            Sheet dataSheet = wb.findSheet(dataSheetName).orElseThrow(
               () -> new MduException(null, "Unable to find sheet %s in %s",
                  dataSheetName, this.iBondRateHistory));

            Spliterator<Row> dataRowItr;
            try {
               dataRowItr = dataSheet.openStream().spliterator();
            } catch (Exception e) {
               throw new MduException(e, "Problem accessing rows in %s", this.iBondRateHistory);
            }
            loadColumnIndexes(dataRowItr);
            this.iBondRates = getIBondRates(dataRowItr);
         } catch (IOException e) {
            throw new MduException(e, "Problem closing %s", this.iBondRateHistory);
         }
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
      while (dataRowItr.tryAdvance(row -> {
         for (Cell cell : row) {
            if (cell.getType() == STRING) {
               this.histColHdrHandlers.getOrDefault(cell.asString(), NOOP)
                  .accept(cell.getColumnIndex());
            }
         } // end for each cell in the next row
      })) {
         if (this.iRateCol >= 0 && this.fRateCol >= 0 && this.sDateCol >= 0)
            return;
      }

      throw new MduException(null, "Unable to locate column headers %s in %s",
         this.histColHdrHandlers.keySet(), this.iBondRateHistory);

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
    * Generic min utility.
    *
    * @param a Comparable parameter a
    * @param b Comparable parameter b
    * @return Earlier of a or b
    */
   private static <T extends Comparable<T>> T min(T a, T b) {

      return a.compareTo(b) <= 0 ? a : b;
   } // end min(T, T)

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
   public YearMonth getDateForTicker(String tickerSymbol) throws MduExcepcionito {
      YearMonth issueMonth;
      try {
         issueMonth = YearMonth.parse(tickerSymbol, TICKER_DATE_FORMATTER);
      } catch (Exception e) {
         throw new MduExcepcionito(e, "Problem parsing date from ticker symbol; %s",
            e.getLocalizedMessage());
      }

      if (issueMonth.isBefore(getIBondRates().firstKey()))
         throw new MduExcepcionito(null,
            "No interest rates for I bonds issued as early as %tY-%<tm (%s)",
            issueMonth, tickerSymbol);

      return issueMonth;
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
      compositeRate = compositeRate.setScale(INTEREST_RATE_DIGITS, HALF_UP);

      return compositeRate;
   } // end combineRate(BigDecimal, BigDecimal)

   /**
    * Update current balances for a specified month.
    *
    * @param current      Current balances to update
    * @param month        The month to add
    * @param iBondIntTxns Collection of interest payment transactions
    * @param monthNet     Function providing total net deposits and redemptions for a month
    */
   private static void updateBalances(IBondBalanceRec current, YearMonth month,
         CalcTxnList iBondIntTxns, Function<YearMonth, BigDecimal> monthNet) {
      // Start by adding calculated interest for this month
      List<CalcTxn> curIntTxns = iBondIntTxns.getForMonth(month);
      BigDecimal startingBal = current.redemptionVal().add(addAmounts(curIntTxns));

      // Add redemption total (typically zero or negative value) for this month
      BigDecimal change = monthNet.apply(month);
      current.redemptionVal(startingBal.add(change));

      if (change.compareTo(BigDecimal.ZERO) != 0 && startingBal.compareTo(BigDecimal.ZERO) != 0) {
         // reduce the interest-eligible balance by the portion of the starting balance redeemed
         current.eligibleBal(current.eligibleBal().multiply(
            BigDecimal.ONE.add(change.divide(startingBal, DECIMAL64))).setScale(2, HALF_UP));
      }

      if (curIntTxns != null) {
         curIntTxns.forEach(txn -> txn.endingBal(current.redemptionVal()));
      }

   } // end updateBalances(IBondBalanceRec, YearMonth, CalcTxnList, Function)

   /**
    * Add I bond interest payments that do not compound to a specified list.
    * Bonds cashed-in in less than 5 years, lose the last 3 months of interest.
    * Details of interest rate calculations are in the US Code of Federal Regulations
    * <a href="https://www.ecfr.gov/current/title-31/subtitle-B/chapter-II/subchapter-A/part-359">
    *    Title 31 Subtitle B Chapter II Subchapter A Part 359</a>.
    *
    * @param curBals          Current balances in calculation
    * @param compositeRate    Composite interest rate to use
    * @param penaltyFreeMonth Month I bond stops losing the last 3 months of interest
    * @param iBondIntTxns     Collection of interest payment transactions
    * @param monthNet         Function providing total net deposits and redemptions for a month
    */
   private static void addInterestTxns(IBondBalanceRec curBals,
         BigDecimal compositeRate, YearMonth penaltyFreeMonth, CalcTxnList iBondIntTxns,
         Function<YearMonth, BigDecimal> monthNet) {
      BigDecimal unitVal = curBals.unitVal(), priorUnitVal = unitVal;
      BigDecimal monthlyMultiplier = BigDecimal.valueOf(
         Math.pow(1.0 + compositeRate.doubleValue() / 2.0, 1.0 / SEMIANNUAL_MONTHS));

      for (int m = 0; m < SEMIANNUAL_MONTHS; ++m) {
         unitVal = unitVal.multiply(monthlyMultiplier, DECIMAL64);
         BigDecimal roundedUnitVal = unitVal.setScale(2, HALF_UP);
         BigDecimal eligibleUnits = curBals.eligibleBal().divide(curBals.unitVal(), DECIMAL64);
         BigDecimal interest = roundedUnitVal.subtract(priorUnitVal).multiply(eligibleUnits)
            .setScale(2, HALF_UP);
         String memo = "%tb %<tY interest".formatted(curBals.month());
         curBals.month(curBals.month().plusMonths(1));

         if (interest.signum() > 0) {
            YearMonth candidate = curBals.month().plusMonths(PENALTY_MONTHS);
            YearMonth accrualMonth = curBals.month().isBefore(penaltyFreeMonth)
               ? min(candidate, penaltyFreeMonth) : curBals.month();
            iBondIntTxns.add(new CalcTxn(accrualMonth, interest, memo));
         }

         updateBalances(curBals, curBals.month(), iBondIntTxns, monthNet);
         priorUnitVal = roundedUnitVal;
      } // end for non-compounding months
      curBals.unitVal(priorUnitVal);

   } // end addInterestTxns(IBondBalanceRec, BigDecimal, YearMonth, CalcTxnList, Function)

   /**
    * Calculate Series I savings bond interest payment transactions.
    * Note: {@code loadIBondRates} must have been called on this instance earlier.
    *
    * @param tickerSymbol Ticker symbol in the format IBondYYYYMM
    * @param monthNet     Function providing total net deposits and redemptions for a month
    * @param displayRates Consumer of interest rate message producer lambdas
    * @return Collection of calculated interest payment transactions
    * @throws MduExcepcionito Problem getting interest rates for the supplied ticker symbol
    */
   public CalcTxnList calcIBondInterestTxns(String tickerSymbol,
         Function<YearMonth, BigDecimal> monthNet,
         Consumer<Supplier<String>> displayRates) throws MduExcepcionito {
      CalcTxnList iBondIntTxns = new CalcTxnList();
      YearMonth issueMonth = getDateForTicker(tickerSymbol);
      BigDecimal issueVal = monthNet.apply(issueMonth);

      IBondBalanceRec curBals = new IBondBalanceRec(issueVal, INITIAL_UNIT_VALUE, issueMonth);
      YearMonth endMonth = min(issueMonth.plusYears(MATURITY_YEARS),
         getIBondRates().lastKey().plusMonths(SEMIANNUAL_MONTHS));
      BigDecimal fixedRate = getRateForMonth(issueMonth).fixedRate();
      YearMonth penaltyFreeMonth = issueMonth.plusYears(PENALTY_YEARS);

      while (curBals.month().isBefore(endMonth)) {
         BigDecimal inflateRate = getRateForMonth(curBals.month()).inflationRate();
         BigDecimal compositeRate = combineRate(fixedRate, inflateRate);
         displayRates.accept(() -> "For I bonds issued %s, starting %s composite rate is %s%%"
            .formatted(issueMonth, curBals.month(), compositeRate.scaleByPowerOfTen(2)));
         addInterestTxns(curBals, compositeRate, penaltyFreeMonth, iBondIntTxns, monthNet);
         curBals.eligibleBal(curBals.redemptionVal()
            .add(iBondIntTxns.tailKeys(curBals.month()).stream()
               .map(tMonth -> addAmounts(iBondIntTxns.getForMonth(tMonth)))
               .reduce(BigDecimal.ZERO, BigDecimal::add)));
      } // end while more months

      iBondIntTxns.tailKeys(curBals.month()).forEach(tailingMonth ->
         updateBalances(curBals, tailingMonth, iBondIntTxns, monthNet));

      return iBondIntTxns;
   } // end calcIBondInterestTxns(String, Function, Consumer)

} // end class IBondImporter
