/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import java.util.Random;

import static android.telephony.SmsMessage.MAX_USER_DATA_BYTES;
import static android.telephony.SmsMessage.MAX_USER_DATA_BYTES_WITH_HEADER;
import static android.telephony.SmsMessage.MAX_USER_DATA_SEPTETS;
import static android.telephony.SmsMessage.MAX_USER_DATA_SEPTETS_WITH_HEADER;

/**
 * Test cases to verify selection of the optimal 7 bit encoding tables
 * (for all combinations of enabled national language tables) for messages
 * containing Turkish, Spanish, Portuguese, Greek, and other symbols
 * present in the GSM default and national language tables defined in
 * 3GPP TS 23.038. Also verifies correct SMS encoding for CDMA, which only
 * supports the GSM 7 bit default alphabet, ASCII 8 bit, and UCS-2.
 * Tests both encoding variations: unsupported characters mapped to space,
 * and unsupported characters force entire message to UCS-2.
 */
public class SmsMessageBodyTest extends AndroidTestCase {
    private static final String TAG = "SmsMessageBodyTest";

    // ASCII chars in the GSM 7 bit default alphabet
    private static final String sAsciiChars = "@$_ !\"#%&'()*+,-./0123456789" +
            ":;<=>?ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz\n\r";

    // Unicode chars in the GSM 7 bit default alphabet and both locking shift tables
    private static final String sGsmDefaultChars = "\u00a3\u00a5\u00e9\u00c7\u0394\u00c9" +
            "\u00dc\u00a7\u00fc\u00e0";

    // Unicode chars in the GSM 7 bit default table and Turkish locking shift tables
    private static final String sGsmDefaultAndTurkishTables = "\u00f9\u00f2\u00c5\u00e5\u00df" +
            "\u00a4\u00c4\u00d6\u00d1\u00e4\u00f6\u00f1";

    // Unicode chars in the GSM 7 bit default table but not the locking shift tables
    private static final String sGsmDefaultTableOnly = "\u00e8\u00ec\u00d8\u00f8\u00c6\u00e6" +
            "\u00a1\u00bf";

    // ASCII chars in the GSM default extension table
    private static final String sGsmExtendedAsciiChars = "{}[]\f";

    // chars in GSM default extension table and Portuguese locking shift table
    private static final String sGsmExtendedPortugueseLocking = "^\\|~";

    // Euro currency symbol
    private static final String sGsmExtendedEuroSymbol = "\u20ac";

    // CJK ideographs, Hiragana, Katakana, full width letters, Cyrillic, etc.
    private static final String sUnicodeChars = "\u4e00\u4e01\u4e02\u4e03" +
            "\u4e04\u4e05\u4e06\u4e07\u4e08\u4e09\u4e0a\u4e0b\u4e0c\u4e0d" +
            "\u4e0e\u4e0f\u3041\u3042\u3043\u3044\u3045\u3046\u3047\u3048" +
            "\u30a1\u30a2\u30a3\u30a4\u30a5\u30a6\u30a7\u30a8" +
            "\uff10\uff11\uff12\uff13\uff14\uff15\uff16\uff17\uff18" +
            "\uff70\uff71\uff72\uff73\uff74\uff75\uff76\uff77\uff78" +
            "\u0400\u0401\u0402\u0403\u0404\u0405\u0406\u0407\u0408" +
            "\u00a2\u00a9\u00ae\u2122";

    // chars in Turkish single shift and locking shift tables
    private static final String sTurkishChars = "\u0131\u011e\u011f\u015e\u015f\u0130";

    // chars in Spanish single shift table and Portuguese single and locking shift tables
    private static final String sPortugueseAndSpanishChars = "\u00c1\u00e1\u00cd\u00ed"
            + "\u00d3\u00f3\u00da\u00fa";

    // chars in all national language tables but not in the standard GSM alphabets
    private static final String sNationalLanguageTablesOnly = "\u00e7";

