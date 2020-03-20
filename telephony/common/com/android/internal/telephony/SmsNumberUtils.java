/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.os.Binder;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.android.internal.telephony.HbpcdLookup.MccIdd;
import com.android.internal.telephony.HbpcdLookup.MccLookup;
import com.android.internal.telephony.util.TelephonyUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;


/**
 * This class implements handle the MO SMS target address before sending.
 * This is special for VZW requirement. Follow the specifications of assisted dialing
 * of MO SMS while traveling on VZW CDMA, international CDMA or GSM markets.
 * {@hide}
 */
public class SmsNumberUtils {
    private static final String TAG = "SmsNumberUtils";
    private static final boolean DBG = SystemProperties.getInt("ro.debuggable", 0) == 1;

    private static final String PLUS_SIGN = "+";

    private static final int NANP_SHORT_LENGTH = 7;
    private static final int NANP_MEDIUM_LENGTH = 10;
    private static final int NANP_LONG_LENGTH = 11;

    private static final int NANP_CC = 1;
    private static final String NANP_NDD = "1";
    private static final String NANP_IDD = "011";

    private static final int MIN_COUNTRY_AREA_LOCAL_LENGTH = 10;

    private static final int GSM_UMTS_NETWORK = 0;
    private static final int CDMA_HOME_NETWORK = 1;
    private static final int CDMA_ROAMING_NETWORK = 2;

    private static final int NP_NONE = 0;
    private static final int NP_NANP_BEGIN = 1;

    /* <Phone Number>, <NXX>-<XXXX> N[2-9] */
    private static final int NP_NANP_LOCAL = NP_NANP_BEGIN;

    /* <Area_code>-<Phone Number>, <NXX>-<NXX>-<XXXX> N[2-9] */
    private static final int NP_NANP_AREA_LOCAL = NP_NANP_BEGIN + 1;

    /* <1>-<Area_code>-<Phone Number>, 1-<NXX>-<NXX>-<XXXX> N[2-9] */
    private static final int NP_NANP_NDD_AREA_LOCAL = NP_NANP_BEGIN + 2;

    /* <+><U.S.Country_code><Area_code><Phone Number>, +1-<NXX>-<NXX>-<XXXX> N[2-9] */
    private static final int NP_NANP_NBPCD_CC_AREA_LOCAL = NP_NANP_BEGIN + 3;

    /* <Local_IDD><Country_code><Area_code><Phone Number>, 001-1-<NXX>-<NXX>-<XXXX> N[2-9] */
    private static final int NP_NANP_LOCALIDD_CC_AREA_LOCAL = NP_NANP_BEGIN + 4;

    /* <+><Home_IDD><Country_code><Area_code><Phone Number>, +011-1-<NXX>-<NXX>-<XXXX> N[2-9] */
    private static final int NP_NANP_NBPCD_HOMEIDD_CC_AREA_LOCAL = NP_NANP_BEGIN + 5;

    private static final int NP_INTERNATIONAL_BEGIN = 100;
    /* <+>-<Home_IDD>-<Country_code>-<Area_code>-<Phone Number>, +011-86-25-86281234 */
    private static final int NP_NBPCD_HOMEIDD_CC_AREA_LOCAL = NP_INTERNATIONAL_BEGIN;

    /* <Home_IDD>-<Country_code>-<Area_code>-<Phone Number>, 011-86-25-86281234 */
    private static final int NP_HOMEIDD_CC_AREA_LOCAL = NP_INTERNATIONAL_BEGIN + 1;

    /* <NBPCD>-<Country_code>-<Area_code>-<Phone Number>, +1-86-25-86281234 */
    private static final int NP_NBPCD_CC_AREA_LOCAL = NP_INTERNATIONAL_BEGIN + 2;

    /* <Local_IDD>-<Country_code>-<Area_code>-<Phone Number>, 00-86-25-86281234 */
    private static final int NP_LOCALIDD_CC_AREA_LOCAL = NP_INTERNATIONAL_BEGIN + 3;

    /* <Country_code>-<Area_code>-<Phone Number>, 86-25-86281234*/
    private static final int NP_CC_AREA_LOCAL = NP_INTERNATIONAL_BEGIN + 4;

    private static int[] ALL_COUNTRY_CODES = null;
    private static int MAX_COUNTRY_CODES_LENGTH;
    private static HashMap<String, ArrayList<String>> IDDS_MAPS =
            new HashMap<String, ArrayList<String>>();

