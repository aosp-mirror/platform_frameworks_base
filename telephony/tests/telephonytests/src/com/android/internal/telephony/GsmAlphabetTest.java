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

package com.android.internal.telephony;

import com.android.internal.telephony.GsmAlphabet;

import junit.framework.TestCase;

import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.SmallTest;

public class GsmAlphabetTest extends TestCase {

    private static final String sGsmExtendedChars = "{|}\\[~]\f\u20ac";

    @SmallTest
    public void test7bitWithHeader() throws Exception {
        SmsHeader.ConcatRef concatRef = new SmsHeader.ConcatRef();
        concatRef.refNumber = 1;
        concatRef.seqNumber = 2;
        concatRef.msgCount = 2;
        concatRef.isEightBits = true;
        SmsHeader header = new SmsHeader();
        header.concatRef = concatRef;

        String message = "aaaaaaaaaabbbbbbbbbbcccccccccc";
        byte[] userData = GsmAlphabet.stringToGsm7BitPackedWithHeader(message,
                SmsHeader.toByteArray(header), 0, 0);
        int septetCount = GsmAlphabet.countGsmSeptetsUsingTables(message, true, 0, 0);
        String parsedMessage = GsmAlphabet.gsm7BitPackedToString(
                userData, SmsHeader.toByteArray(header).length+2, septetCount, 1, 0, 0);
        assertEquals(message, parsedMessage);
    }

