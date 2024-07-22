package com.moneydance.modules.features.ibondvalues;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Data record to hold a security price for a specified date.
 */
public class PriceRec {
   private BigDecimal sharePrice;
   private final LocalDate date;

   /**
    * Sole constructor.
    *
    * @param sharePrice Price in dollars
    * @param date Date the price took effect
    */
   public PriceRec(BigDecimal sharePrice, LocalDate date) {
      this.sharePrice = sharePrice;
      this.date = date;

   } // end constructor

   public BigDecimal sharePrice() { return this.sharePrice; }
   public LocalDate date()        { return  this.date; }

   /**
    * Setter for share price field.
    *
    * @param sharePrice New share price
    */
   void sharePrice(BigDecimal sharePrice) {
      this.sharePrice = sharePrice;

   } // end sharePrice(BigDecimal) setter

   public boolean equals(Object obj) {
      if (obj instanceof PriceRec other) {

         return other == this
            || other.sharePrice.equals(this.sharePrice)
            && other.date.equals(this.date);
      } else {
         return false;
      }
   } // end equals(Object)

   public int hashCode() {

      return this.sharePrice.hashCode() ^ this.date.hashCode();
   } // end hashCode()

   public String toString() {

      return "PriceRec[sharePrice=" + this.sharePrice + ", date=" + this.date + ']';
   } // end toString()

} // end class PriceRec