    // chars in Portuguese single shift and locking shift tables
    private static final String sPortugueseChars = "\u00ea\u00d4\u00f4\u00c0\u00c2\u00e2"
            + "\u00ca\u00c3\u00d5\u00e3\u00f5";

    // chars in Portuguese locking shift table only
    private static final String sPortugueseLockingShiftChars = "\u00aa\u221e\u00ba`";

    // Greek letters in GSM alphabet missing from Portuguese locking and single shift tables
    private static final String sGreekLettersNotInPortugueseTables = "\u039b\u039e";

    // Greek letters in GSM alphabet and Portuguese single shift (but not locking shift) table
    private static final String sGreekLettersInPortugueseShiftTable =
            "\u03a6\u0393\u03a9\u03a0\u03a8\u03a3\u0398";

    // List of classes of characters in SMS tables
    private static final String[] sCharacterClasses = {
            sGsmExtendedAsciiChars,
            sGsmExtendedPortugueseLocking,
            sGsmDefaultChars,
            sGsmDefaultAndTurkishTables,
            sGsmDefaultTableOnly,
            sGsmExtendedEuroSymbol,
            sUnicodeChars,
            sTurkishChars,
            sPortugueseChars,
            sPortugueseLockingShiftChars,
            sPortugueseAndSpanishChars,
            sGreekLettersNotInPortugueseTables,
            sGreekLettersInPortugueseShiftTable,
            sNationalLanguageTablesOnly,
            sAsciiChars
    };

    private static final int sNumCharacterClasses = sCharacterClasses.length;

    // For each character class, whether it is present in a particular char table.
    // First three entries are locking shift tables, followed by four single shift tables
    private static final boolean[][] sCharClassPresenceInTables = {
            // ASCII chars in all GSM extension tables
            {false, false, false, true, true, true, true},
            // ASCII chars in all GSM extension tables and Portuguese locking shift table
            {false, false, true, true, true, true, true},
            // non-ASCII chars in GSM default alphabet and all locking tables
            {true, true, true, false, false, false, false},
            // non-ASCII chars in GSM default alphabet and Turkish locking shift table
            {true, true, false, false, false, false, false},
            // non-ASCII chars in GSM default alphabet table only
            {true, false, false, false, false, false, false},
            // Euro symbol is present in several tables
            {false, true, true, true, true, true, true},
            // Unicode characters not present in any 7 bit tables
            {false, false, false, false, false, false, false},
            // Characters specific to Turkish language
            {false, true, false, false, true, false, false},
            // Characters in Portuguese single shift and locking shift tables
            {false, false, true, false, false, false, true},
            // Characters in Portuguese locking shift table only
            {false, false, true, false, false, false, false},
            // Chars in Spanish single shift and Portuguese single and locking shift tables
            {false, false, true, false, false, true, true},
            // Greek letters in GSM default alphabet missing from Portuguese tables
            {true, true, false, false, false, false, false},
            // Greek letters in GSM alphabet and Portuguese single shift table
            {true, true, false, false, false, false, true},
            // Chars in all national language tables but not the standard GSM tables
            {false, true, true, false, true, true, true},
            // ASCII chars in GSM default alphabet
            {true, true, true, false, false, false, false}
    };

    private static final int sTestLengthCount = 12;

    private static final int[] sSeptetTestLengths =
            {  0,   1,   2, 80, 159, 160, 161, 240, 305, 306, 307, 320};

    private static final int[] sUnicodeTestLengths =
            {  0,   1,   2, 35,  69,  70,  71, 100, 133, 134, 135, 160};

    private static final int[] sTestMsgCounts =
            {  1,   1,   1,  1,   1,   1,   2,   2,   2,   2,   3,   3};

    private static final int[] sSeptetUnitsRemaining =
            {160, 159, 158, 80,   1,   0, 145,  66,   1,   0, 152, 139};

    private static final int[] sUnicodeUnitsRemaining =
            { 70,  69,  68, 35,   1,   0,  63,  34,   1,   0,  66,  41};