    // TODO: This method should *really* be a series of individual test methods.
    // However, it's a SmallTest because it executes quickly.
    @SmallTest
    public void testBasic() throws Exception {
        // '@' maps to char 0
        assertEquals(0, GsmAlphabet.charToGsm('@'));

        // `a (a with grave accent) maps to last GSM character
        assertEquals(0x7f, GsmAlphabet.charToGsm('\u00e0'));

        //
        // These are the extended chars
        // They should all return GsmAlphabet.GSM_EXTENDED_ESCAPE
        //

        for (int i = 0, s = sGsmExtendedChars.length(); i < s; i++) {
            assertEquals(GsmAlphabet.GSM_EXTENDED_ESCAPE,
                    GsmAlphabet.charToGsm(sGsmExtendedChars.charAt(i)));

        }

        // euro symbol
        assertEquals(GsmAlphabet.GSM_EXTENDED_ESCAPE,
                GsmAlphabet.charToGsm('\u20ac'));

        // An unmappable char (the 'cent' char) maps to a space
        assertEquals(GsmAlphabet.charToGsm(' '),
                GsmAlphabet.charToGsm('\u00a2'));

        // unmappable = space = 1 septet
        assertEquals(1, GsmAlphabet.countGsmSeptets('\u00a2'));

        //
        // Test extended table
        //

        for (int i = 0, s = sGsmExtendedChars.length(); i < s; i++) {
            assertEquals(sGsmExtendedChars.charAt(i),
                    GsmAlphabet.gsmExtendedToChar(
                            GsmAlphabet.charToGsmExtended(sGsmExtendedChars.charAt(i))));

        }

        // Unmappable extended char
        assertEquals(GsmAlphabet.charToGsm(' '),
                GsmAlphabet.charToGsmExtended('@'));

        //
        // gsmToChar()
        //

        assertEquals('@', GsmAlphabet.gsmToChar(0));

        // `a (a with grave accent) maps to last GSM character
        assertEquals('\u00e0', GsmAlphabet.gsmToChar(0x7f));

        assertEquals('\uffff',
                GsmAlphabet.gsmToChar(GsmAlphabet.GSM_EXTENDED_ESCAPE));

        // Out-of-range/unmappable value
        assertEquals(' ', GsmAlphabet.gsmToChar(0x80));

        //
        // gsmExtendedToChar()
        //

        assertEquals('{', GsmAlphabet.gsmExtendedToChar(0x28));

        // No double-escapes
        assertEquals(' ', GsmAlphabet.gsmExtendedToChar(
                GsmAlphabet.GSM_EXTENDED_ESCAPE));

        // Reserved for extension to extension table (mapped to space)
        assertEquals(' ', GsmAlphabet.gsmExtendedToChar(GsmAlphabet.GSM_EXTENDED_ESCAPE));

        // Unmappable (mapped to character in default or national locking shift table)
        assertEquals('@', GsmAlphabet.gsmExtendedToChar(0));
        assertEquals('\u00e0', GsmAlphabet.gsmExtendedToChar(0x7f));

        //
        // stringTo7BitPacked, gsm7BitPackedToString
        //

        byte[] packed;
        StringBuilder testString = new StringBuilder(300);

        // Check all alignment cases
        for (int i = 0; i < 9; i++, testString.append('@')) {
            packed = GsmAlphabet.stringToGsm7BitPacked(testString.toString(), 0, 0);
            assertEquals(testString.toString(),
                    GsmAlphabet.gsm7BitPackedToString(packed, 1, 0xff & packed[0]));
        }

        // Check full non-extended alphabet
        for (int i = 0; i < 0x80; i++) {
            char c;

            if (i == GsmAlphabet.GSM_EXTENDED_ESCAPE) {
                continue;
            }

            c = GsmAlphabet.gsmToChar(i);
            testString.append(c);

            // These are all non-extended chars, so it should be
            // one septet per char
            assertEquals(1, GsmAlphabet.countGsmSeptets(c));
        }

        packed = GsmAlphabet.stringToGsm7BitPacked(testString.toString(), 0, 0);
        assertEquals(testString.toString(),
                GsmAlphabet.gsm7BitPackedToString(packed, 1, 0xff & packed[0]));

        // Test extended chars too

        testString.append(sGsmExtendedChars);

        for (int i = 0, s = sGsmExtendedChars.length(); i < s; i++) {
            // These are all extended chars, so it should be
            // two septets per char
            assertEquals(2, GsmAlphabet.countGsmSeptets(sGsmExtendedChars.charAt(i)));

        }

        packed = GsmAlphabet.stringToGsm7BitPacked(testString.toString(), 0, 0);
        assertEquals(testString.toString(),
                GsmAlphabet.gsm7BitPackedToString(packed, 1, 0xff & packed[0]));

        // stringTo7BitPacked handles up to 255 septets

        testString.setLength(0);
        for (int i = 0; i < 255; i++) {
            testString.append('@');
        }

        packed = GsmAlphabet.stringToGsm7BitPacked(testString.toString(), 0, 0);
        assertEquals(testString.toString(),
                GsmAlphabet.gsm7BitPackedToString(packed, 1, 0xff & packed[0]));

        // > 255 septets throws runtime exception
        testString.append('@');

        try {
            GsmAlphabet.stringToGsm7BitPacked(testString.toString(), 0, 0);
            fail("expected exception");
        } catch (EncodeException ex) {
            // exception expected
        }

        // Try 254 septets with 127 extended chars

        testString.setLength(0);
        for (int i = 0; i < (255 / 2); i++) {
            testString.append('{');
        }

        packed = GsmAlphabet.stringToGsm7BitPacked(testString.toString(), 0, 0);
        assertEquals(testString.toString(),
                GsmAlphabet.gsm7BitPackedToString(packed, 1, 0xff & packed[0]));

        // > 255 septets throws runtime exception
        testString.append('{');

        try {
            GsmAlphabet.stringToGsm7BitPacked(testString.toString(), 0, 0);
            fail("expected exception");
        } catch (EncodeException ex) {
            // exception expected
        }

        // Reserved for extension to extension table (mapped to space)
        packed = new byte[]{(byte)(0x1b | 0x80), 0x1b >> 1};
        assertEquals(" ", GsmAlphabet.gsm7BitPackedToString(packed, 0, 2));

        // Unmappable (mapped to character in default alphabet table)
        packed[0] = 0x1b;
        packed[1] = 0x00;
        assertEquals("@", GsmAlphabet.gsm7BitPackedToString(packed, 0, 2));
        packed[0] = (byte)(0x1b | 0x80);
        packed[1] = (byte)(0x7f >> 1);
        assertEquals("\u00e0", GsmAlphabet.gsm7BitPackedToString(packed, 0, 2));

        //
        // 8 bit unpacked format
        //
        // Note: we compare hex strings here
        // because Assert doesn't have array comparisons

        byte unpacked[];

        unpacked = IccUtils.hexStringToBytes("566F696365204D61696C");
        assertEquals("Voice Mail",
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, unpacked.length));