    private static class NumberEntry {
        public String number;
        public String IDD;
        public int countryCode;
        public NumberEntry(String number) {
            this.number = number;
        }
    }

    /**
     * Breaks the given number down and formats it according to the rules
     * for different number plans and different network.
     *
     * @param number destination number which need to be format
     * @param activeMcc current network's mcc
     * @param networkType current network type
     *
     * @return the number after formatting.
     */
    private static String formatNumber(Context context, String number,
                               String activeMcc,
                               int networkType) {
        if (number == null ) {
            throw new IllegalArgumentException("number is null");
        }

        if (activeMcc == null || activeMcc.trim().length() == 0) {
            throw new IllegalArgumentException("activeMcc is null or empty!");
        }

        String networkPortionNumber = PhoneNumberUtils.extractNetworkPortion(number);
        if (networkPortionNumber == null || networkPortionNumber.length() == 0) {
            throw new IllegalArgumentException("Number is invalid!");
        }

        NumberEntry numberEntry = new NumberEntry(networkPortionNumber);
        ArrayList<String> allIDDs = getAllIDDs(context, activeMcc);

        // First check whether the number is a NANP number.
        int nanpState = checkNANP(numberEntry, allIDDs);
        if (DBG) Log.d(TAG, "NANP type: " + getNumberPlanType(nanpState));

        if ((nanpState == NP_NANP_LOCAL)
            || (nanpState == NP_NANP_AREA_LOCAL)
            || (nanpState == NP_NANP_NDD_AREA_LOCAL)) {
            return networkPortionNumber;
        } else if (nanpState == NP_NANP_NBPCD_CC_AREA_LOCAL) {
            if (networkType == CDMA_HOME_NETWORK
                    || networkType == CDMA_ROAMING_NETWORK) {
                // Remove "+"
                return networkPortionNumber.substring(1);
            } else {
                return networkPortionNumber;
            }
        } else if (nanpState == NP_NANP_LOCALIDD_CC_AREA_LOCAL) {
            if (networkType == CDMA_HOME_NETWORK) {
                return networkPortionNumber;
            } else if (networkType == GSM_UMTS_NETWORK) {
                // Remove the local IDD and replace with "+"
                int iddLength  =  numberEntry.IDD != null ? numberEntry.IDD.length() : 0;
                return PLUS_SIGN + networkPortionNumber.substring(iddLength);
            } else if (networkType == CDMA_ROAMING_NETWORK) {
                // Remove the local IDD
                int iddLength  =  numberEntry.IDD != null ? numberEntry.IDD.length() : 0;
                return  networkPortionNumber.substring(iddLength);
            }
        }

        int internationalState = checkInternationalNumberPlan(context, numberEntry, allIDDs,
                NANP_IDD);
        if (DBG) Log.d(TAG, "International type: " + getNumberPlanType(internationalState));
        String returnNumber = null;

        switch (internationalState) {
            case NP_NBPCD_HOMEIDD_CC_AREA_LOCAL:
                if (networkType == GSM_UMTS_NETWORK) {
                    // Remove "+"
                    returnNumber = networkPortionNumber.substring(1);
                }
                break;

            case NP_NBPCD_CC_AREA_LOCAL:
                // Replace "+" with "011"
                returnNumber = NANP_IDD + networkPortionNumber.substring(1);
                break;

            case NP_LOCALIDD_CC_AREA_LOCAL:
                if (networkType == GSM_UMTS_NETWORK || networkType == CDMA_ROAMING_NETWORK) {
                    int iddLength  =  numberEntry.IDD != null ? numberEntry.IDD.length() : 0;
                    // Replace <Local IDD> to <Home IDD>("011")
                    returnNumber = NANP_IDD + networkPortionNumber.substring(iddLength);
                }
                break;

            case NP_CC_AREA_LOCAL:
                int countryCode = numberEntry.countryCode;

                if (!inExceptionListForNpCcAreaLocal(numberEntry)
                    && networkPortionNumber.length() >= 11 && countryCode != NANP_CC) {
                    // Add "011"
                    returnNumber = NANP_IDD + networkPortionNumber;
                }
                break;

            case NP_HOMEIDD_CC_AREA_LOCAL:
                returnNumber = networkPortionNumber;
                break;

            default:
                // Replace "+" with 011 in CDMA network if the number's country
                // code is not in the HbpcdLookup database.
                if (networkPortionNumber.startsWith(PLUS_SIGN)
                    && (networkType == CDMA_HOME_NETWORK || networkType == CDMA_ROAMING_NETWORK)) {
                    if (networkPortionNumber.startsWith(PLUS_SIGN + NANP_IDD)) {
                        // Only remove "+"
                        returnNumber = networkPortionNumber.substring(1);
                    } else {
                        // Replace "+" with "011"
                        returnNumber = NANP_IDD + networkPortionNumber.substring(1);
                    }
                }
        }

        if (returnNumber == null) {
            returnNumber = networkPortionNumber;
        }
        return returnNumber;
    }

