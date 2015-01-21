/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.telephony;

import com.android.i18n.phonenumbers.NumberParseException;
import com.android.i18n.phonenumbers.PhoneNumberUtil;
import com.android.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.android.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.android.i18n.phonenumbers.ShortNumberUtil;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.location.CountryDetector;
import android.net.Uri;
import android.os.SystemProperties;
import android.provider.Contacts;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.telephony.Rlog;
import android.util.SparseIntArray;

import static com.android.internal.telephony.PhoneConstants.SUBSCRIPTION_KEY;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_OPERATOR_IDP_STRING;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Various utilities for dealing with phone number strings.
 */
public class PhoneNumberUtils
{
    /*
     * Special characters
     *
     * (See "What is a phone number?" doc)
     * 'p' --- GSM pause character, same as comma
     * 'n' --- GSM wild character
     * 'w' --- GSM wait character
     */
    public static final char PAUSE = ',';
    public static final char WAIT = ';';
    public static final char WILD = 'N';

    /*
     * Calling Line Identification Restriction (CLIR)
     */
    private static final String CLIR_ON = "*31#";
    private static final String CLIR_OFF = "#31#";

    /*
     * TOA = TON + NPI
     * See TS 24.008 section 10.5.4.7 for details.
     * These are the only really useful TOA values
     */
    public static final int TOA_International = 0x91;
    public static final int TOA_Unknown = 0x81;

    static final String LOG_TAG = "PhoneNumberUtils";
    private static final boolean DBG = false;

    /*
     * global-phone-number = ["+"] 1*( DIGIT / written-sep )
     * written-sep         = ("-"/".")
     */
    private static final Pattern GLOBAL_PHONE_NUMBER_PATTERN =
            Pattern.compile("[\\+]?[0-9.-]+");

    /** True if c is ISO-LATIN characters 0-9 */
    public static boolean
    isISODigit (char c) {
        return c >= '0' && c <= '9';
    }

    /** True if c is ISO-LATIN characters 0-9, *, # */
    public final static boolean
    is12Key(char c) {
        return (c >= '0' && c <= '9') || c == '*' || c == '#';
    }

    /** True if c is ISO-LATIN characters 0-9, *, # , +, WILD  */
    public final static boolean
    isDialable(char c) {
        return (c >= '0' && c <= '9') || c == '*' || c == '#' || c == '+' || c == WILD;
    }

    /** True if c is ISO-LATIN characters 0-9, *, # , + (no WILD)  */
    public final static boolean
    isReallyDialable(char c) {
        return (c >= '0' && c <= '9') || c == '*' || c == '#' || c == '+';
    }

    /** True if c is ISO-LATIN characters 0-9, *, # , +, WILD, WAIT, PAUSE   */
    public final static boolean
    isNonSeparator(char c) {
        return (c >= '0' && c <= '9') || c == '*' || c == '#' || c == '+'
                || c == WILD || c == WAIT || c == PAUSE;
    }

    /** This any anything to the right of this char is part of the
     *  post-dial string (eg this is PAUSE or WAIT)
     */
    public final static boolean
    isStartsPostDial (char c) {
        return c == PAUSE || c == WAIT;
    }

    private static boolean
    isPause (char c){
        return c == 'p'||c == 'P';
    }

    private static boolean
    isToneWait (char c){
        return c == 'w'||c == 'W';
    }


    /** Returns true if ch is not dialable or alpha char */
    private static boolean isSeparator(char ch) {
        return !isDialable(ch) && !(('a' <= ch && ch <= 'z') || ('A' <= ch && ch <= 'Z'));
    }