        assertEquals(IccUtils.bytesToHexString(unpacked),
                IccUtils.bytesToHexString(
                        GsmAlphabet.stringToGsm8BitPacked("Voice Mail")));

        unpacked = GsmAlphabet.stringToGsm8BitPacked(sGsmExtendedChars);
        // two bytes for every extended char
        assertEquals(2 * sGsmExtendedChars.length(), unpacked.length);
        assertEquals(sGsmExtendedChars,
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, unpacked.length));

        // should be two bytes per extended char
        assertEquals(2 * sGsmExtendedChars.length(), unpacked.length);

        // Test truncation of unaligned extended chars
        unpacked = new byte[3];
        GsmAlphabet.stringToGsm8BitUnpackedField(sGsmExtendedChars, unpacked,
                0, unpacked.length);

        // Should be one extended char and an 0xff at the end

        assertEquals(0xff, 0xff & unpacked[2]);
        assertEquals(sGsmExtendedChars.substring(0, 1),
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, unpacked.length));

        // Test truncation of normal chars
        unpacked = new byte[3];
        GsmAlphabet.stringToGsm8BitUnpackedField("abcd", unpacked,
                0, unpacked.length);

        assertEquals("abc",
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, unpacked.length));

        // Test truncation of mixed normal and extended chars
        unpacked = new byte[3];
        GsmAlphabet.stringToGsm8BitUnpackedField("a{cd", unpacked,
                0, unpacked.length);

        assertEquals("a{",
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, unpacked.length));

        // Test padding after normal char
        unpacked = new byte[3];
        GsmAlphabet.stringToGsm8BitUnpackedField("a", unpacked,
                0, unpacked.length);

        assertEquals("a",
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, unpacked.length));

        assertEquals(0xff, 0xff & unpacked[1]);
        assertEquals(0xff, 0xff & unpacked[2]);

        // Test malformed input -- escape char followed by end of field
        unpacked[0] = 0;
        unpacked[1] = 0;
        unpacked[2] = GsmAlphabet.GSM_EXTENDED_ESCAPE;

        assertEquals("@@",
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, unpacked.length));

        // non-zero offset
        assertEquals("@",
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 1, unpacked.length - 1));

        // test non-zero offset
        unpacked[0] = 0;
        GsmAlphabet.stringToGsm8BitUnpackedField("abcd", unpacked,
                1, unpacked.length - 1);


        assertEquals(0, unpacked[0]);

        assertEquals("ab",
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 1, unpacked.length - 1));

        // test non-zero offset with truncated extended char
        unpacked[0] = 0;

        GsmAlphabet.stringToGsm8BitUnpackedField("a{", unpacked,
                1, unpacked.length - 1);

        assertEquals(0, unpacked[0]);

        assertEquals("a",
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 1, unpacked.length - 1));

        // Reserved for extension to extension table (mapped to space)
        unpacked[0] = 0x1b;
        unpacked[1] = 0x1b;
        assertEquals(" ", GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, 2));

        // Unmappable (mapped to character in default or national locking shift table)
        unpacked[1] = 0x00;
        assertEquals("@", GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, 2));
        unpacked[1] = 0x7f;
        assertEquals("\u00e0", GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, 2));
    }

    @SmallTest
    public void testGsm8BitUpackedWithEuckr() throws Exception {
        // Some feature phones in Korea store contacts as euc-kr.
        // Test this situations.
        byte unpacked[];

        // Test general alphabet strings.
        unpacked = IccUtils.hexStringToBytes("61626320646566FF");
        assertEquals("abc def",
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, unpacked.length, "euc-kr"));

        // Test korean strings.
        unpacked = IccUtils.hexStringToBytes("C5D7BDBAC6AEFF");
        assertEquals("\uD14C\uC2A4\uD2B8",
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, unpacked.length, "euc-kr"));

        // Test gsm Extented Characters.
        unpacked = GsmAlphabet.stringToGsm8BitPacked(sGsmExtendedChars);
        assertEquals(sGsmExtendedChars,
                GsmAlphabet.gsm8BitUnpackedToString(unpacked, 0, unpacked.length, "euc-kr"));
    }
}
