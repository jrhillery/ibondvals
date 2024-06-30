package com.moneydance.modules.features.ibondvalues;

import java.time.LocalDate;

/**
 * Data record to hold Series I savings bond interest rate history data provided by TreasuryDirect.gov.
 * The interest rate on a Series I savings bond changes every 6 months, based on inflation.
 *
 * @param inflationRate Semiannual (1/2 year) inflation rate
 * @param fixedRate Fixed interest rate
 * @param startDate Date the rates took effect
 */
public record IBondRateRec(double inflationRate, double fixedRate, LocalDate startDate) {

} // end record IBondRateRec
