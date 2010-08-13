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
import android.test.suitebuilder.annotation.SmallTest;

import static android.telephony.SmsMessage.MAX_USER_DATA_SEPTETS;

public class SmsMessageBodyTest extends AndroidTestCase {

    private static final String sAsciiChars = "@$_ !\"#%&'()*+,-./0123456789" +
            ":;<=>?ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz\n\r";
    private static final String sGsmBasicChars = "\u00a3\u00a5\u00e8\u00e9" +
            "\u00f9\u00ec\u00f2\u00c7\u00d8\u00f8\u00c5\u00e5\u0394\u03a6" +
            "\u0393\u039b\u03a9\u03a0\u03a8\u03a3\u0398\u00c6\u00e6" +
            "\u00df\u00c9\u00a4\u00a1\u00c4\u00d6\u00d1\u00dc\u00a7\u00bf" +
            "\u00e4\u00f6\u00f1\u00fc\u00e0";
    private static final String sGsmExtendedAsciiChars = "{|}\\[~]^\f";
    private static final String sGsmExtendedEuroSymbol = "\u20ac";
    private static final String sUnicodeChars = "\u4e00\u4e01\u4e02\u4e03" +
            "\u4e04\u4e05\u4e06\u4e07\u4e08\u4e09\u4e0a\u4e0b\u4e0c\u4e0d" +
            "\u4e0e\u4e0f\u3041\u3042\u3043\u3044\u3045\u3046\u3047\u3048" +
            "\u30a1\u30a2\u30a3\u30a4\u30a5\u30a6\u30a7\u30a8" +
            "\uff10\uff11\uff12\uff13\uff14\uff15\uff16\uff17\uff18" +
            "\uff70\uff71\uff72\uff73\uff74\uff75\uff76\uff77\uff78" +
            "\u0400\u0401\u0402\u0403\u0404\u0405\u0406\u0407\u0408" +
            "\u00a2\u00a9\u00ae\u2122";

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


    @SmallTest
    public void testCalcLengthAscii() throws Exception {
        StringBuilder sb = new StringBuilder(320);
        int[] values = {0, 0, 0, SmsMessage.ENCODING_7BIT};
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
    public void testCalcLength7bitGsm() throws Exception {
        // TODO
    }

    @SmallTest
    public void testCalcLength7bitGsmExtended() throws Exception {
        // TODO
    }

    @SmallTest
    public void testCalcLengthUnicode() throws Exception {
        StringBuilder sb = new StringBuilder(160);
        int[] values = {0, 0, 0, SmsMessage.ENCODING_16BIT};
        int[] values7bit = {1, 0, 0, SmsMessage.ENCODING_7BIT};
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
