package com.moneydance.modules.features.ibondvalues;

import java.util.HashMap;

/**
 * Enumerates the column headers we reference.
 */
public enum IBondHistColHdr {
   /** Column of semiannual inflation interest rates */
   iRate,
   /** Column of fixed interest rates */
   fRate,
   /** Column of dates rates take effect */
   sDate,
   /** Any other column */
   other;

   /** Map header name text to this enum */
   private static final HashMap<String, IBondHistColHdr> valMap = new HashMap<>();

   /**
    * Initialize the column header enum value map.
    * @param iRateName Header name for semiannual inflation interest rates
    * @param fRateName Header name for fixed interest rates
    * @param sDateName Header name for dates rates take effect
    */
   public static void initializeColumns(
         String iRateName, String fRateName, String sDateName) {
      valMap.put(iRateName, iRate);
      valMap.put(fRateName, fRate);
      valMap.put(sDateName, sDate);

   } // end initializeColumns(String, String, String)

   /**
    * Retrieve the enum for the supplied column header name text.
    * @param value Column header text
    * @return Corresponding enum
    */
   public static IBondHistColHdr getEnum(String value) {
      IBondHistColHdr colHdr = valMap.get(value);

      if (colHdr == null)
         return other;
      else
         return colHdr;
   } // end getEnum(String)

} // end enum IBondHistColHdr
