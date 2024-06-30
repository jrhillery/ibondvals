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
    *
    * @param sharePrice Price in dollars
    * @param date Date the price took effect
    */
   public PriceRec(BigDecimal sharePrice, LocalDate date) {
      this.sharePrice = sharePrice;
      this.date = date;
   }

   public BigDecimal sharePrice() { return this.sharePrice; }
   public LocalDate date()        { return  this.date; }

   /**
    * Setter for share price field.
    * @param sharePrice New share price
    */
   void sharePrice(BigDecimal sharePrice) {
      this.sharePrice = sharePrice;
   }

   public boolean equals(Object obj) {
      if (obj instanceof PriceRec other) {

         return other == this
            || other.sharePrice.equals(this.sharePrice)
            && other.date.equals(this.date);
      } else {
         return false;
      }
   }

   public int hashCode() {
      return this.sharePrice.hashCode() ^ this.date.hashCode();
   }

   public String toString() {
      return "PriceRec[sharePrice=" + this.sharePrice + ", date=" + this.date + ']';
   }

} // end record PriceRec