    /** Extracts the phone number from an Intent.
     *
     * @param intent the intent to get the number of
     * @param context a context to use for database access
     *
     * @return the phone number that would be called by the intent, or
     *         <code>null</code> if the number cannot be found.
     */
    public static String getNumberFromIntent(Intent intent, Context context) {
        String number = null;

        Uri uri = intent.getData();

        if (uri == null) {
            return null;
        }

        String scheme = uri.getScheme();

        if (scheme.equals("tel") || scheme.equals("sip")) {
            return uri.getSchemeSpecificPart();
        }

        if (context == null) {
            return null;
        }

        String type = intent.resolveType(context);
        String phoneColumn = null;

        // Correctly read out the phone entry based on requested provider
        final String authority = uri.getAuthority();
        if (Contacts.AUTHORITY.equals(authority)) {
            phoneColumn = Contacts.People.Phones.NUMBER;
        } else if (ContactsContract.AUTHORITY.equals(authority)) {
            phoneColumn = ContactsContract.CommonDataKinds.Phone.NUMBER;
        }

        final Cursor c = context.getContentResolver().query(uri, new String[] {
            phoneColumn
        }, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    number = c.getString(c.getColumnIndex(phoneColumn));
                }
            } finally {
                c.close();
            }
        }

        return number;
    }

    /** Extracts the network address portion and canonicalizes
     *  (filters out separators.)
     *  Network address portion is everything up to DTMF control digit
     *  separators (pause or wait), but without non-dialable characters.
     *
     *  Please note that the GSM wild character is allowed in the result.
     *  This must be resolved before dialing.
     *
     *  Returns null if phoneNumber == null
     */
    public static String
    extractNetworkPortion(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }

        int len = phoneNumber.length();
        StringBuilder ret = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            char c = phoneNumber.charAt(i);
            // Character.digit() supports ASCII and Unicode digits (fullwidth, Arabic-Indic, etc.)
            int digit = Character.digit(c, 10);
            if (digit != -1) {
                ret.append(digit);
            } else if (c == '+') {
                // Allow '+' as first character or after CLIR MMI prefix
                String prefix = ret.toString();
                if (prefix.length() == 0 || prefix.equals(CLIR_ON) || prefix.equals(CLIR_OFF)) {
                    ret.append(c);
                }
            } else if (isDialable(c)) {
                ret.append(c);
            } else if (isStartsPostDial (c)) {
                break;
            }
        }

        return ret.toString();
    }

    /**
     * Extracts the network address portion and canonicalize.
     *
     * This function is equivalent to extractNetworkPortion(), except
     * for allowing the PLUS character to occur at arbitrary positions
     * in the address portion, not just the first position.
     *
     * @hide
     */
    public static String extractNetworkPortionAlt(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }

        int len = phoneNumber.length();
        StringBuilder ret = new StringBuilder(len);
        boolean haveSeenPlus = false;

        for (int i = 0; i < len; i++) {
            char c = phoneNumber.charAt(i);
            if (c == '+') {
                if (haveSeenPlus) {
                    continue;
                }
                haveSeenPlus = true;
            }
            if (isDialable(c)) {
                ret.append(c);
            } else if (isStartsPostDial (c)) {
                break;
            }
        }

        return ret.toString();
    }

    /**
     * Strips separators from a phone number string.
     * @param phoneNumber phone number to strip.
     * @return phone string stripped of separators.
     */
    public static String stripSeparators(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }
        int len = phoneNumber.length();
        StringBuilder ret = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            char c = phoneNumber.charAt(i);
            // Character.digit() supports ASCII and Unicode digits (fullwidth, Arabic-Indic, etc.)
            int digit = Character.digit(c, 10);
            if (digit != -1) {
                ret.append(digit);
            } else if (isNonSeparator(c)) {
                ret.append(c);
            }
        }

        return ret.toString();
    }

    /**
     * Translates keypad letters to actual digits (e.g. 1-800-GOOG-411 will
     * become 1-800-4664-411), and then strips all separators (e.g. 1-800-4664-411 will become
     * 18004664411).
     *
     * @see #convertKeypadLettersToDigits(String)
     * @see #stripSeparators(String)
     *
     * @hide
     */
    public static String convertAndStrip(String phoneNumber) {
        return stripSeparators(convertKeypadLettersToDigits(phoneNumber));
    }

    /**
     * Converts pause and tonewait pause characters
     * to Android representation.
     * RFC 3601 says pause is 'p' and tonewait is 'w'.
     * @hide
     */
    public static String convertPreDial(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }
        int len = phoneNumber.length();
        StringBuilder ret = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            char c = phoneNumber.charAt(i);

            if (isPause(c)) {
                c = PAUSE;
            } else if (isToneWait(c)) {
                c = WAIT;
            }
            ret.append(c);
        }
        return ret.toString();
    }

    /** or -1 if both are negative */
    static private int
    minPositive (int a, int b) {
        if (a >= 0 && b >= 0) {
            return (a < b) ? a : b;
        } else if (a >= 0) { /* && b < 0 */
            return a;
        } else if (b >= 0) { /* && a < 0 */
            return b;
        } else { /* a < 0 && b < 0 */
            return -1;
        }
    }

    private static void log(String msg) {
        Rlog.d(LOG_TAG, msg);
    }
    /** index of the last character of the network portion
     *  (eg anything after is a post-dial string)
     */
    static private int
    indexOfLastNetworkChar(String a) {
        int pIndex, wIndex;
        int origLength;
        int trimIndex;

        origLength = a.length();

        pIndex = a.indexOf(PAUSE);
        wIndex = a.indexOf(WAIT);

        trimIndex = minPositive(pIndex, wIndex);

        if (trimIndex < 0) {
            return origLength - 1;
        } else {
            return trimIndex - 1;
        }
    }

    /**
     * Extracts the post-dial sequence of DTMF control digits, pauses, and
     * waits. Strips separators. This string may be empty, but will not be null
     * unless phoneNumber == null.
     *
     * Returns null if phoneNumber == null
     */

    public static String
    extractPostDialPortion(String phoneNumber) {
        if (phoneNumber == null) return null;

        int trimIndex;
        StringBuilder ret = new StringBuilder();

        trimIndex = indexOfLastNetworkChar (phoneNumber);

        for (int i = trimIndex + 1, s = phoneNumber.length()
                ; i < s; i++
        ) {
            char c = phoneNumber.charAt(i);
            if (isNonSeparator(c)) {
                ret.append(c);
            }
        }

        return ret.toString();
    }

    /**
     * Compare phone numbers a and b, return true if they're identical enough for caller ID purposes.
     */
    public static boolean compare(String a, String b) {
        // We've used loose comparation at least Eclair, which may change in the future.

        return compare(a, b, false);
    }

    /**
     * Compare phone numbers a and b, and return true if they're identical
     * enough for caller ID purposes. Checks a resource to determine whether
     * to use a strict or loose comparison algorithm.
     */
    public static boolean compare(Context context, String a, String b) {
        boolean useStrict = context.getResources().getBoolean(
               com.android.internal.R.bool.config_use_strict_phone_number_comparation);
        return compare(a, b, useStrict);
    }

    /**
     * @hide only for testing.
     */
    public static boolean compare(String a, String b, boolean useStrictComparation) {
        return (useStrictComparation ? compareStrictly(a, b) : compareLoosely(a, b));
    }

    /**
     * Compare phone numbers a and b, return true if they're identical
     * enough for caller ID purposes.
     *
     * - Compares from right to left
     * - requires MIN_MATCH (7) characters to match
     * - handles common trunk prefixes and international prefixes
     *   (basically, everything except the Russian trunk prefix)
     *
     * Note that this method does not return false even when the two phone numbers
     * are not exactly same; rather; we can call this method "similar()", not "equals()".
     *
     * @hide
     */
    public static boolean
    compareLoosely(String a, String b) {
        int ia, ib;
        int matched;
        int numNonDialableCharsInA = 0;
        int numNonDialableCharsInB = 0;

        if (a == null || b == null) return a == b;

        if (a.length() == 0 || b.length() == 0) {
            return false;
        }

        ia = indexOfLastNetworkChar (a);
        ib = indexOfLastNetworkChar (b);
        matched = 0;

        while (ia >= 0 && ib >=0) {
            char ca, cb;
            boolean skipCmp = false;

            ca = a.charAt(ia);

            if (!isDialable(ca)) {
                ia--;
                skipCmp = true;
                numNonDialableCharsInA++;
            }

            cb = b.charAt(ib);

            if (!isDialable(cb)) {
                ib--;
                skipCmp = true;
                numNonDialableCharsInB++;
            }

            if (!skipCmp) {
                if (cb != ca && ca != WILD && cb != WILD) {
                    break;
                }
                ia--; ib--; matched++;
            }
        }

        if (matched < MIN_MATCH) {
            int effectiveALen = a.length() - numNonDialableCharsInA;
            int effectiveBLen = b.length() - numNonDialableCharsInB;


            // if the number of dialable chars in a and b match, but the matched chars < MIN_MATCH,
            // treat them as equal (i.e. 404-04 and 40404)
            if (effectiveALen == effectiveBLen && effectiveALen == matched) {
                return true;
            }

            return false;
        }

        // At least one string has matched completely;
        if (matched >= MIN_MATCH && (ia < 0 || ib < 0)) {
            return true;
        }

        /*
         * Now, what remains must be one of the following for a
         * match:
         *
         *  - a '+' on one and a '00' or a '011' on the other
         *  - a '0' on one and a (+,00)<country code> on the other
         *     (for this, a '0' and a '00' prefix would have succeeded above)
         */

        if (matchIntlPrefix(a, ia + 1)
            && matchIntlPrefix (b, ib +1)
        ) {
            return true;
        }

        if (matchTrunkPrefix(a, ia + 1)
            && matchIntlPrefixAndCC(b, ib +1)
        ) {
            return true;
        }

        if (matchTrunkPrefix(b, ib + 1)
            && matchIntlPrefixAndCC(a, ia +1)
        ) {
            return true;
        }

        return false;
    }

    /**
     * @hide
     */
    public static boolean
    compareStrictly(String a, String b) {
        return compareStrictly(a, b, true);
    }

    /**
     * @hide
     */
    public static boolean
    compareStrictly(String a, String b, boolean acceptInvalidCCCPrefix) {
        if (a == null || b == null) {
            return a == b;
        } else if (a.length() == 0 && b.length() == 0) {
            return false;
        }

        int forwardIndexA = 0;
        int forwardIndexB = 0;

        CountryCallingCodeAndNewIndex cccA =
            tryGetCountryCallingCodeAndNewIndex(a, acceptInvalidCCCPrefix);
        CountryCallingCodeAndNewIndex cccB =
            tryGetCountryCallingCodeAndNewIndex(b, acceptInvalidCCCPrefix);
        boolean bothHasCountryCallingCode = false;
        boolean okToIgnorePrefix = true;
        boolean trunkPrefixIsOmittedA = false;
        boolean trunkPrefixIsOmittedB = false;
        if (cccA != null && cccB != null) {
            if (cccA.countryCallingCode != cccB.countryCallingCode) {
                // Different Country Calling Code. Must be different phone number.
                return false;
            }
            // When both have ccc, do not ignore trunk prefix. Without this,
            // "+81123123" becomes same as "+810123123" (+81 == Japan)
            okToIgnorePrefix = false;
            bothHasCountryCallingCode = true;
            forwardIndexA = cccA.newIndex;
            forwardIndexB = cccB.newIndex;
        } else if (cccA == null && cccB == null) {
            // When both do not have ccc, do not ignore trunk prefix. Without this,
            // "123123" becomes same as "0123123"
            okToIgnorePrefix = false;
        } else {
            if (cccA != null) {
                forwardIndexA = cccA.newIndex;
            } else {
                int tmp = tryGetTrunkPrefixOmittedIndex(b, 0);
                if (tmp >= 0) {
                    forwardIndexA = tmp;
                    trunkPrefixIsOmittedA = true;
                }
            }
            if (cccB != null) {
                forwardIndexB = cccB.newIndex;
            } else {
                int tmp = tryGetTrunkPrefixOmittedIndex(b, 0);
                if (tmp >= 0) {
                    forwardIndexB = tmp;
                    trunkPrefixIsOmittedB = true;
                }
            }
        }

        int backwardIndexA = a.length() - 1;
        int backwardIndexB = b.length() - 1;
        while (backwardIndexA >= forwardIndexA && backwardIndexB >= forwardIndexB) {
            boolean skip_compare = false;
            final char chA = a.charAt(backwardIndexA);
            final char chB = b.charAt(backwardIndexB);
            if (isSeparator(chA)) {
                backwardIndexA--;
                skip_compare = true;
            }
            if (isSeparator(chB)) {
                backwardIndexB--;
                skip_compare = true;
            }

            if (!skip_compare) {
                if (chA != chB) {
                    return false;
                }
                backwardIndexA--;
                backwardIndexB--;
            }
        }

        if (okToIgnorePrefix) {
            if ((trunkPrefixIsOmittedA && forwardIndexA <= backwardIndexA) ||
                !checkPrefixIsIgnorable(a, forwardIndexA, backwardIndexA)) {
                if (acceptInvalidCCCPrefix) {
                    // Maybe the code handling the special case for Thailand makes the
                    // result garbled, so disable the code and try again.
                    // e.g. "16610001234" must equal to "6610001234", but with
                    //      Thailand-case handling code, they become equal to each other.
                    //
                    // Note: we select simplicity rather than adding some complicated
                    //       logic here for performance(like "checking whether remaining
                    //       numbers are just 66 or not"), assuming inputs are small
                    //       enough.
                    return compare(a, b, false);
                } else {
                    return false;
                }
            }
            if ((trunkPrefixIsOmittedB && forwardIndexB <= backwardIndexB) ||
                !checkPrefixIsIgnorable(b, forwardIndexA, backwardIndexB)) {
                if (acceptInvalidCCCPrefix) {
                    return compare(a, b, false);
                } else {
                    return false;
                }
            }
        } else {
            // In the US, 1-650-555-1234 must be equal to 650-555-1234,
            // while 090-1234-1234 must not be equal to 90-1234-1234 in Japan.
            // This request exists just in US (with 1 trunk (NDD) prefix).
            // In addition, "011 11 7005554141" must not equal to "+17005554141",
            // while "011 1 7005554141" must equal to "+17005554141"
            //
            // In this comparison, we ignore the prefix '1' just once, when
            // - at least either does not have CCC, or
            // - the remaining non-separator number is 1
            boolean maybeNamp = !bothHasCountryCallingCode;
            while (backwardIndexA >= forwardIndexA) {
                final char chA = a.charAt(backwardIndexA);
                if (isDialable(chA)) {
                    if (maybeNamp && tryGetISODigit(chA) == 1) {
                        maybeNamp = false;
                    } else {
                        return false;
                    }
                }
                backwardIndexA--;
            }
            while (backwardIndexB >= forwardIndexB) {
                final char chB = b.charAt(backwardIndexB);
                if (isDialable(chB)) {
                    if (maybeNamp && tryGetISODigit(chB) == 1) {
                        maybeNamp = false;
                    } else {
                        return false;
                    }
                }
                backwardIndexB--;
            }
        }

        return true;
    }

    /**
     * Returns the rightmost MIN_MATCH (5) characters in the network portion
     * in *reversed* order
     *
     * This can be used to do a database lookup against the column
     * that stores getStrippedReversed()
     *
     * Returns null if phoneNumber == null
     */
    public static String
    toCallerIDMinMatch(String phoneNumber) {
        String np = extractNetworkPortionAlt(phoneNumber);
        return internalGetStrippedReversed(np, MIN_MATCH);
    }

    /**
     * Returns the network portion reversed.
     * This string is intended to go into an index column for a
     * database lookup.
     *
     * Returns null if phoneNumber == null
     */
    public static String
    getStrippedReversed(String phoneNumber) {
        String np = extractNetworkPortionAlt(phoneNumber);

        if (np == null) return null;

        return internalGetStrippedReversed(np, np.length());
    }

    /**
     * Returns the last numDigits of the reversed phone number
     * Returns null if np == null
     */
    private static String
    internalGetStrippedReversed(String np, int numDigits) {
        if (np == null) return null;

        StringBuilder ret = new StringBuilder(numDigits);
        int length = np.length();

        for (int i = length - 1, s = length
            ; i >= 0 && (s - i) <= numDigits ; i--
        ) {
            char c = np.charAt(i);

            ret.append(c);
        }

        return ret.toString();
    }

    /**
     * Basically: makes sure there's a + in front of a
     * TOA_International number
     *
     * Returns null if s == null
     */
    public static String
    stringFromStringAndTOA(String s, int TOA) {
        if (s == null) return null;

        if (TOA == TOA_International && s.length() > 0 && s.charAt(0) != '+') {
            return "+" + s;
        }

        return s;
    }

    /**
     * Returns the TOA for the given dial string
     * Basically, returns TOA_International if there's a + prefix
     */

    public static int
    toaFromString(String s) {
        if (s != null && s.length() > 0 && s.charAt(0) == '+') {
            return TOA_International;
        }

        return TOA_Unknown;
    }

    /**
     *  3GPP TS 24.008 10.5.4.7
     *  Called Party BCD Number
     *
     *  See Also TS 51.011 10.5.1 "dialing number/ssc string"
     *  and TS 11.11 "10.3.1 EF adn (Abbreviated dialing numbers)"
     *
     * @param bytes the data buffer
     * @param offset should point to the TOA (aka. TON/NPI) octet after the length byte
     * @param length is the number of bytes including TOA byte
     *                and must be at least 2
     *
     * @return partial string on invalid decode
     *
     * FIXME(mkf) support alphanumeric address type
     *  currently implemented in SMSMessage.getAddress()
     */
    public static String
    calledPartyBCDToString (byte[] bytes, int offset, int length) {
        boolean prependPlus = false;
        StringBuilder ret = new StringBuilder(1 + length * 2);

        if (length < 2) {
            return "";
        }

        //Only TON field should be taken in consideration
        if ((bytes[offset] & 0xf0) == (TOA_International & 0xf0)) {
            prependPlus = true;
        }

        internalCalledPartyBCDFragmentToString(
                ret, bytes, offset + 1, length - 1);

        if (prependPlus && ret.length() == 0) {
            // If the only thing there is a prepended plus, return ""
            return "";
        }

        if (prependPlus) {
            // This is an "international number" and should have
            // a plus prepended to the dialing number. But there
            // can also be GSM MMI codes as defined in TS 22.030 6.5.2
            // so we need to handle those also.
            //
            // http://web.telia.com/~u47904776/gsmkode.htm
            // has a nice list of some of these GSM codes.
            //
            // Examples are:
            //   **21*+886988171479#
            //   **21*8311234567#
            //   *21#
            //   #21#
            //   *#21#
            //   *31#+11234567890
            //   #31#+18311234567
            //   #31#8311234567
            //   18311234567
            //   +18311234567#
            //   +18311234567
            // Odd ball cases that some phones handled
            // where there is no dialing number so they
            // append the "+"
            //   *21#+
            //   **21#+
            String retString = ret.toString();
            Pattern p = Pattern.compile("(^[#*])(.*)([#*])(.*)(#)$");
            Matcher m = p.matcher(retString);
            if (m.matches()) {
                if ("".equals(m.group(2))) {
                    // Started with two [#*] ends with #
                    // So no dialing number and we'll just
                    // append a +, this handles **21#+
                    ret = new StringBuilder();
                    ret.append(m.group(1));
                    ret.append(m.group(3));
                    ret.append(m.group(4));
                    ret.append(m.group(5));
                    ret.append("+");
                } else {
                    // Starts with [#*] and ends with #
                    // Assume group 4 is a dialing number
                    // such as *21*+1234554#
                    ret = new StringBuilder();
                    ret.append(m.group(1));
                    ret.append(m.group(2));
                    ret.append(m.group(3));
                    ret.append("+");
                    ret.append(m.group(4));
                    ret.append(m.group(5));
                }
            } else {
                p = Pattern.compile("(^[#*])(.*)([#*])(.*)");
                m = p.matcher(retString);
                if (m.matches()) {
                    // Starts with [#*] and only one other [#*]
                    // Assume the data after last [#*] is dialing
                    // number (i.e. group 4) such as *31#+11234567890.
                    // This also includes the odd ball *21#+
                    ret = new StringBuilder();
                    ret.append(m.group(1));
                    ret.append(m.group(2));
                    ret.append(m.group(3));
                    ret.append("+");
                    ret.append(m.group(4));
                } else {
                    // Does NOT start with [#*] just prepend '+'
                    ret = new StringBuilder();
                    ret.append('+');
                    ret.append(retString);
                }
            }
        }

        return ret.toString();
    }

    private static void
    internalCalledPartyBCDFragmentToString(
        StringBuilder sb, byte [] bytes, int offset, int length) {
        for (int i = offset ; i < length + offset ; i++) {
            byte b;
            char c;

            c = bcdToChar((byte)(bytes[i] & 0xf));

            if (c == 0) {
                return;
            }
            sb.append(c);

            // FIXME(mkf) TS 23.040 9.1.2.3 says
            // "if a mobile receives 1111 in a position prior to
            // the last semi-octet then processing shall commence with
            // the next semi-octet and the intervening
            // semi-octet shall be ignored"
            // How does this jive with 24.008 10.5.4.7

            b = (byte)((bytes[i] >> 4) & 0xf);

            if (b == 0xf && i + 1 == length + offset) {
                //ignore final 0xf
                break;
            }

            c = bcdToChar(b);
            if (c == 0) {
                return;
            }

            sb.append(c);
        }

    }

    /**
     * Like calledPartyBCDToString, but field does not start with a
     * TOA byte. For example: SIM ADN extension fields
     */

    public static String
    calledPartyBCDFragmentToString(byte [] bytes, int offset, int length) {
        StringBuilder ret = new StringBuilder(length * 2);

        internalCalledPartyBCDFragmentToString(ret, bytes, offset, length);

        return ret.toString();
    }

    /** returns 0 on invalid value */
    private static char
    bcdToChar(byte b) {
        if (b < 0xa) {
            return (char)('0' + b);
        } else switch (b) {
            case 0xa: return '*';
            case 0xb: return '#';
            case 0xc: return PAUSE;
            case 0xd: return WILD;

            default: return 0;
        }
    }

    private static int
    charToBCD(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        } else if (c == '*') {
            return 0xa;
        } else if (c == '#') {
            return 0xb;
        } else if (c == PAUSE) {
            return 0xc;
        } else if (c == WILD) {
            return 0xd;
        } else {
            throw new RuntimeException ("invalid char for BCD " + c);
        }
    }

    /**
     * Return true iff the network portion of <code>address</code> is,
     * as far as we can tell on the device, suitable for use as an SMS
     * destination address.
     */
    public static boolean isWellFormedSmsAddress(String address) {
        String networkPortion =
                PhoneNumberUtils.extractNetworkPortion(address);

        return (!(networkPortion.equals("+")
                  || TextUtils.isEmpty(networkPortion)))
               && isDialable(networkPortion);
    }

    public static boolean isGlobalPhoneNumber(String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) {
            return false;
        }

        Matcher match = GLOBAL_PHONE_NUMBER_PATTERN.matcher(phoneNumber);
        return match.matches();
    }

    private static boolean isDialable(String address) {
        for (int i = 0, count = address.length(); i < count; i++) {
            if (!isDialable(address.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNonSeparator(String address) {
        for (int i = 0, count = address.length(); i < count; i++) {
            if (!isNonSeparator(address.charAt(i))) {
                return false;
            }
        }
        return true;
    }
    /**
     * Note: calls extractNetworkPortion(), so do not use for
     * SIM EF[ADN] style records
     *
     * Returns null if network portion is empty.
     */
    public static byte[]
    networkPortionToCalledPartyBCD(String s) {
        String networkPortion = extractNetworkPortion(s);
        return numberToCalledPartyBCDHelper(networkPortion, false);
    }

    /**
     * Same as {@link #networkPortionToCalledPartyBCD}, but includes a
     * one-byte length prefix.
     */
    public static byte[]
    networkPortionToCalledPartyBCDWithLength(String s) {
        String networkPortion = extractNetworkPortion(s);
        return numberToCalledPartyBCDHelper(networkPortion, true);
    }

    /**
     * Convert a dialing number to BCD byte array
     *
     * @param number dialing number string
     *        if the dialing number starts with '+', set to international TOA
     * @return BCD byte array
     */
    public static byte[]
    numberToCalledPartyBCD(String number) {
        return numberToCalledPartyBCDHelper(number, false);
    }

    /**
     * If includeLength is true, prepend a one-byte length value to
     * the return array.
     */
    private static byte[]
    numberToCalledPartyBCDHelper(String number, boolean includeLength) {
        int numberLenReal = number.length();
        int numberLenEffective = numberLenReal;
        boolean hasPlus = number.indexOf('+') != -1;
        if (hasPlus) numberLenEffective--;

        if (numberLenEffective == 0) return null;

        int resultLen = (numberLenEffective + 1) / 2;  // Encoded numbers require only 4 bits each.
        int extraBytes = 1;                            // Prepended TOA byte.
        if (includeLength) extraBytes++;               // Optional prepended length byte.
        resultLen += extraBytes;

        byte[] result = new byte[resultLen];

        int digitCount = 0;
        for (int i = 0; i < numberLenReal; i++) {
            char c = number.charAt(i);
            if (c == '+') continue;
            int shift = ((digitCount & 0x01) == 1) ? 4 : 0;
            result[extraBytes + (digitCount >> 1)] |= (byte)((charToBCD(c) & 0x0F) << shift);
            digitCount++;
        }

        // 1-fill any trailing odd nibble/quartet.
        if ((digitCount & 0x01) == 1) result[extraBytes + (digitCount >> 1)] |= 0xF0;

        int offset = 0;
        if (includeLength) result[offset++] = (byte)(resultLen - 1);
        result[offset] = (byte)(hasPlus ? TOA_International : TOA_Unknown);

        return result;
    }

    //================ Number formatting =========================

    /** The current locale is unknown, look for a country code or don't format */
    public static final int FORMAT_UNKNOWN = 0;
    /** NANP formatting */
    public static final int FORMAT_NANP = 1;
    /** Japanese formatting */
    public static final int FORMAT_JAPAN = 2;

    /** List of country codes for countries that use the NANP */
    private static final String[] NANP_COUNTRIES = new String[] {
        "US", // United States
        "CA", // Canada
        "AS", // American Samoa
        "AI", // Anguilla
        "AG", // Antigua and Barbuda
        "BS", // Bahamas
        "BB", // Barbados
        "BM", // Bermuda
        "VG", // British Virgin Islands
        "KY", // Cayman Islands
        "DM", // Dominica
        "DO", // Dominican Republic
        "GD", // Grenada
        "GU", // Guam
        "JM", // Jamaica
        "PR", // Puerto Rico
        "MS", // Montserrat
        "MP", // Northern Mariana Islands
        "KN", // Saint Kitts and Nevis
        "LC", // Saint Lucia
        "VC", // Saint Vincent and the Grenadines
        "TT", // Trinidad and Tobago
        "TC", // Turks and Caicos Islands
        "VI", // U.S. Virgin Islands
    };

    /**
     * Breaks the given number down and formats it according to the rules
     * for the country the number is from.
     *
     * @param source The phone number to format
     * @return A locally acceptable formatting of the input, or the raw input if
     *  formatting rules aren't known for the number
     *
     * @deprecated Use link #formatNumber(String phoneNumber, String defaultCountryIso) instead
     */
    public static String formatNumber(String source) {
        SpannableStringBuilder text = new SpannableStringBuilder(source);
        formatNumber(text, getFormatTypeForLocale(Locale.getDefault()));
        return text.toString();
    }

    /**
     * Formats the given number with the given formatting type. Currently
     * {@link #FORMAT_NANP} and {@link #FORMAT_JAPAN} are supported as a formating type.
     *
     * @param source the phone number to format
     * @param defaultFormattingType The default formatting rules to apply if the number does
     * not begin with +[country_code]
     * @return The phone number formatted with the given formatting type.
     *
     * @hide
     * @deprecated Use link #formatNumber(String phoneNumber, String defaultCountryIso) instead
     */
    public static String formatNumber(String source, int defaultFormattingType) {
        SpannableStringBuilder text = new SpannableStringBuilder(source);
        formatNumber(text, defaultFormattingType);
        return text.toString();
    }

    /**
     * Returns the phone number formatting type for the given locale.
     *
     * @param locale The locale of interest, usually {@link Locale#getDefault()}
     * @return The formatting type for the given locale, or FORMAT_UNKNOWN if the formatting
     * rules are not known for the given locale
     *
     * @deprecated Use link #formatNumber(String phoneNumber, String defaultCountryIso) instead
     */
    public static int getFormatTypeForLocale(Locale locale) {
        String country = locale.getCountry();

        return getFormatTypeFromCountryCode(country);
    }

    /**
     * Formats a phone number in-place. Currently {@link #FORMAT_JAPAN} and {@link #FORMAT_NANP}
     * is supported as a second argument.
     *
     * @param text The number to be formatted, will be modified with the formatting
     * @param defaultFormattingType The default formatting rules to apply if the number does
     * not begin with +[country_code]
     *
     * @deprecated Use link #formatNumber(String phoneNumber, String defaultCountryIso) instead
     */
    public static void formatNumber(Editable text, int defaultFormattingType) {
        int formatType = defaultFormattingType;

        if (text.length() > 2 && text.charAt(0) == '+') {
            if (text.charAt(1) == '1') {
                formatType = FORMAT_NANP;
            } else if (text.length() >= 3 && text.charAt(1) == '8'
                && text.charAt(2) == '1') {
                formatType = FORMAT_JAPAN;
            } else {
                formatType = FORMAT_UNKNOWN;
            }
        }

        switch (formatType) {
            case FORMAT_NANP:
                formatNanpNumber(text);
                return;
            case FORMAT_JAPAN:
                formatJapaneseNumber(text);
                return;
            case FORMAT_UNKNOWN:
                removeDashes(text);
                return;
        }
    }

    private static final int NANP_STATE_DIGIT = 1;
    private static final int NANP_STATE_PLUS = 2;
    private static final int NANP_STATE_ONE = 3;
    private static final int NANP_STATE_DASH = 4;

    /**
     * Formats a phone number in-place using the NANP formatting rules. Numbers will be formatted
     * as:
     *
     * <p><code>
     * xxxxx
     * xxx-xxxx
     * xxx-xxx-xxxx
     * 1-xxx-xxx-xxxx
     * +1-xxx-xxx-xxxx
     * </code></p>
     *
     * @param text the number to be formatted, will be modified with the formatting
     *
     * @deprecated Use link #formatNumber(String phoneNumber, String defaultCountryIso) instead
     */
    public static void formatNanpNumber(Editable text) {
        int length = text.length();
        if (length > "+1-nnn-nnn-nnnn".length()) {
            // The string is too long to be formatted
            return;
        } else if (length <= 5) {
            // The string is either a shortcode or too short to be formatted
            return;
        }

        CharSequence saved = text.subSequence(0, length);

        // Strip the dashes first, as we're going to add them back
        removeDashes(text);
        length = text.length();

        // When scanning the number we record where dashes need to be added,
        // if they're non-0 at the end of the scan the dashes will be added in
        // the proper places.
        int dashPositions[] = new int[3];
        int numDashes = 0;

        int state = NANP_STATE_DIGIT;
        int numDigits = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '1':
                    if (numDigits == 0 || state == NANP_STATE_PLUS) {
                        state = NANP_STATE_ONE;
                        break;
                    }
                    // fall through
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                case '0':
                    if (state == NANP_STATE_PLUS) {
                        // Only NANP number supported for now
                        text.replace(0, length, saved);
                        return;
                    } else if (state == NANP_STATE_ONE) {
                        // Found either +1 or 1, follow it up with a dash
                        dashPositions[numDashes++] = i;
                    } else if (state != NANP_STATE_DASH && (numDigits == 3 || numDigits == 6)) {
                        // Found a digit that should be after a dash that isn't
                        dashPositions[numDashes++] = i;
                    }
                    state = NANP_STATE_DIGIT;
                    numDigits++;
                    break;

                case '-':
                    state = NANP_STATE_DASH;
                    break;

                case '+':
                    if (i == 0) {
                        // Plus is only allowed as the first character
                        state = NANP_STATE_PLUS;
                        break;
                    }
                    // Fall through
                default:
                    // Unknown character, bail on formatting
                    text.replace(0, length, saved);
                    return;
            }
        }

        if (numDigits == 7) {
            // With 7 digits we want xxx-xxxx, not xxx-xxx-x
            numDashes--;
        }

        // Actually put the dashes in place
        for (int i = 0; i < numDashes; i++) {
            int pos = dashPositions[i];
            text.replace(pos + i, pos + i, "-");
        }

        // Remove trailing dashes
        int len = text.length();
        while (len > 0) {
            if (text.charAt(len - 1) == '-') {
                text.delete(len - 1, len);
                len--;
            } else {
                break;
            }
        }
    }

    /**
     * Formats a phone number in-place using the Japanese formatting rules.
     * Numbers will be formatted as:
     *
     * <p><code>
     * 03-xxxx-xxxx
     * 090-xxxx-xxxx
     * 0120-xxx-xxx
     * +81-3-xxxx-xxxx
     * +81-90-xxxx-xxxx
     * </code></p>
     *
     * @param text the number to be formatted, will be modified with
     * the formatting
     *
     * @deprecated Use link #formatNumber(String phoneNumber, String defaultCountryIso) instead
     */
    public static void formatJapaneseNumber(Editable text) {
        JapanesePhoneNumberFormatter.format(text);
    }

    /**
     * Removes all dashes from the number.
     *
     * @param text the number to clear from dashes
     */
    private static void removeDashes(Editable text) {
        int p = 0;
        while (p < text.length()) {
            if (text.charAt(p) == '-') {
                text.delete(p, p + 1);
           } else {
                p++;
           }
        }
    }

    /**
     * Format the given phoneNumber to the E.164 representation.
     * <p>
     * The given phone number must have an area code and could have a country
     * code.
     * <p>
     * The defaultCountryIso is used to validate the given number and generate
     * the E.164 phone number if the given number doesn't have a country code.
     *
     * @param phoneNumber
     *            the phone number to format
     * @param defaultCountryIso
     *            the ISO 3166-1 two letters country code
     * @return the E.164 representation, or null if the given phone number is
     *         not valid.
     */
    public static String formatNumberToE164(String phoneNumber, String defaultCountryIso) {
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        String result = null;
        try {
            PhoneNumber pn = util.parse(phoneNumber, defaultCountryIso);
            if (util.isValidNumber(pn)) {
                result = util.format(pn, PhoneNumberFormat.E164);
            }
        } catch (NumberParseException e) {
        }
        return result;
    }

    /**
     * Format a phone number.
     * <p>
     * If the given number doesn't have the country code, the phone will be
     * formatted to the default country's convention.
     *
     * @param phoneNumber
     *            the number to be formatted.
     * @param defaultCountryIso
     *            the ISO 3166-1 two letters country code whose convention will
     *            be used if the given number doesn't have the country code.
     * @return the formatted number, or null if the given number is not valid.
     */
    public static String formatNumber(String phoneNumber, String defaultCountryIso) {
        // Do not attempt to format numbers that start with a hash or star symbol.
        if (phoneNumber.startsWith("#") || phoneNumber.startsWith("*")) {
            return phoneNumber;
        }

        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        String result = null;
        try {
            PhoneNumber pn = util.parseAndKeepRawInput(phoneNumber, defaultCountryIso);
            result = util.formatInOriginalFormat(pn, defaultCountryIso);
        } catch (NumberParseException e) {
        }
        return result;
    }

    /**
     * Format the phone number only if the given number hasn't been formatted.
     * <p>
     * The number which has only dailable character is treated as not being
     * formatted.
     *
     * @param phoneNumber
     *            the number to be formatted.
     * @param phoneNumberE164
     *            the E164 format number whose country code is used if the given
     *            phoneNumber doesn't have the country code.
     * @param defaultCountryIso
     *            the ISO 3166-1 two letters country code whose convention will
     *            be used if the phoneNumberE164 is null or invalid, or if phoneNumber
     *            contains IDD.
     * @return the formatted number if the given number has been formatted,
     *            otherwise, return the given number.
     */
    public static String formatNumber(
            String phoneNumber, String phoneNumberE164, String defaultCountryIso) {
        int len = phoneNumber.length();
        for (int i = 0; i < len; i++) {
            if (!isDialable(phoneNumber.charAt(i))) {
                return phoneNumber;
            }
        }
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        // Get the country code from phoneNumberE164
        if (phoneNumberE164 != null && phoneNumberE164.length() >= 2
                && phoneNumberE164.charAt(0) == '+') {
            try {
                // The number to be parsed is in E164 format, so the default region used doesn't
                // matter.
                PhoneNumber pn = util.parse(phoneNumberE164, "ZZ");
                String regionCode = util.getRegionCodeForNumber(pn);
                if (!TextUtils.isEmpty(regionCode) &&
                    // This makes sure phoneNumber doesn't contain an IDD
                    normalizeNumber(phoneNumber).indexOf(phoneNumberE164.substring(1)) <= 0) {
                    defaultCountryIso = regionCode;
                }
            } catch (NumberParseException e) {
            }
        }
        String result = formatNumber(phoneNumber, defaultCountryIso);
        return result != null ? result : phoneNumber;
    }

    /**
     * Normalize a phone number by removing the characters other than digits. If
     * the given number has keypad letters, the letters will be converted to
     * digits first.
     *
     * @param phoneNumber the number to be normalized.
     * @return the normalized number.
     */
    public static String normalizeNumber(String phoneNumber) {
        if (TextUtils.isEmpty(phoneNumber)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int len = phoneNumber.length();
        for (int i = 0; i < len; i++) {
            char c = phoneNumber.charAt(i);
            // Character.digit() supports ASCII and Unicode digits (fullwidth, Arabic-Indic, etc.)
            int digit = Character.digit(c, 10);
            if (digit != -1) {
                sb.append(digit);
            } else if (i == 0 && c == '+') {
                sb.append(c);
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return normalizeNumber(PhoneNumberUtils.convertKeypadLettersToDigits(phoneNumber));
            }
        }
        return sb.toString();
    }

    /**
     * Replaces all unicode(e.g. Arabic, Persian) digits with their decimal digit equivalents.
     *
     * @param number the number to perform the replacement on.
     * @return the replaced number.
     */
    public static String replaceUnicodeDigits(String number) {
        StringBuilder normalizedDigits = new StringBuilder(number.length());
        for (char c : number.toCharArray()) {
            int digit = Character.digit(c, 10);
            if (digit != -1) {
                normalizedDigits.append(digit);
            } else {
                normalizedDigits.append(c);
            }
        }
        return normalizedDigits.toString();
    }

    // Three and four digit phone numbers for either special services,
    // or 3-6 digit addresses from the network (eg carrier-originated SMS messages) should
    // not match.
    //
    // This constant used to be 5, but SMS short codes has increased in length and
    // can be easily 6 digits now days. Most countries have SMS short code length between
    // 3 to 6 digits. The exceptions are
    //
    // Australia: Short codes are six or eight digits in length, starting with the prefix "19"
    //            followed by an additional four or six digits and two.
    // Czech Republic: Codes are seven digits in length for MO and five (not billed) or
    //            eight (billed) for MT direction
    //
    // see http://en.wikipedia.org/wiki/Short_code#Regional_differences for reference
    //
    // However, in order to loose match 650-555-1212 and 555-1212, we need to set the min match
    // to 7.
    static final int MIN_MATCH = 7;

    /**
     * Checks a given number against the list of
     * emergency numbers provided by the RIL and SIM card.
     *
     * @param number the number to look up.
     * @return true if the number is in the list of emergency numbers
     *         listed in the RIL / SIM, otherwise return false.
     */
    public static boolean isEmergencyNumber(String number) {
        return isEmergencyNumber(getDefaultVoiceSubId(), number);
    }

    /**
     * Checks a given number against the list of
     * emergency numbers provided by the RIL and SIM card.
     *
     * @param subId the subscription id of the SIM.
     * @param number the number to look up.
     * @return true if the number is in the list of emergency numbers
     *         listed in the RIL / SIM, otherwise return false.
     * @hide
     */
    public static boolean isEmergencyNumber(long subId, String number) {
        // Return true only if the specified number *exactly* matches
        // one of the emergency numbers listed by the RIL / SIM.
        return isEmergencyNumberInternal(subId, number, true /* useExactMatch */);
    }

    /**
     * Checks if given number might *potentially* result in
     * a call to an emergency service on the current network.
     *
     * Specifically, this method will return true if the specified number
     * is an emergency number according to the list managed by the RIL or
     * SIM, *or* if the specified number simply starts with the same
     * digits as any of the emergency numbers listed in the RIL / SIM.
     *
     * This method is intended for internal use by the phone app when
     * deciding whether to allow ACTION_CALL intents from 3rd party apps
     * (where we're required to *not* allow emergency calls to be placed.)
     *
     * @param number the number to look up.
     * @return true if the number is in the list of emergency numbers
     *         listed in the RIL / SIM, *or* if the number starts with the
     *         same digits as any of those emergency numbers.
     *
     * @hide
     */
    public static boolean isPotentialEmergencyNumber(String number) {
        return isPotentialEmergencyNumber(getDefaultVoiceSubId(), number);
    }

    /**
     * Checks if given number might *potentially* result in
     * a call to an emergency service on the current network.
     *
     * Specifically, this method will return true if the specified number
     * is an emergency number according to the list managed by the RIL or
     * SIM, *or* if the specified number simply starts with the same
     * digits as any of the emergency numbers listed in the RIL / SIM.
     *
     * This method is intended for internal use by the phone app when
     * deciding whether to allow ACTION_CALL intents from 3rd party apps
     * (where we're required to *not* allow emergency calls to be placed.)
     *
     * @param subId the subscription id of the SIM.
     * @param number the number to look up.
     * @return true if the number is in the list of emergency numbers
     *         listed in the RIL / SIM, *or* if the number starts with the
     *         same digits as any of those emergency numbers.
     * @hide
     */
    public static boolean isPotentialEmergencyNumber(long subId, String number) {
        // Check against the emergency numbers listed by the RIL / SIM,
        // and *don't* require an exact match.
        return isEmergencyNumberInternal(subId, number, false /* useExactMatch */);
    }

    /**
     * Helper function for isEmergencyNumber(String) and
     * isPotentialEmergencyNumber(String).
     *
     * @param number the number to look up.
     *
     * @param useExactMatch if true, consider a number to be an emergency
     *           number only if it *exactly* matches a number listed in
     *           the RIL / SIM.  If false, a number is considered to be an
     *           emergency number if it simply starts with the same digits
     *           as any of the emergency numbers listed in the RIL / SIM.
     *           (Setting useExactMatch to false allows you to identify
     *           number that could *potentially* result in emergency calls
     *           since many networks will actually ignore trailing digits
     *           after a valid emergency number.)
     *
     * @return true if the number is in the list of emergency numbers
     *         listed in the RIL / sim, otherwise return false.
     */
    private static boolean isEmergencyNumberInternal(String number, boolean useExactMatch) {
        return isEmergencyNumberInternal(getDefaultVoiceSubId(), number, useExactMatch);
    }

    /**
     * Helper function for isEmergencyNumber(String) and
     * isPotentialEmergencyNumber(String).
     *
     * @param subId the subscription id of the SIM.
     * @param number the number to look up.
     *
     * @param useExactMatch if true, consider a number to be an emergency
     *           number only if it *exactly* matches a number listed in
     *           the RIL / SIM.  If false, a number is considered to be an
     *           emergency number if it simply starts with the same digits
     *           as any of the emergency numbers listed in the RIL / SIM.
     *           (Setting useExactMatch to false allows you to identify
     *           number that could *potentially* result in emergency calls
     *           since many networks will actually ignore trailing digits
     *           after a valid emergency number.)
     *
     * @return true if the number is in the list of emergency numbers
     *         listed in the RIL / sim, otherwise return false.
     */
    private static boolean isEmergencyNumberInternal(long subId, String number,
            boolean useExactMatch) {
        return isEmergencyNumberInternal(subId, number, null, useExactMatch);
    }

    /**
     * Checks if a given number is an emergency number for a specific country.
     *
     * @param number the number to look up.
     * @param defaultCountryIso the specific country which the number should be checked against
     * @return if the number is an emergency number for the specific country, then return true,
     * otherwise false
     *
     * @hide
     */
    public static boolean isEmergencyNumber(String number, String defaultCountryIso) {
            return isEmergencyNumber(getDefaultVoiceSubId(), number, defaultCountryIso);
    }

    /**
     * Checks if a given number is an emergency number for a specific country.
     *
     * @param subId the subscription id of the SIM.
     * @param number the number to look up.
     * @param defaultCountryIso the specific country which the number should be checked against
     * @return if the number is an emergency number for the specific country, then return true,
     * otherwise false
     * @hide
     */
    public static boolean isEmergencyNumber(long subId, String number, String defaultCountryIso) {
        return isEmergencyNumberInternal(subId, number,
                                         defaultCountryIso,
                                         true /* useExactMatch */);
    }

    /**
     * Checks if a given number might *potentially* result in a call to an
     * emergency service, for a specific country.
     *
     * Specifically, this method will return true if the specified number
     * is an emergency number in the specified country, *or* if the number
     * simply starts with the same digits as any emergency number for that
     * country.
     *
     * This method is intended for internal use by the phone app when
     * deciding whether to allow ACTION_CALL intents from 3rd party apps
     * (where we're required to *not* allow emergency calls to be placed.)
     *
     * @param number the number to look up.
     * @param defaultCountryIso the specific country which the number should be checked against
     * @return true if the number is an emergency number for the specific
     *         country, *or* if the number starts with the same digits as
     *         any of those emergency numbers.
     *
     * @hide
     */
    public static boolean isPotentialEmergencyNumber(String number, String defaultCountryIso) {
        return isPotentialEmergencyNumber(getDefaultVoiceSubId(), number, defaultCountryIso);
    }

    /**
     * Checks if a given number might *potentially* result in a call to an
     * emergency service, for a specific country.
     *
     * Specifically, this method will return true if the specified number
     * is an emergency number in the specified country, *or* if the number
     * simply starts with the same digits as any emergency number for that
     * country.
     *
     * This method is intended for internal use by the phone app when
     * deciding whether to allow ACTION_CALL intents from 3rd party apps
     * (where we're required to *not* allow emergency calls to be placed.)
     *
     * @param subId the subscription id of the SIM.
     * @param number the number to look up.
     * @param defaultCountryIso the specific country which the number should be checked against
     * @return true if the number is an emergency number for the specific
     *         country, *or* if the number starts with the same digits as
     *         any of those emergency numbers.
     * @hide
     */
    public static boolean isPotentialEmergencyNumber(long subId, String number,
            String defaultCountryIso) {
        return isEmergencyNumberInternal(subId, number,
                                         defaultCountryIso,
                                         false /* useExactMatch */);
    }

    /**
     * Helper function for isEmergencyNumber(String, String) and
     * isPotentialEmergencyNumber(String, String).
     *
     * @param number the number to look up.
     * @param defaultCountryIso the specific country which the number should be checked against
     * @param useExactMatch if true, consider a number to be an emergency
     *           number only if it *exactly* matches a number listed in
     *           the RIL / SIM.  If false, a number is considered to be an
     *           emergency number if it simply starts with the same digits
     *           as any of the emergency numbers listed in the RIL / SIM.
     *
     * @return true if the number is an emergency number for the specified country.
     */
    private static boolean isEmergencyNumberInternal(String number,
                                                     String defaultCountryIso,
                                                     boolean useExactMatch) {
        return isEmergencyNumberInternal(getDefaultVoiceSubId(), number, defaultCountryIso,
                useExactMatch);
    }

    /**
     * Helper function for isEmergencyNumber(String, String) and
     * isPotentialEmergencyNumber(String, String).
     *
     * @param subId the subscription id of the SIM.
     * @param number the number to look up.
     * @param defaultCountryIso the specific country which the number should be checked against
     * @param useExactMatch if true, consider a number to be an emergency
     *           number only if it *exactly* matches a number listed in
     *           the RIL / SIM.  If false, a number is considered to be an
     *           emergency number if it simply starts with the same digits
     *           as any of the emergency numbers listed in the RIL / SIM.
     *
     * @return true if the number is an emergency number for the specified country.
     * @hide
     */
    private static boolean isEmergencyNumberInternal(long subId, String number,
                                                     String defaultCountryIso,
                                                     boolean useExactMatch) {
        // If the number passed in is null, just return false:
        if (number == null) return false;

        // If the number passed in is a SIP address, return false, since the
        // concept of "emergency numbers" is only meaningful for calls placed
        // over the cell network.
        // (Be sure to do this check *before* calling extractNetworkPortionAlt(),
        // since the whole point of extractNetworkPortionAlt() is to filter out
        // any non-dialable characters (which would turn 'abc911def@example.com'
        // into '911', for example.))
        if (isUriNumber(number)) {
            return false;
        }

        // Strip the separators from the number before comparing it
        // to the list.
        number = extractNetworkPortionAlt(number);

        String numbers = "";
        int slotId = SubscriptionManager.getSlotId(subId);
        // retrieve the list of emergency numbers
        // check read-write ecclist property first
        String ecclist = (slotId <= 0) ? "ril.ecclist" : ("ril.ecclist" + slotId);

        numbers = SystemProperties.get(ecclist);

        if (TextUtils.isEmpty(numbers)) {
            // then read-only ecclist property since old RIL only uses this
            numbers = SystemProperties.get("ro.ril.ecclist");
        }

        if (!TextUtils.isEmpty(numbers)) {
            // searches through the comma-separated list for a match,
            // return true if one is found.
            for (String emergencyNum : numbers.split(",")) {
                // It is not possible to append additional digits to an emergency number to dial
                // the number in Brazil - it won't connect.
                if (useExactMatch || "BR".equalsIgnoreCase(defaultCountryIso)) {
                    if (number.equals(emergencyNum)) {
                        return true;
                    }
                } else {
                    if (number.startsWith(emergencyNum)) {
                        return true;
                    }
                }
            }
            // no matches found against the list!
            return false;
        }

        Rlog.d(LOG_TAG, "System property doesn't provide any emergency numbers."
                + " Use embedded logic for determining ones.");

        // No ecclist system property, so use our own list.
        if (defaultCountryIso != null) {
            ShortNumberUtil util = new ShortNumberUtil();
            if (useExactMatch) {
                return util.isEmergencyNumber(number, defaultCountryIso);
            } else {
                return util.connectsToEmergencyNumber(number, defaultCountryIso);
            }
        } else {
            if (useExactMatch) {
                return (number.equals("112") || number.equals("911"));
            } else {
                return (number.startsWith("112") || number.startsWith("911"));
            }
        }
    }

    /**
     * Checks if a given number is an emergency number for the country that the user is in.
     *
     * @param number the number to look up.
     * @param context the specific context which the number should be checked against
     * @return true if the specified number is an emergency number for the country the user
     * is currently in.
     */
    public static boolean isLocalEmergencyNumber(Context context, String number) {
        return isLocalEmergencyNumber(context, getDefaultVoiceSubId(), number);
    }

    /**
     * Checks if a given number is an emergency number for the country that the user is in.
     *
     * @param subId the subscription id of the SIM.
     * @param number the number to look up.
     * @param context the specific context which the number should be checked against
     * @return true if the specified number is an emergency number for the country the user
     * is currently in.
     * @hide
     */
    public static boolean isLocalEmergencyNumber(Context context, long subId, String number) {
        return isLocalEmergencyNumberInternal(subId, number,
                                              context,
                                              true /* useExactMatch */);
    }

    /**
     * Checks if a given number might *potentially* result in a call to an
     * emergency service, for the country that the user is in. The current
     * country is determined using the CountryDetector.
     *
     * Specifically, this method will return true if the specified number
     * is an emergency number in the current country, *or* if the number
     * simply starts with the same digits as any emergency number for the
     * current country.
     *
     * This method is intended for internal use by the phone app when
     * deciding whether to allow ACTION_CALL intents from 3rd party apps
     * (where we're required to *not* allow emergency calls to be placed.)
     *
     * @param number the number to look up.
     * @param context the specific context which the number should be checked against
     * @return true if the specified number is an emergency number for a local country, based on the
     *              CountryDetector.
     *
     * @see android.location.CountryDetector
     * @hide
     */
    public static boolean isPotentialLocalEmergencyNumber(Context context, String number) {
        return isPotentialLocalEmergencyNumber(context, getDefaultVoiceSubId(), number);
    }

    /**
     * Checks if a given number might *potentially* result in a call to an
     * emergency service, for the country that the user is in. The current
     * country is determined using the CountryDetector.
     *
     * Specifically, this method will return true if the specified number
     * is an emergency number in the current country, *or* if the number
     * simply starts with the same digits as any emergency number for the
     * current country.
     *
     * This method is intended for internal use by the phone app when
     * deciding whether to allow ACTION_CALL intents from 3rd party apps
     * (where we're required to *not* allow emergency calls to be placed.)
     *
     * @param subId the subscription id of the SIM.
     * @param number the number to look up.
     * @param context the specific context which the number should be checked against
     * @return true if the specified number is an emergency number for a local country, based on the
     *              CountryDetector.
     *
     * @hide
     */
    public static boolean isPotentialLocalEmergencyNumber(Context context, long subId,
            String number) {
        return isLocalEmergencyNumberInternal(subId, number,
                                              context,
                                              false /* useExactMatch */);
    }

    /**
     * Helper function for isLocalEmergencyNumber() and
     * isPotentialLocalEmergencyNumber().
     *
     * @param number the number to look up.
     * @param context the specific context which the number should be checked against
     * @param useExactMatch if true, consider a number to be an emergency
     *           number only if it *exactly* matches a number listed in
     *           the RIL / SIM.  If false, a number is considered to be an
     *           emergency number if it simply starts with the same digits
     *           as any of the emergency numbers listed in the RIL / SIM.
     *
     * @return true if the specified number is an emergency number for a
     *              local country, based on the CountryDetector.
     *
     * @see android.location.CountryDetector
     * @hide
     */
    private static boolean isLocalEmergencyNumberInternal(String number,
                                                          Context context,
                                                          boolean useExactMatch) {
        return isLocalEmergencyNumberInternal(getDefaultVoiceSubId(), number, context,
                useExactMatch);
    }

    /**
     * Helper function for isLocalEmergencyNumber() and
     * isPotentialLocalEmergencyNumber().
     *
     * @param subId the subscription id of the SIM.
     * @param number the number to look up.
     * @param context the specific context which the number should be checked against
     * @param useExactMatch if true, consider a number to be an emergency
     *           number only if it *exactly* matches a number listed in
     *           the RIL / SIM.  If false, a number is considered to be an
     *           emergency number if it simply starts with the same digits
     *           as any of the emergency numbers listed in the RIL / SIM.
     *
     * @return true if the specified number is an emergency number for a
     *              local country, based on the CountryDetector.
     * @hide
     */
    private static boolean isLocalEmergencyNumberInternal(long subId, String number,
                                                          Context context,
                                                          boolean useExactMatch) {
        String countryIso;
        CountryDetector detector = (CountryDetector) context.getSystemService(
                Context.COUNTRY_DETECTOR);
        if (detector != null && detector.detectCountry() != null) {
            countryIso = detector.detectCountry().getCountryIso();
        } else {
            Locale locale = context.getResources().getConfiguration().locale;
            countryIso = locale.getCountry();
            Rlog.w(LOG_TAG, "No CountryDetector; falling back to countryIso based on locale: "
                    + countryIso);
        }
        return isEmergencyNumberInternal(subId, number, countryIso, useExactMatch);
    }

    /**
     * isVoiceMailNumber: checks a given number against the voicemail
     *   number provided by the RIL and SIM card. The caller must have
     *   the READ_PHONE_STATE credential.
     *
     * @param number the number to look up.
     * @return true if the number is in the list of voicemail. False
     * otherwise, including if the caller does not have the permission
     * to read the VM number.
     */
    public static boolean isVoiceMailNumber(String number) {
        return isVoiceMailNumber(SubscriptionManager.getDefaultSubId(), number);
    }

    /**
     * isVoiceMailNumber: checks a given number against the voicemail
     *   number provided by the RIL and SIM card. The caller must have
     *   the READ_PHONE_STATE credential.
     *
     * @param subId the subscription id of the SIM.
     * @param number the number to look up.
     * @return true if the number is in the list of voicemail. False
     * otherwise, including if the caller does not have the permission
     * to read the VM number.
     * @hide
     */
    public static boolean isVoiceMailNumber(long subId, String number) {
        String vmNumber;

        try {
            vmNumber = TelephonyManager.getDefault().getVoiceMailNumber(subId);
        } catch (SecurityException ex) {
            return false;
        }

        // Strip the separators from the number before comparing it
        // to the list.
        number = extractNetworkPortionAlt(number);

        // compare tolerates null so we need to make sure that we
        // don't return true when both are null.
        return !TextUtils.isEmpty(number) && compare(number, vmNumber);
    }

    /**
     * Translates any alphabetic letters (i.e. [A-Za-z]) in the
     * specified phone number into the equivalent numeric digits,
     * according to the phone keypad letter mapping described in
     * ITU E.161 and ISO/IEC 9995-8.
     *
     * @return the input string, with alpha letters converted to numeric
     *         digits using the phone keypad letter mapping.  For example,
     *         an input of "1-800-GOOG-411" will return "1-800-4664-411".
     */
    public static String convertKeypadLettersToDigits(String input) {
        if (input == null) {
            return input;
        }
        int len = input.length();
        if (len == 0) {
            return input;
        }

        char[] out = input.toCharArray();

        for (int i = 0; i < len; i++) {
            char c = out[i];
            // If this char isn't in KEYPAD_MAP at all, just leave it alone.
            out[i] = (char) KEYPAD_MAP.get(c, c);
        }

        return new String(out);
    }

    /**
     * The phone keypad letter mapping (see ITU E.161 or ISO/IEC 9995-8.)
     * TODO: This should come from a resource.
     */
    private static final SparseIntArray KEYPAD_MAP = new SparseIntArray();
    static {
        KEYPAD_MAP.put('a', '2'); KEYPAD_MAP.put('b', '2'); KEYPAD_MAP.put('c', '2');
        KEYPAD_MAP.put('A', '2'); KEYPAD_MAP.put('B', '2'); KEYPAD_MAP.put('C', '2');

        KEYPAD_MAP.put('d', '3'); KEYPAD_MAP.put('e', '3'); KEYPAD_MAP.put('f', '3');
        KEYPAD_MAP.put('D', '3'); KEYPAD_MAP.put('E', '3'); KEYPAD_MAP.put('F', '3');

        KEYPAD_MAP.put('g', '4'); KEYPAD_MAP.put('h', '4'); KEYPAD_MAP.put('i', '4');
        KEYPAD_MAP.put('G', '4'); KEYPAD_MAP.put('H', '4'); KEYPAD_MAP.put('I', '4');

        KEYPAD_MAP.put('j', '5'); KEYPAD_MAP.put('k', '5'); KEYPAD_MAP.put('l', '5');
        KEYPAD_MAP.put('J', '5'); KEYPAD_MAP.put('K', '5'); KEYPAD_MAP.put('L', '5');

        KEYPAD_MAP.put('m', '6'); KEYPAD_MAP.put('n', '6'); KEYPAD_MAP.put('o', '6');
        KEYPAD_MAP.put('M', '6'); KEYPAD_MAP.put('N', '6'); KEYPAD_MAP.put('O', '6');

        KEYPAD_MAP.put('p', '7'); KEYPAD_MAP.put('q', '7'); KEYPAD_MAP.put('r', '7'); KEYPAD_MAP.put('s', '7');
        KEYPAD_MAP.put('P', '7'); KEYPAD_MAP.put('Q', '7'); KEYPAD_MAP.put('R', '7'); KEYPAD_MAP.put('S', '7');

        KEYPAD_MAP.put('t', '8'); KEYPAD_MAP.put('u', '8'); KEYPAD_MAP.put('v', '8');
        KEYPAD_MAP.put('T', '8'); KEYPAD_MAP.put('U', '8'); KEYPAD_MAP.put('V', '8');

        KEYPAD_MAP.put('w', '9'); KEYPAD_MAP.put('x', '9'); KEYPAD_MAP.put('y', '9'); KEYPAD_MAP.put('z', '9');
        KEYPAD_MAP.put('W', '9'); KEYPAD_MAP.put('X', '9'); KEYPAD_MAP.put('Y', '9'); KEYPAD_MAP.put('Z', '9');
    }

    //================ Plus Code formatting =========================
    private static final char PLUS_SIGN_CHAR = '+';
    private static final String PLUS_SIGN_STRING = "+";
    private static final String NANP_IDP_STRING = "011";
    private static final int NANP_LENGTH = 10;

    /**
     * This function checks if there is a plus sign (+) in the passed-in dialing number.
     * If there is, it processes the plus sign based on the default telephone
     * numbering plan of the system when the phone is activated and the current
     * telephone numbering plan of the system that the phone is camped on.
     * Currently, we only support the case that the default and current telephone
     * numbering plans are North American Numbering Plan(NANP).
     *
     * The passed-in dialStr should only contain the valid format as described below,
     * 1) the 1st character in the dialStr should be one of the really dialable
     *    characters listed below
     *    ISO-LATIN characters 0-9, *, # , +
     * 2) the dialStr should already strip out the separator characters,
     *    every character in the dialStr should be one of the non separator characters
     *    listed below
     *    ISO-LATIN characters 0-9, *, # , +, WILD, WAIT, PAUSE
     *
     * Otherwise, this function returns the dial string passed in
     *
     * @param dialStr the original dial string
     * @return the converted dial string if the current/default countries belong to NANP,
     * and if there is the "+" in the original dial string. Otherwise, the original dial
     * string returns.
     *
     * This API is for CDMA only
     *
     * @hide TODO: pending API Council approval
     */
    public static String cdmaCheckAndProcessPlusCode(String dialStr) {
        if (!TextUtils.isEmpty(dialStr)) {
            if (isReallyDialable(dialStr.charAt(0)) &&
                isNonSeparator(dialStr)) {
                String currIso = SystemProperties.get(PROPERTY_OPERATOR_ISO_COUNTRY, "");
                String defaultIso = SystemProperties.get(PROPERTY_ICC_OPERATOR_ISO_COUNTRY, "");
                if (!TextUtils.isEmpty(currIso) && !TextUtils.isEmpty(defaultIso)) {
                    return cdmaCheckAndProcessPlusCodeByNumberFormat(dialStr,
                            getFormatTypeFromCountryCode(currIso),
                            getFormatTypeFromCountryCode(defaultIso));
                }
            }
        }
        return dialStr;
    }

    /**
     * Process phone number for CDMA, converting plus code using the home network number format.
     * This is used for outgoing SMS messages.
     *
     * @param dialStr the original dial string
     * @return the converted dial string
     * @hide for internal use
     */
    public static String cdmaCheckAndProcessPlusCodeForSms(String dialStr) {
        if (!TextUtils.isEmpty(dialStr)) {
            if (isReallyDialable(dialStr.charAt(0)) && isNonSeparator(dialStr)) {
                String defaultIso = SystemProperties.get(PROPERTY_ICC_OPERATOR_ISO_COUNTRY, "");
                if (!TextUtils.isEmpty(defaultIso)) {
                    int format = getFormatTypeFromCountryCode(defaultIso);
                    return cdmaCheckAndProcessPlusCodeByNumberFormat(dialStr, format, format);
                }
            }
        }
        return dialStr;
    }

    /**
     * This function should be called from checkAndProcessPlusCode only
     * And it is used for test purpose also.
     *
     * It checks the dial string by looping through the network portion,
     * post dial portion 1, post dial porting 2, etc. If there is any
     * plus sign, then process the plus sign.
     * Currently, this function supports the plus sign conversion within NANP only.
     * Specifically, it handles the plus sign in the following ways:
     * 1)+1NANP,remove +, e.g.
     *   +18475797000 is converted to 18475797000,
     * 2)+NANP or +non-NANP Numbers,replace + with the current NANP IDP, e.g,
     *   +8475797000 is converted to 0118475797000,
     *   +11875767800 is converted to 01111875767800
     * 3)+1NANP in post dial string(s), e.g.
     *   8475797000;+18475231753 is converted to 8475797000;18475231753
     *
     *
     * @param dialStr the original dial string
     * @param currFormat the numbering system of the current country that the phone is camped on
     * @param defaultFormat the numbering system of the country that the phone is activated on
     * @return the converted dial string if the current/default countries belong to NANP,
     * and if there is the "+" in the original dial string. Otherwise, the original dial
     * string returns.
     *
     * @hide
     */
    public static String
    cdmaCheckAndProcessPlusCodeByNumberFormat(String dialStr,int currFormat,int defaultFormat) {
        String retStr = dialStr;

        boolean useNanp = (currFormat == defaultFormat) && (currFormat == FORMAT_NANP);

        // Checks if the plus sign character is in the passed-in dial string
        if (dialStr != null &&
            dialStr.lastIndexOf(PLUS_SIGN_STRING) != -1) {

            // Handle case where default and current telephone numbering plans are NANP.
            String postDialStr = null;
            String tempDialStr = dialStr;

            // Sets the retStr to null since the conversion will be performed below.
            retStr = null;
            if (DBG) log("checkAndProcessPlusCode,dialStr=" + dialStr);
            // This routine is to process the plus sign in the dial string by loop through
            // the network portion, post dial portion 1, post dial portion 2... etc. if
            // applied
            do {
                String networkDialStr;
                // Format the string based on the rules for the country the number is from,
                // and the current country the phone is camped
                if (useNanp) {
                    networkDialStr = extractNetworkPortion(tempDialStr);
                } else  {
                    networkDialStr = extractNetworkPortionAlt(tempDialStr);

                }

                networkDialStr = processPlusCode(networkDialStr, useNanp);

                // Concatenates the string that is converted from network portion
                if (!TextUtils.isEmpty(networkDialStr)) {
                    if (retStr == null) {
                        retStr = networkDialStr;
                    } else {
                        retStr = retStr.concat(networkDialStr);
                    }
                } else {
                    // This should never happen since we checked the if dialStr is null
                    // and if it contains the plus sign in the beginning of this function.
                    // The plus sign is part of the network portion.
                    Rlog.e("checkAndProcessPlusCode: null newDialStr", networkDialStr);
                    return dialStr;
                }
                postDialStr = extractPostDialPortion(tempDialStr);
                if (!TextUtils.isEmpty(postDialStr)) {
                    int dialableIndex = findDialableIndexFromPostDialStr(postDialStr);

                    // dialableIndex should always be greater than 0
                    if (dialableIndex >= 1) {
                        retStr = appendPwCharBackToOrigDialStr(dialableIndex,
                                 retStr,postDialStr);
                        // Skips the P/W character, extracts the dialable portion
                        tempDialStr = postDialStr.substring(dialableIndex);
                    } else {
                        // Non-dialable character such as P/W should not be at the end of
                        // the dial string after P/W processing in CdmaConnection.java
                        // Set the postDialStr to "" to break out of the loop
                        if (dialableIndex < 0) {
                            postDialStr = "";
                        }
                        Rlog.e("wrong postDialStr=", postDialStr);
                    }
                }
                if (DBG) log("checkAndProcessPlusCode,postDialStr=" + postDialStr);
            } while (!TextUtils.isEmpty(postDialStr) && !TextUtils.isEmpty(tempDialStr));
        }
        return retStr;
    }

    private static String getCurrentIdp(boolean useNanp) {
        String ps = null;
        if(useNanp)
            ps = NANP_IDP_STRING;
        else{
            // in case, there is no IDD is found, we shouldn't convert it.
            ps = SystemProperties.get(PROPERTY_OPERATOR_IDP_STRING, PLUS_SIGN_STRING);                
        }
        return ps;
    }

    private static boolean isTwoToNine (char c) {
        if (c >= '2' && c <= '9') {
            return true;
        } else {
            return false;
        }
    }

    private static int getFormatTypeFromCountryCode (String country) {
        // Check for the NANP countries
        int length = NANP_COUNTRIES.length;
        for (int i = 0; i < length; i++) {
            if (NANP_COUNTRIES[i].compareToIgnoreCase(country) == 0) {
                return FORMAT_NANP;
            }
        }
        if ("jp".compareToIgnoreCase(country) == 0) {
            return FORMAT_JAPAN;
        }
        return FORMAT_UNKNOWN;
    }

    /**
     * This function checks if the passed in string conforms to the NANP format
     * i.e. NXX-NXX-XXXX, N is any digit 2-9 and X is any digit 0-9
     * @hide
     */
    public static boolean isNanp (String dialStr) {
        boolean retVal = false;
        if (dialStr != null) {
            if (dialStr.length() == NANP_LENGTH) {
                if (isTwoToNine(dialStr.charAt(0)) &&
                    isTwoToNine(dialStr.charAt(3))) {
                    retVal = true;
                    for (int i=1; i<NANP_LENGTH; i++ ) {
                        char c=dialStr.charAt(i);
                        if (!PhoneNumberUtils.isISODigit(c)) {
                            retVal = false;
                            break;
                        }
                    }
                }
            }
        } else {
            Rlog.e("isNanp: null dialStr passed in", dialStr);
        }
        return retVal;
    }

   /**
    * This function checks if the passed in string conforms to 1-NANP format
    */
    private static boolean isOneNanp(String dialStr) {
        boolean retVal = false;
        if (dialStr != null) {
            String newDialStr = dialStr.substring(1);
            if ((dialStr.charAt(0) == '1') && isNanp(newDialStr)) {
                retVal = true;
            }
        } else {
            Rlog.e("isOneNanp: null dialStr passed in", dialStr);
        }
        return retVal;
    }

    /**
     * Determines if the specified number is actually a URI
     * (i.e. a SIP address) rather than a regular PSTN phone number,
     * based on whether or not the number contains an "@" character.
     *
     * @hide
     * @param number
     * @return true if number contains @
     */
    public static boolean isUriNumber(String number) {
        // Note we allow either "@" or "%40" to indicate a URI, in case
        // the passed-in string is URI-escaped.  (Neither "@" nor "%40"
        // will ever be found in a legal PSTN number.)
        return number != null && (number.contains("@") || number.contains("%40"));
    }

    /**
     * @return the "username" part of the specified SIP address,
     *         i.e. the part before the "@" character (or "%40").
     *
     * @param number SIP address of the form "username@domainname"
     *               (or the URI-escaped equivalent "username%40domainname")
     * @see isUriNumber
     *
     * @hide
     */
    public static String getUsernameFromUriNumber(String number) {
        // The delimiter between username and domain name can be
        // either "@" or "%40" (the URI-escaped equivalent.)
        int delimiterIndex = number.indexOf('@');
        if (delimiterIndex < 0) {
            delimiterIndex = number.indexOf("%40");
        }
        if (delimiterIndex < 0) {
            Rlog.w(LOG_TAG,
                  "getUsernameFromUriNumber: no delimiter found in SIP addr '" + number + "'");
            delimiterIndex = number.length();
        }
        return number.substring(0, delimiterIndex);
    }

    /**
     * This function handles the plus code conversion
     * If the number format is
     * 1)+1NANP,remove +,
     * 2)other than +1NANP, any + numbers,replace + with the current IDP
     */
    private static String processPlusCode(String networkDialStr, boolean useNanp) {
        String retStr = networkDialStr;

        if (DBG) log("processPlusCode, networkDialStr = " + networkDialStr
                + "for NANP = " + useNanp);
        // If there is a plus sign at the beginning of the dial string,
        // Convert the plus sign to the default IDP since it's an international number
        if (networkDialStr != null &&
            networkDialStr.charAt(0) == PLUS_SIGN_CHAR &&
            networkDialStr.length() > 1) {
            String newStr = networkDialStr.substring(1);
            // TODO: for nonNanp, should the '+' be removed if following number is country code
            if (useNanp && isOneNanp(newStr)) {
                // Remove the leading plus sign
                retStr = newStr;
            } else {
                // Replaces the plus sign with the default IDP
                retStr = networkDialStr.replaceFirst("[+]", getCurrentIdp(useNanp));
            }
        }
        if (DBG) log("processPlusCode, retStr=" + retStr);
        return retStr;
    }

    // This function finds the index of the dialable character(s)
    // in the post dial string
    private static int findDialableIndexFromPostDialStr(String postDialStr) {
        for (int index = 0;index < postDialStr.length();index++) {
             char c = postDialStr.charAt(index);
             if (isReallyDialable(c)) {
                return index;
             }
        }
        return -1;
    }

    // This function appends the non-dialable P/W character to the original
    // dial string based on the dialable index passed in
    private static String
    appendPwCharBackToOrigDialStr(int dialableIndex,String origStr, String dialStr) {
        String retStr;

        // There is only 1 P/W character before the dialable characters
        if (dialableIndex == 1) {
            StringBuilder ret = new StringBuilder(origStr);
            ret = ret.append(dialStr.charAt(0));
            retStr = ret.toString();
        } else {
            // It means more than 1 P/W characters in the post dial string,
            // appends to retStr
            String nonDigitStr = dialStr.substring(0,dialableIndex);
            retStr = origStr.concat(nonDigitStr);
        }
        return retStr;
    }

    //===== Beginning of utility methods used in compareLoosely() =====

    /**
     * Phone numbers are stored in "lookup" form in the database
     * as reversed strings to allow for caller ID lookup
     *
     * This method takes a phone number and makes a valid SQL "LIKE"
     * string that will match the lookup form
     *
     */
    /** all of a up to len must be an international prefix or
     *  separators/non-dialing digits
     */
    private static boolean
    matchIntlPrefix(String a, int len) {
        /* '([^0-9*#+pwn]\+[^0-9*#+pwn] | [^0-9*#+pwn]0(0|11)[^0-9*#+pwn] )$' */
        /*        0       1                           2 3 45               */

        int state = 0;
        for (int i = 0 ; i < len ; i++) {
            char c = a.charAt(i);

            switch (state) {
                case 0:
                    if      (c == '+') state = 1;
                    else if (c == '0') state = 2;
                    else if (isNonSeparator(c)) return false;
                break;

                case 2:
                    if      (c == '0') state = 3;
                    else if (c == '1') state = 4;
                    else if (isNonSeparator(c)) return false;
                break;

                case 4:
                    if      (c == '1') state = 5;
                    else if (isNonSeparator(c)) return false;
                break;

                default:
                    if (isNonSeparator(c)) return false;
                break;

            }
        }

        return state == 1 || state == 3 || state == 5;
    }

    /** all of 'a' up to len must be a (+|00|011)country code)
     *  We're fast and loose with the country code. Any \d{1,3} matches */
    private static boolean
    matchIntlPrefixAndCC(String a, int len) {
        /*  [^0-9*#+pwn]*(\+|0(0|11)\d\d?\d? [^0-9*#+pwn] $ */
        /*      0          1 2 3 45  6 7  8                 */

        int state = 0;
        for (int i = 0 ; i < len ; i++ ) {
            char c = a.charAt(i);

            switch (state) {
                case 0:
                    if      (c == '+') state = 1;
                    else if (c == '0') state = 2;
                    else if (isNonSeparator(c)) return false;
                break;

                case 2:
                    if      (c == '0') state = 3;
                    else if (c == '1') state = 4;
                    else if (isNonSeparator(c)) return false;
                break;

                case 4:
                    if      (c == '1') state = 5;
                    else if (isNonSeparator(c)) return false;
                break;

                case 1:
                case 3:
                case 5:
                    if      (isISODigit(c)) state = 6;
                    else if (isNonSeparator(c)) return false;
                break;

                case 6:
                case 7:
                    if      (isISODigit(c)) state++;
                    else if (isNonSeparator(c)) return false;
                break;

                default:
                    if (isNonSeparator(c)) return false;
            }
        }

        return state == 6 || state == 7 || state == 8;
    }

    /** all of 'a' up to len must match non-US trunk prefix ('0') */
    private static boolean
    matchTrunkPrefix(String a, int len) {
        boolean found;

        found = false;

        for (int i = 0 ; i < len ; i++) {
            char c = a.charAt(i);

            if (c == '0' && !found) {
                found = true;
            } else if (isNonSeparator(c)) {
                return false;
            }
        }

        return found;
    }

    //===== End of utility methods used only in compareLoosely() =====

    //===== Beginning of utility methods used only in compareStrictly() ====

    /*
     * If true, the number is country calling code.
     */
    private static final boolean COUNTRY_CALLING_CALL[] = {
        true, true, false, false, false, false, false, true, false, false,
        false, false, false, false, false, false, false, false, false, false,
        true, false, false, false, false, false, false, true, true, false,
        true, true, true, true, true, false, true, false, false, true,
        true, false, false, true, true, true, true, true, true, true,
        false, true, true, true, true, true, true, true, true, false,
        true, true, true, true, true, true, true, false, false, false,
        false, false, false, false, false, false, false, false, false, false,
        false, true, true, true, true, false, true, false, false, true,
        true, true, true, true, true, true, false, false, true, false,
    };
    private static final int CCC_LENGTH = COUNTRY_CALLING_CALL.length;

    /**
     * @return true when input is valid Country Calling Code.
     */
    private static boolean isCountryCallingCode(int countryCallingCodeCandidate) {
        return countryCallingCodeCandidate > 0 && countryCallingCodeCandidate < CCC_LENGTH &&
                COUNTRY_CALLING_CALL[countryCallingCodeCandidate];
    }

    /**
     * Returns integer corresponding to the input if input "ch" is
     * ISO-LATIN characters 0-9.
     * Returns -1 otherwise
     */
    private static int tryGetISODigit(char ch) {
        if ('0' <= ch && ch <= '9') {
            return ch - '0';
        } else {
            return -1;
        }
    }

    private static class CountryCallingCodeAndNewIndex {
        public final int countryCallingCode;
        public final int newIndex;
        public CountryCallingCodeAndNewIndex(int countryCode, int newIndex) {
            this.countryCallingCode = countryCode;
            this.newIndex = newIndex;
        }
    }

    /*
     * Note that this function does not strictly care the country calling code with
     * 3 length (like Morocco: +212), assuming it is enough to use the first two
     * digit to compare two phone numbers.
     */
    private static CountryCallingCodeAndNewIndex tryGetCountryCallingCodeAndNewIndex(
        String str, boolean acceptThailandCase) {
        // Rough regexp:
        //  ^[^0-9*#+]*((\+|0(0|11)\d\d?|166) [^0-9*#+] $
        //         0        1 2 3 45  6 7  89
        //
        // In all the states, this function ignores separator characters.
        // "166" is the special case for the call from Thailand to the US. Uguu!
        int state = 0;
        int ccc = 0;
        final int length = str.length();
        for (int i = 0 ; i < length ; i++ ) {
            char ch = str.charAt(i);
            switch (state) {
                case 0:
                    if      (ch == '+') state = 1;
                    else if (ch == '0') state = 2;
                    else if (ch == '1') {
                        if (acceptThailandCase) {
                            state = 8;
                        } else {
                            return null;
                        }
                    } else if (isDialable(ch)) {
                        return null;
                    }
                break;

                case 2:
                    if      (ch == '0') state = 3;
                    else if (ch == '1') state = 4;
                    else if (isDialable(ch)) {
                        return null;
                    }
                break;

                case 4:
                    if      (ch == '1') state = 5;
                    else if (isDialable(ch)) {
                        return null;
                    }
                break;

                case 1:
                case 3:
                case 5:
                case 6:
                case 7:
                    {
                        int ret = tryGetISODigit(ch);
                        if (ret > 0) {
                            ccc = ccc * 10 + ret;
                            if (ccc >= 100 || isCountryCallingCode(ccc)) {
                                return new CountryCallingCodeAndNewIndex(ccc, i + 1);
                            }
                            if (state == 1 || state == 3 || state == 5) {
                                state = 6;
                            } else {
                                state++;
                            }
                        } else if (isDialable(ch)) {
                            return null;
                        }
                    }
                    break;
                case 8:
                    if (ch == '6') state = 9;
                    else if (isDialable(ch)) {
                        return null;
                    }
                    break;
                case 9:
                    if (ch == '6') {
                        return new CountryCallingCodeAndNewIndex(66, i + 1);
                    } else {
                        return null;
                    }
                default:
                    return null;
            }
        }

        return null;
    }

    /**
     * Currently this function simply ignore the first digit assuming it is
     * trunk prefix. Actually trunk prefix is different in each country.
     *
     * e.g.
     * "+79161234567" equals "89161234567" (Russian trunk digit is 8)
     * "+33123456789" equals "0123456789" (French trunk digit is 0)
     *
     */
    private static int tryGetTrunkPrefixOmittedIndex(String str, int currentIndex) {
        int length = str.length();
        for (int i = currentIndex ; i < length ; i++) {
            final char ch = str.charAt(i);
            if (tryGetISODigit(ch) >= 0) {
                return i + 1;
            } else if (isDialable(ch)) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Return true if the prefix of "str" is "ignorable". Here, "ignorable" means
     * that "str" has only one digit and separator characters. The one digit is
     * assumed to be trunk prefix.
     */
    private static boolean checkPrefixIsIgnorable(final String str,
            int forwardIndex, int backwardIndex) {
        boolean trunk_prefix_was_read = false;
        while (backwardIndex >= forwardIndex) {
            if (tryGetISODigit(str.charAt(backwardIndex)) >= 0) {
                if (trunk_prefix_was_read) {
                    // More than one digit appeared, meaning that "a" and "b"
                    // is different.
                    return false;
                } else {
                    // Ignore just one digit, assuming it is trunk prefix.
                    trunk_prefix_was_read = true;
                }
            } else if (isDialable(str.charAt(backwardIndex))) {
                // Trunk prefix is a digit, not "*", "#"...
                return false;
            }
            backwardIndex--;
        }

        return true;
    }

    /**
     * Returns Default voice subscription Id.
     */
    private static long getDefaultVoiceSubId() {
        return SubscriptionManager.getDefaultVoiceSubId();
    }
    //==== End of utility methods used only in compareStrictly() =====
}