    // Combinations of enabled GSM national language single shift tables
    private static final int[][] sEnabledSingleShiftTables = {
            {},         // GSM default alphabet only
            {1},        // Turkish (single shift only)
            {1},        // Turkish (single and locking shift)
            {2},        // Spanish
            {3},        // Portuguese (single shift only)
            {3},        // Portuguese (single and locking shift)
            {1, 2},     // Turkish + Spanish (single shift only)
            {1, 2},     // Turkish + Spanish (single and locking shift)
            {1, 3},     // Turkish + Portuguese (single shift only)
            {1, 3},     // Turkish + Portuguese (single and locking shift)
            {2, 3},     // Spanish + Portuguese (single shift only)
            {2, 3},     // Spanish + Portuguese (single and locking shift)
            {1, 2, 3},  // Turkish, Spanish, Portuguese (single shift only)
            {1, 2, 3},  // Turkish, Spanish, Portuguese (single and locking shift)
            {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13} // all language tables
    };

    // Combinations of enabled GSM national language locking shift tables
    private static final int[][] sEnabledLockingShiftTables = {
            {},         // GSM default alphabet only
            {},         // Turkish (single shift only)
            {1},        // Turkish (single and locking shift)
            {},         // Spanish (no locking shift table)
            {},         // Portuguese (single shift only)
            {3},        // Portuguese (single and locking shift)
            {},         // Turkish + Spanish (single shift only)
            {1},        // Turkish + Spanish (single and locking shift)
            {},         // Turkish + Portuguese (single shift only)
            {1, 3},     // Turkish + Portuguese (single and locking shift)
            {},         // Spanish + Portuguese (single shift only)
            {3},        // Spanish + Portuguese (single and locking shift)
            {},         // Turkish, Spanish, Portuguese (single shift only)
            {1, 3},     // Turkish, Spanish, Portuguese (single and locking shift)
            {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13} // all language tables
    };