    /**
     * Query International direct dialing from HbpcdLookup.db
     * for specified country code
     *
     * @param mcc current network's country code
     *
     * @return the IDD array list.
     */
    private static ArrayList<String> getAllIDDs(Context context, String mcc) {
        ArrayList<String> allIDDs = IDDS_MAPS.get(mcc);
        if (allIDDs != null) {
            return allIDDs;
        } else {
            allIDDs = new ArrayList<String>();
        }

        String projection[] = {MccIdd.IDD, MccIdd.MCC};
        String where = null;

        // if mcc is null         : return all rows
        // if mcc is empty-string : return those rows whose mcc is emptry-string
        String[] selectionArgs = null;
        if (mcc != null) {
            where = MccIdd.MCC + "=?";
            selectionArgs = new String[] {mcc};
        }

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(MccIdd.CONTENT_URI, projection,
                    where, selectionArgs, null);
            if (cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    String idd = cursor.getString(0);
                    if (!allIDDs.contains(idd)) {
                        allIDDs.add(idd);
                    }
                }
            }
        } catch (SQLException e) {
            Log.e(TAG, "Can't access HbpcdLookup database", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        IDDS_MAPS.put(mcc, allIDDs);

        if (DBG) Log.d(TAG, "MCC = " + mcc + ", all IDDs = " + allIDDs);
        return allIDDs;
    }


    /**
     * Verify if the the destination number is a NANP number
     *
     * @param numberEntry including number and IDD array
     * @param allIDDs the IDD array list of the current network's country code
     *
     * @return the number plan type related NANP
     */
    private static int checkNANP(NumberEntry numberEntry, ArrayList<String> allIDDs) {
        boolean isNANP = false;
        String number = numberEntry.number;

        if (number.length() == NANP_SHORT_LENGTH) {
            // 7 digits - Seven digit phone numbers
            char firstChar = number.charAt(0);
            if (firstChar >= '2' && firstChar <= '9') {
                isNANP = true;
                for (int i=1; i< NANP_SHORT_LENGTH; i++ ) {
                    char c= number.charAt(i);
                    if (!PhoneNumberUtils.isISODigit(c)) {
                        isNANP = false;
                        break;
                    }
                }
            }
            if (isNANP) {
                return NP_NANP_LOCAL;
            }
        } else if (number.length() == NANP_MEDIUM_LENGTH) {
            // 10 digits - Three digit area code followed by seven digit phone numbers/
            if (isNANP(number)) {
                return NP_NANP_AREA_LOCAL;
            }
        } else if (number.length() == NANP_LONG_LENGTH) {
            // 11 digits - One digit U.S. NDD(National Direct Dial) prefix '1',
            // followed by three digit area code and seven digit phone numbers
            if (isNANP(number)) {
                return NP_NANP_NDD_AREA_LOCAL;
            }
        } else if (number.startsWith(PLUS_SIGN)) {
            number = number.substring(1);
            if (number.length() == NANP_LONG_LENGTH) {
                // '+' and 11 digits -'+', followed by NANP CC prefix '1' followed by
                // three digit area code and seven digit phone numbers
                if (isNANP(number)) {
                    return NP_NANP_NBPCD_CC_AREA_LOCAL;
                }
            } else if (number.startsWith(NANP_IDD) && number.length() == NANP_LONG_LENGTH + 3) {
                // '+' and 14 digits -'+', followed by NANP IDD "011" followed by NANP CC
                // prefix '1' followed by three digit area code and seven digit phone numbers
                number = number.substring(3);
                if (isNANP(number)) {
                    return NP_NANP_NBPCD_HOMEIDD_CC_AREA_LOCAL;
                }
            }
        } else {
            // Check whether it's NP_NANP_LOCALIDD_CC_AREA_LOCAL
            for (String idd : allIDDs) {
                if (number.startsWith(idd)) {
                    String number2 = number.substring(idd.length());
                    if(number2 !=null && number2.startsWith(String.valueOf(NANP_CC))){
                        if (isNANP(number2)) {
                            numberEntry.IDD = idd;
                            return NP_NANP_LOCALIDD_CC_AREA_LOCAL;
                        }
                    }
                }
            }
        }

        return NP_NONE;
    }

    /**
     * This function checks if the passed in string conforms to the NANP format
     * i.e. NXX-NXX-XXXX, N is any digit 2-9 and X is any digit 0-9
     */
    private static boolean isNANP(String number) {
        boolean retVal = false;

        if (number.length() == NANP_MEDIUM_LENGTH
            || (number.length() == NANP_LONG_LENGTH  && number.startsWith(NANP_NDD))) {

            if (number.length() == NANP_LONG_LENGTH) {
                number = number.substring(1);
            }

            if (isTwoToNine(number.charAt(0)) &&
                isTwoToNine(number.charAt(3))) {
                retVal = true;
                for (int i=1; i<NANP_MEDIUM_LENGTH; i++ ) {
                    char c=number.charAt(i);
                    if (!PhoneNumberUtils.isISODigit(c)) {
                        retVal = false;
                        break;
                    }
                 }
             }
        }
        return retVal;
    }

    private static boolean isTwoToNine (char c) {
        if (c >= '2' && c <= '9') {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Verify if the the destination number is an internal number
     *
     * @param numberEntry including number and IDD array
     * @param allIDDs the IDD array list of the current network's country code
     *
     * @return the number plan type related international number
     */
    private static int checkInternationalNumberPlan(Context context, NumberEntry numberEntry,
            ArrayList<String> allIDDs,String homeIDD) {
        String number = numberEntry.number;
        int countryCode = -1;

        if (number.startsWith(PLUS_SIGN)) {
            // +xxxxxxxxxx
            String numberNoNBPCD = number.substring(1);
            if (numberNoNBPCD.startsWith(homeIDD)) {
                // +011xxxxxxxx
                String numberCountryAreaLocal = numberNoNBPCD.substring(homeIDD.length());
                if ((countryCode = getCountryCode(context, numberCountryAreaLocal)) > 0) {
                    numberEntry.countryCode = countryCode;
                    return NP_NBPCD_HOMEIDD_CC_AREA_LOCAL;
                }
            } else if ((countryCode = getCountryCode(context, numberNoNBPCD)) > 0) {
                numberEntry.countryCode = countryCode;
                return NP_NBPCD_CC_AREA_LOCAL;
            }

        } else if (number.startsWith(homeIDD)) {
            // 011xxxxxxxxx
            String numberCountryAreaLocal = number.substring(homeIDD.length());
            if ((countryCode = getCountryCode(context, numberCountryAreaLocal)) > 0) {
                numberEntry.countryCode = countryCode;
                return NP_HOMEIDD_CC_AREA_LOCAL;
            }
        } else {
            for (String exitCode : allIDDs) {
                if (number.startsWith(exitCode)) {
                    String numberNoIDD = number.substring(exitCode.length());
                    if ((countryCode = getCountryCode(context, numberNoIDD)) > 0) {
                        numberEntry.countryCode = countryCode;
                        numberEntry.IDD = exitCode;
                        return NP_LOCALIDD_CC_AREA_LOCAL;
                    }
                }
            }

            if (!number.startsWith("0") && (countryCode = getCountryCode(context, number)) > 0) {
                numberEntry.countryCode = countryCode;
                return NP_CC_AREA_LOCAL;
            }
        }
        return NP_NONE;
    }

    /**
     *  Returns the country code from the given number.
     */
    private static int getCountryCode(Context context, String number) {
        int countryCode = -1;
        if (number.length() >= MIN_COUNTRY_AREA_LOCAL_LENGTH) {
            // Check Country code
            int[] allCCs = getAllCountryCodes(context);
            if (allCCs == null) {
                return countryCode;
            }

            int[] ccArray = new int[MAX_COUNTRY_CODES_LENGTH];
            for (int i = 0; i < MAX_COUNTRY_CODES_LENGTH; i ++) {
                ccArray[i] = Integer.parseInt(number.substring(0, i+1));
            }

            for (int i = 0; i < allCCs.length; i ++) {
                int tempCC = allCCs[i];
                for (int j = 0; j < MAX_COUNTRY_CODES_LENGTH; j ++) {
                    if (tempCC == ccArray[j]) {
                        if (DBG) Log.d(TAG, "Country code = " + tempCC);
                        return tempCC;
                    }
                }
            }
        }

        return countryCode;
    }

    /**
     *  Gets all country Codes information with given MCC.
     */
    private static int[] getAllCountryCodes(Context context) {
        if (ALL_COUNTRY_CODES != null) {
            return ALL_COUNTRY_CODES;
        }

        Cursor cursor = null;
        try {
            String projection[] = {MccLookup.COUNTRY_CODE};
            cursor = context.getContentResolver().query(MccLookup.CONTENT_URI,
                    projection, null, null, null);

            if (cursor.getCount() > 0) {
                ALL_COUNTRY_CODES = new int[cursor.getCount()];
                int i = 0;
                while (cursor.moveToNext()) {
                    int countryCode = cursor.getInt(0);
                    ALL_COUNTRY_CODES[i++] = countryCode;
                    int length = String.valueOf(countryCode).trim().length();
                    if (length > MAX_COUNTRY_CODES_LENGTH) {
                        MAX_COUNTRY_CODES_LENGTH = length;
                    }
                }
            }
        } catch (SQLException e) {
            Log.e(TAG, "Can't access HbpcdLookup database", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return ALL_COUNTRY_CODES;
    }

    private static boolean inExceptionListForNpCcAreaLocal(NumberEntry numberEntry) {
        int countryCode = numberEntry.countryCode;
        boolean result = (numberEntry.number.length() == 12
                          && (countryCode == 7 || countryCode == 20
                              || countryCode == 65 || countryCode == 90));
        return result;
    }

    private static String getNumberPlanType(int state) {
        String numberPlanType = "Number Plan type (" + state + "): ";

        if (state == NP_NANP_LOCAL) {
            numberPlanType = "NP_NANP_LOCAL";
        } else if (state == NP_NANP_AREA_LOCAL) {
            numberPlanType = "NP_NANP_AREA_LOCAL";
        } else if (state  == NP_NANP_NDD_AREA_LOCAL) {
            numberPlanType = "NP_NANP_NDD_AREA_LOCAL";
        } else if (state == NP_NANP_NBPCD_CC_AREA_LOCAL) {
            numberPlanType = "NP_NANP_NBPCD_CC_AREA_LOCAL";
        } else if (state == NP_NANP_LOCALIDD_CC_AREA_LOCAL) {
            numberPlanType = "NP_NANP_LOCALIDD_CC_AREA_LOCAL";
        } else if (state == NP_NANP_NBPCD_HOMEIDD_CC_AREA_LOCAL) {
            numberPlanType = "NP_NANP_NBPCD_HOMEIDD_CC_AREA_LOCAL";
        } else if (state == NP_NBPCD_HOMEIDD_CC_AREA_LOCAL) {
            numberPlanType = "NP_NBPCD_HOMEIDD_CC_AREA_LOCAL";
        } else if (state == NP_HOMEIDD_CC_AREA_LOCAL) {
            numberPlanType = "NP_HOMEIDD_CC_AREA_LOCAL";
        } else if (state == NP_NBPCD_CC_AREA_LOCAL) {
            numberPlanType = "NP_NBPCD_CC_AREA_LOCAL";
        } else if (state == NP_LOCALIDD_CC_AREA_LOCAL) {
            numberPlanType = "NP_LOCALIDD_CC_AREA_LOCAL";
        } else if (state == NP_CC_AREA_LOCAL) {
            numberPlanType = "NP_CC_AREA_LOCAL";
        } else {
            numberPlanType = "Unknown type";
        }
        return numberPlanType;
    }

    /**
     * Filter the destination number if using VZW sim card.
     */
    public static String filterDestAddr(Context context, int subId, String destAddr) {
        if (DBG) Log.d(TAG, "enter filterDestAddr. destAddr=\"" + pii(TAG, destAddr) + "\"" );

        if (destAddr == null || !PhoneNumberUtils.isGlobalPhoneNumber(destAddr)) {
            Log.w(TAG, "destAddr" + pii(TAG, destAddr) +
                    " is not a global phone number! Nothing changed.");
            return destAddr;
        }

        final TelephonyManager telephonyManager = ((TelephonyManager) context
                .getSystemService(Context.TELEPHONY_SERVICE)).createForSubscriptionId(subId);
        final String networkOperator = telephonyManager.getNetworkOperator();
        String result = null;

        if (needToConvert(context, subId)) {
            final int networkType = getNetworkType(telephonyManager);
            if (networkType != -1 && !TextUtils.isEmpty(networkOperator)) {
                String networkMcc = networkOperator.substring(0, 3);
                if (networkMcc != null && networkMcc.trim().length() > 0) {
                    result = formatNumber(context, destAddr, networkMcc, networkType);
                }
            }
        }

        if (DBG) {
            Log.d(TAG, "destAddr is " + ((result != null)?"formatted.":"not formatted."));
            Log.d(TAG, "leave filterDestAddr, new destAddr=\"" + (result != null ? pii(TAG,
                    result) : pii(TAG, destAddr)) + "\"");
        }
        return result != null ? result : destAddr;
    }

    /**
     * Returns the current network type
     */
    private static int getNetworkType(TelephonyManager telephonyManager) {
        int networkType = -1;
        int phoneType = telephonyManager.getPhoneType();

        if (phoneType == TelephonyManager.PHONE_TYPE_GSM) {
            networkType = GSM_UMTS_NETWORK;
        } else if (phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
            if (isInternationalRoaming(telephonyManager)) {
                networkType = CDMA_ROAMING_NETWORK;
            } else {
                networkType = CDMA_HOME_NETWORK;
            }
        } else {
            if (DBG) Log.w(TAG, "warning! unknown mPhoneType value=" + phoneType);
        }

        return networkType;
    }

    private static boolean isInternationalRoaming(TelephonyManager telephonyManager) {
        String operatorIsoCountry = telephonyManager.getNetworkCountryIso();
        String simIsoCountry = telephonyManager.getSimCountryIso();
        boolean internationalRoaming = !TextUtils.isEmpty(operatorIsoCountry)
                && !TextUtils.isEmpty(simIsoCountry)
                && !simIsoCountry.equals(operatorIsoCountry);
        if (internationalRoaming) {
            if ("us".equals(simIsoCountry)) {
                internationalRoaming = !"vi".equals(operatorIsoCountry);
            } else if ("vi".equals(simIsoCountry)) {
                internationalRoaming = !"us".equals(operatorIsoCountry);
            }
        }
        return internationalRoaming;
    }

    private static boolean needToConvert(Context context, int subId) {
        // Calling package may not have READ_PHONE_STATE which is required for getConfig().
        // Clear the calling identity so that it is called as self.
        final long identity = Binder.clearCallingIdentity();
        try {
            CarrierConfigManager configManager = (CarrierConfigManager)
                    context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
            if (configManager != null) {
                PersistableBundle bundle = configManager.getConfigForSubId(subId);
                if (bundle != null) {
                    return bundle.getBoolean(CarrierConfigManager
                            .KEY_SMS_REQUIRES_DESTINATION_NUMBER_CONVERSION_BOOL);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        // by default this value is false
        return false;
    }

    /**
     * Redact personally identifiable information for production users.
     * @param tag used to identify the source of a log message
     * @param pii the personally identifiable information we want to apply secure hash on.
     * @return If tag is loggable in verbose mode or pii is null, return the original input.
     * otherwise return a secure Hash of input pii
     */
    private static String pii(String tag, Object pii) {
        String val = String.valueOf(pii);
        if (pii == null || TextUtils.isEmpty(val) || Log.isLoggable(tag, Log.VERBOSE)) {
            return val;
        }
        return "[" + secureHash(val.getBytes()) + "]";
    }

    /**
     * Returns a secure hash (using the SHA1 algorithm) of the provided input.
     *
     * @return "****" if the build type is user, otherwise the hash
     * @param input the bytes for which the secure hash should be computed.
     */
    private static String secureHash(byte[] input) {
        // Refrain from logging user personal information in user build.
        if (TelephonyUtils.IS_USER) {
            return "****";
        }

        MessageDigest messageDigest;

        try {
            messageDigest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            return "####";
        }

        byte[] result = messageDigest.digest(input);
        return Base64.encodeToString(
                result, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