    // LanguagePair counter indexes to check for each entry above
    private static final int[][] sLanguagePairIndexesByEnabledIndex = {
            {0},                            // default tables only
            {0, 1},                         // Turkish (single shift only)
            {0, 1, 4, 5},                   // Turkish (single and locking shift)
            {0, 2},                         // Spanish
            {0, 3},                         // Portuguese (single shift only)
            {0, 3, 8, 11},                  // Portuguese (single and locking shift)
            {0, 1, 2},                      // Turkish + Spanish (single shift only)
            {0, 1, 2, 4, 5, 6},             // Turkish + Spanish (single and locking shift)
            {0, 1, 3},                      // Turkish + Portuguese (single shift only)
            {0, 1, 3, 4, 5, 7, 8, 9, 11},   // Turkish + Portuguese (single and locking shift)
            {0, 2, 3},                      // Spanish + Portuguese (single shift only)
            {0, 2, 3, 8, 10, 11},           // Spanish + Portuguese (single and locking shift)
            {0, 1, 2, 3},                   // all languages (single shift only)
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, // all languages (single and locking shift)
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}  // all languages (no Indic chars in test)
    };

    /**
     * User data header requires one octet for length. Count as one septet, because
     * all combinations of header elements below will have at least one free bit
     * when padding to the nearest septet boundary.
     */
    private static final int UDH_SEPTET_COST_LENGTH = 1;

    /**
     * Using a non-default language locking shift table OR single shift table
     * requires a user data header of 3 octets, or 4 septets, plus UDH length.
     */
    private static final int UDH_SEPTET_COST_ONE_SHIFT_TABLE = 4;

    /**
     * Using a non-default language locking shift table AND single shift table
     * requires a user data header of 6 octets, or 7 septets, plus UDH length.
     */
    private static final int UDH_SEPTET_COST_TWO_SHIFT_TABLES = 7;

    /**
     * Multi-part messages require a user data header of 5 octets, or 6 septets,
     * plus UDH length.
     */
    private static final int UDH_SEPTET_COST_CONCATENATED_MESSAGE = 6;

    @SmallTest
    public void testCalcLengthAscii() throws Exception {
        StringBuilder sb = new StringBuilder(320);
        int[] values = {0, 0, 0, SmsMessage.ENCODING_7BIT, 0, 0};
        int startPos = 0;
        int asciiCharsLen = sAsciiChars.length();

        for (int i = 0; i < sTestLengthCount; i++) {
            int len = sSeptetTestLengths[i];
            assertTrue(sb.length() <= len);

            while (sb.length() < len) {
                int addCount = len - sb.length();
                int endPos = (asciiCharsLen - startPos > addCount) ?
                        (startPos + addCount) : asciiCharsLen;
                sb.append(sAsciiChars, startPos, endPos);
                startPos = (endPos == asciiCharsLen) ? 0 : endPos;
            }
            assertEquals(len, sb.length());

            String testStr = sb.toString();
            values[0] = sTestMsgCounts[i];
            values[1] = len;
            values[2] = sSeptetUnitsRemaining[i];

            callGsmLengthMethods(testStr, false, values);
            callGsmLengthMethods(testStr, true, values);
            callCdmaLengthMethods(testStr, false, values);
            callCdmaLengthMethods(testStr, true, values);
        }
    }

    @SmallTest
    public void testCalcLengthUnicode() throws Exception {
        StringBuilder sb = new StringBuilder(160);
        int[] values = {0, 0, 0, SmsMessage.ENCODING_16BIT, 0, 0};
        int[] values7bit = {1, 0, 0, SmsMessage.ENCODING_7BIT, 0, 0};
        int startPos = 0;
        int unicodeCharsLen = sUnicodeChars.length();

        // start with length 1: empty string uses ENCODING_7BIT
        for (int i = 1; i < sTestLengthCount; i++) {
            int len = sUnicodeTestLengths[i];
            assertTrue(sb.length() <= len);

            while (sb.length() < len) {
                int addCount = len - sb.length();
                int endPos = (unicodeCharsLen - startPos > addCount) ?
                        (startPos + addCount) : unicodeCharsLen;
                sb.append(sUnicodeChars, startPos, endPos);
                startPos = (endPos == unicodeCharsLen) ? 0 : endPos;
            }
            assertEquals(len, sb.length());

            String testStr = sb.toString();
            values[0] = sTestMsgCounts[i];
            values[1] = len;
            values[2] = sUnicodeUnitsRemaining[i];
            values7bit[1] = len;
            values7bit[2] = MAX_USER_DATA_SEPTETS - len;

            callGsmLengthMethods(testStr, false, values);
            callCdmaLengthMethods(testStr, false, values);
            callGsmLengthMethods(testStr, true, values7bit);
            callCdmaLengthMethods(testStr, true, values7bit);
        }
    }

    private static class LanguagePair {
        // index is 2 for Portuguese locking shift because there is no Spanish locking shift table
        private final int langTableIndex;
        private final int langShiftTableIndex;
        int length;
        int missingChars7bit;

        LanguagePair(int langTable, int langShiftTable) {
            langTableIndex = langTable;
            langShiftTableIndex = langShiftTable;
        }

        void clear() {
            length = 0;
            missingChars7bit = 0;
        }

        void addChar(boolean[] charClassTableRow) {
            if (charClassTableRow[langTableIndex]) {
                length++;
            } else if (charClassTableRow[3 + langShiftTableIndex]) {
                length += 2;
            } else {
                length++;    // use ' ' for unmapped char in 7 bit only mode
                missingChars7bit++;
            }
        }
    }

    private static class CounterHelper {
        LanguagePair[] mCounters;
        int[] mStatsCounters;
        int mUnicodeCounter;

        CounterHelper() {
            mCounters = new LanguagePair[12];
            mStatsCounters = new int[12];
            for (int i = 0; i < 12; i++) {
                mCounters[i] = new LanguagePair(i/4, i%4);
            }
        }

        void clear() {
            // Note: don't clear stats counters
            for (int i = 0; i < 12; i++) {
                mCounters[i].clear();
            }
        }

        void addChar(int charClass) {
            boolean[] charClassTableRow = sCharClassPresenceInTables[charClass];
            for (int i = 0; i < 12; i++) {
                mCounters[i].addChar(charClassTableRow);
            }
        }

        void fillData(int enabledLangsIndex, boolean use7bitOnly, int[] values, int length) {
            int[] languagePairs = sLanguagePairIndexesByEnabledIndex[enabledLangsIndex];
            int minNumSeptets = Integer.MAX_VALUE;
            int minNumSeptetsWithHeader = Integer.MAX_VALUE;
            int minNumMissingChars = Integer.MAX_VALUE;
            int langIndex = -1;
            int langShiftIndex = -1;
            for (int i : languagePairs) {
                LanguagePair pair = mCounters[i];
                int udhLength = 0;
                if (i != 0) {
                    udhLength = UDH_SEPTET_COST_LENGTH;
                    if (i < 4 || i % 4 == 0) {
                        udhLength += UDH_SEPTET_COST_ONE_SHIFT_TABLE;
                    } else {
                        udhLength += UDH_SEPTET_COST_TWO_SHIFT_TABLES;
                    }
                }
                int numSeptetsWithHeader;
                if (pair.length > (MAX_USER_DATA_SEPTETS - udhLength)) {
                    if (udhLength == 0) {
                        udhLength = UDH_SEPTET_COST_LENGTH;
                    }
                    udhLength += UDH_SEPTET_COST_CONCATENATED_MESSAGE;
                    int septetsPerPart = MAX_USER_DATA_SEPTETS - udhLength;
                    int msgCount = (pair.length + septetsPerPart - 1) / septetsPerPart;
                    numSeptetsWithHeader = udhLength * msgCount + pair.length;
                } else {
                    numSeptetsWithHeader = udhLength + pair.length;
                }

                if (use7bitOnly) {
                    if (pair.missingChars7bit < minNumMissingChars || (pair.missingChars7bit ==
                            minNumMissingChars && numSeptetsWithHeader < minNumSeptetsWithHeader)) {
                        minNumSeptets = pair.length;
                        minNumSeptetsWithHeader = numSeptetsWithHeader;
                        minNumMissingChars = pair.missingChars7bit;
                        langIndex = pair.langTableIndex;
                        langShiftIndex = pair.langShiftTableIndex;
                    }
                } else {
                    if (pair.missingChars7bit == 0 && numSeptetsWithHeader < minNumSeptetsWithHeader) {
                        minNumSeptets = pair.length;
                        minNumSeptetsWithHeader = numSeptetsWithHeader;
                        langIndex = pair.langTableIndex;
                        langShiftIndex = pair.langShiftTableIndex;
                    }
                }
            }
            if (langIndex == -1) {
                // nothing matches, use values for Unicode
                int byteCount = length * 2;
                if (byteCount > MAX_USER_DATA_BYTES) {
                    values[0] = (byteCount + MAX_USER_DATA_BYTES_WITH_HEADER - 1) /
                            MAX_USER_DATA_BYTES_WITH_HEADER;
                    values[2] = ((values[0] * MAX_USER_DATA_BYTES_WITH_HEADER) - byteCount) / 2;
                } else {
                    values[0] = 1;
                    values[2] = (MAX_USER_DATA_BYTES - byteCount) / 2;
                }
                values[1] = length;
                values[3] = SmsMessage.ENCODING_16BIT;
                values[4] = 0;
                values[5] = 0;
                mUnicodeCounter++;
            } else {
                int udhLength = 0;
                if (langIndex != 0 || langShiftIndex != 0) {
                    udhLength = UDH_SEPTET_COST_LENGTH;
                    if (langIndex == 0 || langShiftIndex == 0) {
                        udhLength += UDH_SEPTET_COST_ONE_SHIFT_TABLE;
                    } else {
                        udhLength += UDH_SEPTET_COST_TWO_SHIFT_TABLES;
                    }
                }
                int msgCount;
                if (minNumSeptets > (MAX_USER_DATA_SEPTETS - udhLength)) {
                    if (udhLength == 0) {
                        udhLength = UDH_SEPTET_COST_LENGTH;
                    }
                    udhLength += UDH_SEPTET_COST_CONCATENATED_MESSAGE;
                    int septetsPerPart = MAX_USER_DATA_SEPTETS - udhLength;
                    msgCount = (minNumSeptets + septetsPerPart - 1) / septetsPerPart;
                } else {
                    msgCount = 1;
                }
                values[0] = msgCount;
                values[1] = minNumSeptets;
                values[2] = (values[0] * (MAX_USER_DATA_SEPTETS - udhLength)) - minNumSeptets;
                values[3] = SmsMessage.ENCODING_7BIT;
                values[4] = (langIndex == 2 ? 3 : langIndex); // Portuguese is code 3, index 2
                values[5] = langShiftIndex;
                assertEquals("minNumSeptetsWithHeader", minNumSeptetsWithHeader,
                        udhLength * msgCount + minNumSeptets);
                mStatsCounters[langIndex * 4 + langShiftIndex]++;
            }
        }

        void printStats() {
            Log.d(TAG, "Unicode selection count: " + mUnicodeCounter);
            for (int i = 0; i < 12; i++) {
                Log.d(TAG, "Language pair index " + i + " count: " + mStatsCounters[i]);
            }
        }
    }

    @LargeTest
    public void testCalcLengthMixed7bit() throws Exception {
        StringBuilder sb = new StringBuilder(320);
        CounterHelper ch = new CounterHelper();
        Random r = new Random(0x4321);  // use the same seed for reproducibility
        int[] expectedValues = new int[6];
        int[] origLockingShiftTables = GsmAlphabet.getEnabledLockingShiftTables();
        int[] origSingleShiftTables = GsmAlphabet.getEnabledSingleShiftTables();
        int enabledLanguagesTestCases = sEnabledSingleShiftTables.length;
        long startTime = System.currentTimeMillis();

        // Repeat for 10 test runs
        for (int run = 0; run < 10; run++) {
            sb.setLength(0);
            ch.clear();
            int unicodeOnlyCount = 0;

            // Test incrementally from 1 to 320 character random messages
            for (int i = 1; i < 320; i++) {
                // 1% chance to add from each special character class, else add an ASCII char
                int charClass = r.nextInt(100);
                if (charClass >= sNumCharacterClasses) {
                    charClass = sNumCharacterClasses - 1;   // last class is ASCII
                }
                int classLength = sCharacterClasses[charClass].length();
                char nextChar = sCharacterClasses[charClass].charAt(r.nextInt(classLength));
                sb.append(nextChar);
                ch.addChar(charClass);

//                if (i % 20 == 0) {
//                    Log.d(TAG, "test string: " + sb);
//                }

                // Test string against all combinations of enabled languages
                boolean unicodeOnly = true;
                for (int j = 0; j < enabledLanguagesTestCases; j++) {
                    GsmAlphabet.setEnabledSingleShiftTables(sEnabledSingleShiftTables[j]);
                    GsmAlphabet.setEnabledLockingShiftTables(sEnabledLockingShiftTables[j]);
                    ch.fillData(j, false, expectedValues, i);
                    if (expectedValues[3] == SmsMessage.ENCODING_7BIT) {
                        unicodeOnly = false;
                    }
                    callGsmLengthMethods(sb, false, expectedValues);
                    // test 7 bit only mode
                    ch.fillData(j, true, expectedValues, i);
                    callGsmLengthMethods(sb, true, expectedValues);
                }
                // after 10 iterations with a Unicode-only string, skip to next test string
                // so we can spend more time testing strings that do encode into 7 bits.
                if (unicodeOnly && ++unicodeOnlyCount == 10) {
//                    Log.d(TAG, "Unicode only: skipping to next test string");
                    break;
                }
            }
        }
        ch.printStats();
        Log.d(TAG, "Completed in " + (System.currentTimeMillis() - startTime) + " ms");
        GsmAlphabet.setEnabledLockingShiftTables(origLockingShiftTables);
        GsmAlphabet.setEnabledSingleShiftTables(origSingleShiftTables);
    }

    private void callGsmLengthMethods(CharSequence msgBody, boolean use7bitOnly,
            int[] expectedValues)
    {
        // deprecated GSM-specific method
        int[] values = android.telephony.gsm.SmsMessage.calculateLength(msgBody, use7bitOnly);
        assertEquals("msgCount",           expectedValues[0], values[0]);
        assertEquals("codeUnitCount",      expectedValues[1], values[1]);
        assertEquals("codeUnitsRemaining", expectedValues[2], values[2]);
        assertEquals("codeUnitSize",       expectedValues[3], values[3]);

        int activePhone = TelephonyManager.getDefault().getPhoneType();
        if (TelephonyManager.PHONE_TYPE_GSM == activePhone) {
            values = android.telephony.SmsMessage.calculateLength(msgBody, use7bitOnly);
            assertEquals("msgCount",           expectedValues[0], values[0]);
            assertEquals("codeUnitCount",      expectedValues[1], values[1]);
            assertEquals("codeUnitsRemaining", expectedValues[2], values[2]);
            assertEquals("codeUnitSize",       expectedValues[3], values[3]);
        }

        SmsMessageBase.TextEncodingDetails ted =
                com.android.internal.telephony.gsm.SmsMessage.calculateLength(msgBody, use7bitOnly);
        assertEquals("msgCount",           expectedValues[0], ted.msgCount);
        assertEquals("codeUnitCount",      expectedValues[1], ted.codeUnitCount);
        assertEquals("codeUnitsRemaining", expectedValues[2], ted.codeUnitsRemaining);
        assertEquals("codeUnitSize",       expectedValues[3], ted.codeUnitSize);
        assertEquals("languageTable",      expectedValues[4], ted.languageTable);
        assertEquals("languageShiftTable", expectedValues[5], ted.languageShiftTable);
    }

    private void callCdmaLengthMethods(CharSequence msgBody, boolean use7bitOnly,
            int[] expectedValues)
    {
        int activePhone = TelephonyManager.getDefault().getPhoneType();
        if (TelephonyManager.PHONE_TYPE_CDMA == activePhone) {
            int[] values = android.telephony.SmsMessage.calculateLength(msgBody, use7bitOnly);
            assertEquals("msgCount",           expectedValues[0], values[0]);
            assertEquals("codeUnitCount",      expectedValues[1], values[1]);
            assertEquals("codeUnitsRemaining", expectedValues[2], values[2]);
            assertEquals("codeUnitSize",       expectedValues[3], values[3]);
        }

        SmsMessageBase.TextEncodingDetails ted =
                com.android.internal.telephony.cdma.SmsMessage.calculateLength(msgBody, use7bitOnly);
        assertEquals("msgCount",           expectedValues[0], ted.msgCount);
        assertEquals("codeUnitCount",      expectedValues[1], ted.codeUnitCount);
        assertEquals("codeUnitsRemaining", expectedValues[2], ted.codeUnitsRemaining);
        assertEquals("codeUnitSize",       expectedValues[3], ted.codeUnitSize);

        ted = com.android.internal.telephony.cdma.sms.BearerData.calcTextEncodingDetails(msgBody, use7bitOnly);
        assertEquals("msgCount",           expectedValues[0], ted.msgCount);
        assertEquals("codeUnitCount",      expectedValues[1], ted.codeUnitCount);
        assertEquals("codeUnitsRemaining", expectedValues[2], ted.codeUnitsRemaining);
        assertEquals("codeUnitSize",       expectedValues[3], ted.codeUnitSize);
    }
}
