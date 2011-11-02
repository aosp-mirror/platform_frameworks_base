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

import android.telephony.TelephonyManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.telephony.gsm.SmsMessage;
import com.android.internal.util.HexDump;

import java.util.ArrayList;

public class GsmSmsTest extends AndroidTestCase {

    @SmallTest
    public void testAddressing() throws Exception {
        String pdu = "07914151551512f2040B916105551511f100006060605130308A04D4F29C0E";
        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));
        assertEquals("+14155551212", sms.getServiceCenterAddress());
        assertEquals("+16505551111", sms.getOriginatingAddress());
        assertEquals("Test", sms.getMessageBody());

        pdu = "07914151551512f2040B916105551511f100036060924180008A0DA"
                + "8695DAC2E8FE9296A794E07";
        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));
        assertEquals("+14155551212", sms.getServiceCenterAddress());
        assertEquals("+16505551111", sms.getOriginatingAddress());
        assertEquals("(Subject)Test", sms.getMessageBody());
    }

    @SmallTest
    public void testUdh() throws Exception {
        String pdu = "07914140279510F6440A8111110301003BF56080207130138A8C0B05040B8423F"
                + "000032A02010106276170706C69636174696F6E2F766E642E7761702E6D6D732D"
                + "6D65737361676500AF848D0185B4848C8298524E453955304A6D7135514141426"
                + "66C414141414D7741414236514141414141008D908918802B3135313232393737"
                + "3638332F545950453D504C4D4E008A808E022B918805810306977F83687474703"
                + "A2F2F36";
        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));
        SmsHeader header = sms.getUserDataHeader();
        assertNotNull(header);
        assertNotNull(header.concatRef);
        assertEquals(header.concatRef.refNumber, 42);
        assertEquals(header.concatRef.msgCount, 2);
        assertEquals(header.concatRef.seqNumber, 1);
        assertEquals(header.concatRef.isEightBits, true);
        assertNotNull(header.portAddrs);
        assertEquals(header.portAddrs.destPort, 2948);
        assertEquals(header.portAddrs.origPort, 9200);
        assertEquals(header.portAddrs.areEightBits, false);

        pdu = "07914140279510F6440A8111110301003BF56080207130238A3B0B05040B8423F"
                + "000032A0202362E3130322E3137312E3135302F524E453955304A6D7135514141"
                + "42666C414141414D774141423651414141414100";
        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));
        header = sms.getUserDataHeader();
        assertNotNull(header);
        assertNotNull(header.concatRef);
        assertEquals(header.concatRef.refNumber, 42);
        assertEquals(header.concatRef.msgCount, 2);
        assertEquals(header.concatRef.seqNumber, 2);
        assertEquals(header.concatRef.isEightBits, true);
        assertNotNull(header.portAddrs);
        assertEquals(header.portAddrs.destPort, 2948);
        assertEquals(header.portAddrs.origPort, 9200);
        assertEquals(header.portAddrs.areEightBits, false);
    }

    @SmallTest
    public void testUcs2() throws Exception {
        String pdu = "07912160130300F4040B914151245584F600087010807121352B1021220"
                + "0A900AE00680065006C006C006F";
        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));
        assertEquals("\u2122\u00a9\u00aehello", sms.getMessageBody());
    }

    @SmallTest
    public void testMultipart() throws Exception {
        /*
         * Multi-part text SMS with septet data.
         */
        String pdu = "07916163838408F6440B816105224431F700007060217175830AA0050003"
                + "00020162B1582C168BC562B1582C168BC562B1582C168BC562B1582C"
                + "168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C"
                + "168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C"
                + "168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C"
                + "168BC562B1582C168BC562B1582C168BC562B1582C168BC562";
        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));
        assertEquals(sms.getMessageBody(),
                "1111111111111111111111111111111111111111"
                + "1111111111111111111111111111111111111111"
                + "1111111111111111111111111111111111111111"
                + "111111111111111111111111111111111");

        pdu = "07916163838408F6440B816105224431F700007060217185000A23050003"
                + "00020262B1582C168BC96432994C2693C96432994C2693C96432990C";
        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));
        assertEquals("1111111222222222222222222222", sms.getMessageBody());
    }

    @SmallTest
    public void testCPHSVoiceMail() throws Exception {
        // "set MWI flag"

        String pdu = "07912160130310F20404D0110041006060627171118A0120";

        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertTrue(sms.isReplace());
        assertEquals("_@", sms.getOriginatingAddress());
        assertEquals(" ", sms.getMessageBody());
        assertTrue(sms.isMWISetMessage());

        // "clear mwi flag"

        pdu = "07912160130310F20404D0100041006021924193352B0120";

        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertTrue(sms.isMWIClearMessage());

        // "clear MWI flag"

        pdu = "07912160130310F20404D0100041006060627161058A0120";

        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertTrue(sms.isReplace());
        assertEquals("\u0394@", sms.getOriginatingAddress());
        assertEquals(" ", sms.getMessageBody());
        assertTrue(sms.isMWIClearMessage());
    }

    @SmallTest
    public void testCingularVoiceMail() throws Exception {
        // "set MWI flag"

        String pdu = "07912180958750F84401800500C87020026195702B06040102000200";
        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertTrue(sms.isMWISetMessage());
        assertTrue(sms.isMwiDontStore());

        // "clear mwi flag"

        pdu = "07912180958750F84401800500C07020027160112B06040102000000";
        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertTrue(sms.isMWIClearMessage());
        assertTrue(sms.isMwiDontStore());
    }

    @SmallTest
    public void testEmailGateway() throws Exception {
        String pdu = "07914151551512f204038105f300007011103164638a28e6f71b50c687db" +
                "7076d9357eb7412f7a794e07cdeb6275794c07bde8e5391d247e93f3";

        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertEquals("+14155551212", sms.getServiceCenterAddress());
        assertTrue(sms.isEmail());
        assertEquals("foo@example.com", sms.getEmailFrom());
        assertEquals("foo@example.com", sms.getDisplayOriginatingAddress());
        // As of https://android-git.corp.google.com/g/#change,9324
        // getPseudoSubject will always be empty, and any subject is not extracted.
        assertEquals("", sms.getPseudoSubject());
        assertEquals("test subject /test body", sms.getDisplayMessageBody());
        assertEquals("test subject /test body", sms.getEmailBody());

        // email gateway sms test, including gsm extended character set.
        pdu = "07914151551512f204038105f400007011103105458a29e6f71b50c687db" +
                "7076d9357eb741af0d0a442fcfe9c23739bfe16d289bdee6b5f1813629";

        sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertEquals("+14155551212", sms.getServiceCenterAddress());
        assertTrue(sms.isEmail());
        assertEquals("foo@example.com", sms.getDisplayOriginatingAddress());
        assertEquals("foo@example.com", sms.getEmailFrom());
        assertEquals("{ testBody[^~\\] }", sms.getDisplayMessageBody());
        assertEquals("{ testBody[^~\\] }", sms.getEmailBody());
    }

    @SmallTest
    public void testExtendedCharacterTable() throws Exception {
        String pdu = "07914151551512f2040B916105551511f100006080615131728A44D4F29C0E2" +
                "AE3E96537B94C068DD16179784C2FCB41F4B0985D06B958ADD00FB0E94536AF9749" +
                "74DA6D281BA00E95E26D509B946FC3DBF87A25D56A04";

        SmsMessage sms = SmsMessage.createFromPdu(HexDump.hexStringToByteArray(pdu));

        assertEquals("+14155551212", sms.getServiceCenterAddress());
        assertEquals("+16505551111", sms.getOriginatingAddress());
        assertEquals("Test extended character table .,-!?@~_\\/&\"';^|:()<{}>[]=%*+#",
                sms.getMessageBody());
    }

    // GSM 7 bit tables in String form, Escape (0x1B) replaced with '@'
    private static final String[] sBasicTables = {
        // GSM 7 bit default alphabet
        "@\u00a3$\u00a5\u00e8\u00e9\u00f9\u00ec\u00f2\u00c7\n\u00d8\u00f8\r\u00c5\u00e5\u0394_"
            + "\u03a6\u0393\u039b\u03a9\u03a0\u03a8\u03a3\u0398\u039e@\u00c6\u00e6\u00df\u00c9"
            + " !\"#\u00a4%&'()*+,-./0123456789:;<=>?\u00a1ABCDEFGHIJKLMNOPQRSTUVWXYZ\u00c4\u00d6"
            + "\u00d1\u00dc\u00a7\u00bfabcdefghijklmnopqrstuvwxyz\u00e4\u00f6\u00f1\u00fc\u00e0",

        // Turkish locking shift table
        "@\u00a3$\u00a5\u20ac\u00e9\u00f9\u0131\u00f2\u00c7\n\u011e\u011f\r\u00c5\u00e5\u0394_"
            + "\u03a6\u0393\u039b\u03a9\u03a0\u03a8\u03a3\u0398\u039e@\u015e\u015f\u00df\u00c9"
            + " !\"#\u00a4%&'()*+,-./0123456789:;<=>?\u0130ABCDEFGHIJKLMNOPQRSTUVWXYZ\u00c4\u00d6"
            + "\u00d1\u00dc\u00a7\u00e7abcdefghijklmnopqrstuvwxyz\u00e4\u00f6\u00f1\u00fc\u00e0",

        // no locking shift table defined for Spanish
        "",

        // Portuguese locking shift table
        "@\u00a3$\u00a5\u00ea\u00e9\u00fa\u00ed\u00f3\u00e7\n\u00d4\u00f4\r\u00c1\u00e1\u0394_"
            + "\u00aa\u00c7\u00c0\u221e^\\\u20ac\u00d3|@\u00c2\u00e2\u00ca\u00c9 !\"#\u00ba%&'()"
            + "*+,-./0123456789:;<=>?\u00cdABCDEFGHIJKLMNOPQRSTUVWXYZ\u00c3\u00d5\u00da\u00dc"
            + "\u00a7~abcdefghijklmnopqrstuvwxyz\u00e3\u00f5`\u00fc\u00e0"
    };

    @SmallTest
    public void testFragmentText() throws Exception {
        boolean isGsmPhone = (TelephonyManager.getDefault().getPhoneType() ==
                TelephonyManager.PHONE_TYPE_GSM);

        // Valid 160 character 7-bit text.
        String text = "123456789012345678901234567890123456789012345678901234567890" +
                "1234567890123456789012345678901234567890123456789012345678901234567890" +
                "123456789012345678901234567890";
        SmsMessageBase.TextEncodingDetails ted = SmsMessage.calculateLength(text, false);
        assertEquals(1, ted.msgCount);
        assertEquals(160, ted.codeUnitCount);
        assertEquals(1, ted.codeUnitSize);
        assertEquals(0, ted.languageTable);
        assertEquals(0, ted.languageShiftTable);
        if (isGsmPhone) {
            ArrayList<String> fragments = android.telephony.SmsMessage.fragmentText(text);
            assertEquals(1, fragments.size());
        }

        // Valid 161 character 7-bit text.
        text = "123456789012345678901234567890123456789012345678901234567890" +
                "1234567890123456789012345678901234567890123456789012345678901234567890" +
                "1234567890123456789012345678901";
        ted = SmsMessage.calculateLength(text, false);
        assertEquals(2, ted.msgCount);
        assertEquals(161, ted.codeUnitCount);
        assertEquals(1, ted.codeUnitSize);
        assertEquals(0, ted.languageTable);
        assertEquals(0, ted.languageShiftTable);
        if (isGsmPhone) {
            ArrayList<String> fragments = android.telephony.SmsMessage.fragmentText(text);
            assertEquals(2, fragments.size());
            assertEquals(text, fragments.get(0) + fragments.get(1));
            assertEquals(153, fragments.get(0).length());
            assertEquals(8, fragments.get(1).length());
        }
    }

    @SmallTest
    public void testFragmentTurkishText() throws Exception {
        boolean isGsmPhone = (TelephonyManager.getDefault().getPhoneType() ==
                TelephonyManager.PHONE_TYPE_GSM);

        int[] oldTables = GsmAlphabet.getEnabledSingleShiftTables();
        int[] turkishTable = { 1 };
        GsmAlphabet.setEnabledSingleShiftTables(turkishTable);

        // Valid 77 character text with Turkish characters.
        String text = "ĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşı" +
                "ĞŞİğşıĞŞİğşıĞŞİğş";
        SmsMessageBase.TextEncodingDetails ted = SmsMessage.calculateLength(text, false);
        assertEquals(1, ted.msgCount);
        assertEquals(154, ted.codeUnitCount);
        assertEquals(1, ted.codeUnitSize);
        assertEquals(0, ted.languageTable);
        assertEquals(1, ted.languageShiftTable);
        if (isGsmPhone) {
            ArrayList<String> fragments = android.telephony.SmsMessage.fragmentText(text);
            assertEquals(1, fragments.size());
            assertEquals(text, fragments.get(0));
            assertEquals(77, fragments.get(0).length());
        }

        // Valid 78 character text with Turkish characters.
        text = "ĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşı" +
                "ĞŞİğşıĞŞİğşıĞŞİğşı";
        ted = SmsMessage.calculateLength(text, false);
        assertEquals(2, ted.msgCount);
        assertEquals(156, ted.codeUnitCount);
        assertEquals(1, ted.codeUnitSize);
        assertEquals(0, ted.languageTable);
        assertEquals(1, ted.languageShiftTable);
        if (isGsmPhone) {
            ArrayList<String> fragments = android.telephony.SmsMessage.fragmentText(text);
            assertEquals(2, fragments.size());
            assertEquals(text, fragments.get(0) + fragments.get(1));
            assertEquals(74, fragments.get(0).length());
            assertEquals(4, fragments.get(1).length());
        }

        // Valid 160 character text with Turkish characters.
        text = "ĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşı" +
                "ĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğ" +
                "ĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşıĞŞİğşı";
        ted = SmsMessage.calculateLength(text, false);
        assertEquals(3, ted.msgCount);
        assertEquals(320, ted.codeUnitCount);
        assertEquals(1, ted.codeUnitSize);
        assertEquals(0, ted.languageTable);
        assertEquals(1, ted.languageShiftTable);
        if (isGsmPhone) {
            ArrayList<String> fragments = android.telephony.SmsMessage.fragmentText(text);
            assertEquals(3, fragments.size());
            assertEquals(text, fragments.get(0) + fragments.get(1) + fragments.get(2));
            assertEquals(74, fragments.get(0).length());
            assertEquals(74, fragments.get(1).length());
            assertEquals(12, fragments.get(2).length());
        }

        GsmAlphabet.setEnabledSingleShiftTables(oldTables);
    }


    @SmallTest
    public void testDecode() throws Exception {
        decodeSingle(0);    // default table
        decodeSingle(1);    // Turkish locking shift table
        decodeSingle(3);    // Portuguese locking shift table
    }

    private void decodeSingle(int language) throws Exception {
        byte[] septets = new byte[(7 * 128 + 7) / 8];

        int bitOffset = 0;

        for (int i = 0; i < 128; i++) {
            int v;
            if (i == 0x1b) {
                // extended escape char
                v = 0;
            } else {
                v = i;
            }

            int byteOffset = bitOffset / 8;
            int shift = bitOffset % 8;

            septets[byteOffset] |= v << shift;

            if (shift > 1) {
                septets[byteOffset + 1] = (byte) (v >> (8 - shift));
            }

            bitOffset += 7;
        }

        String decoded = GsmAlphabet.gsm7BitPackedToString(septets, 0, 128, 0, language, 0);
        byte[] reEncoded = GsmAlphabet.stringToGsm7BitPacked(decoded, language, 0);

        assertEquals(sBasicTables[language], decoded);

        // reEncoded has the count septets byte at the front
        assertEquals(septets.length + 1, reEncoded.length);

        for (int i = 0; i < septets.length; i++) {
            assertEquals(septets[i], reEncoded[i + 1]);
        }
    }

    private static final int GSM_ESCAPE_CHARACTER = 0x1b;

    private static final String[] sExtendedTables = {
        // GSM 7 bit default alphabet extension table
        "\f^{}\\[~]|\u20ac",

        // Turkish single shift extension table
        "\f^{}\\[~]|\u011e\u0130\u015e\u00e7\u20ac\u011f\u0131\u015f",

        // Spanish single shift extension table
        "\u00e7\f^{}\\[~]|\u00c1\u00cd\u00d3\u00da\u00e1\u20ac\u00ed\u00f3\u00fa",

        // Portuguese single shift extension table
        "\u00ea\u00e7\f\u00d4\u00f4\u00c1\u00e1\u03a6\u0393^\u03a9\u03a0\u03a8\u03a3\u0398\u00ca"
            + "{}\\[~]|\u00c0\u00cd\u00d3\u00da\u00c3\u00d5\u00c2\u20ac\u00ed\u00f3\u00fa\u00e3"
            + "\u00f5\u00e2"
    };

    private static final int[][] sExtendedTableIndexes = {
        {0x0a, 0x14, 0x28, 0x29, 0x2f, 0x3c, 0x3d, 0x3e, 0x40, 0x65},
        {0x0a, 0x14, 0x28, 0x29, 0x2f, 0x3c, 0x3d, 0x3e, 0x40, 0x47, 0x49, 0x53, 0x63,
                0x65, 0x67, 0x69, 0x73},
        {0x09, 0x0a, 0x14, 0x28, 0x29, 0x2f, 0x3c, 0x3d, 0x3e, 0x40, 0x41, 0x49, 0x4f,
                0x55, 0x61, 0x65, 0x69, 0x6f, 0x75},
        {0x05, 0x09, 0x0a, 0x0b, 0x0c, 0x0e, 0x0f, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
                0x18, 0x19, 0x1f, 0x28, 0x29, 0x2f, 0x3c, 0x3d, 0x3e, 0x40, 0x41, 0x49,
                0x4f, 0x55, 0x5b, 0x5c, 0x61, 0x65, 0x69, 0x6f, 0x75, 0x7b, 0x7c, 0x7f}
    };

    @SmallTest
    public void testDecodeExtended() throws Exception {
        for (int language = 0; language < 3; language++) {
            int[] tableIndex = sExtendedTableIndexes[language];
            int numSeptets = tableIndex.length * 2;  // two septets per extended char
            byte[] septets = new byte[(7 * numSeptets + 7) / 8];

            int bitOffset = 0;

            for (int v : tableIndex) {
                // escape character
                int byteOffset = bitOffset / 8;
                int shift = bitOffset % 8;

                septets[byteOffset] |= GSM_ESCAPE_CHARACTER << shift;

                if (shift > 1) {
                    septets[byteOffset + 1] = (byte) (GSM_ESCAPE_CHARACTER >> (8 - shift));
                }

                bitOffset += 7;

                // extended table index
                byteOffset = bitOffset / 8;
                shift = bitOffset % 8;

                septets[byteOffset] |= v << shift;

                if (shift > 1) {
                    septets[byteOffset + 1] = (byte) (v >> (8 - shift));
                }

                bitOffset += 7;
            }

            String decoded = GsmAlphabet.gsm7BitPackedToString(septets, 0, numSeptets, 0,
                    0, language);
            byte[] reEncoded = GsmAlphabet.stringToGsm7BitPacked(decoded, 0, language);

            assertEquals(sExtendedTables[language], decoded);

            // reEncoded has the count septets byte at the front
            assertEquals(septets.length + 1, reEncoded.length);

            for (int i = 0; i < septets.length; i++) {
                assertEquals(septets[i], reEncoded[i + 1]);
            }
        }
    }

    @SmallTest
    public void testDecodeExtendedFallback() throws Exception {
        // verify that unmapped characters in extension table fall back to locking shift table
        for (int language = 0; language < 3; language++) {
            int[] tableIndex = sExtendedTableIndexes[language];
            int numChars = 128 - tableIndex.length;
            int numSeptets = numChars * 2;  // two septets per extended char
            byte[] septets = new byte[(7 * numSeptets + 7) / 8];

            int tableOffset = 0;
            int bitOffset = 0;

            StringBuilder defaultTable = new StringBuilder(128);
            StringBuilder turkishTable = new StringBuilder(128);
            StringBuilder portugueseTable = new StringBuilder(128);

            for (char c = 0; c < 128; c++) {
                // skip characters that are present in the current extension table
                if (tableOffset < tableIndex.length && tableIndex[tableOffset] == c) {
                    tableOffset++;
                    continue;
                }

                // escape character
                int byteOffset = bitOffset / 8;
                int shift = bitOffset % 8;

                septets[byteOffset] |= GSM_ESCAPE_CHARACTER << shift;

                if (shift > 1) {
                    septets[byteOffset + 1] = (byte) (GSM_ESCAPE_CHARACTER >> (8 - shift));
                }

                bitOffset += 7;

                // extended table index
                byteOffset = bitOffset / 8;
                shift = bitOffset % 8;

                septets[byteOffset] |= c << shift;

                if (shift > 1) {
                    septets[byteOffset + 1] = (byte) (c >> (8 - shift));
                }

                bitOffset += 7;

                if (c == GsmAlphabet.GSM_EXTENDED_ESCAPE) {
                    // double Escape maps to space character
                    defaultTable.append(' ');
                    turkishTable.append(' ');
                    portugueseTable.append(' ');
                } else {
                    // other unmapped chars map to the default or locking shift table
                    defaultTable.append(sBasicTables[0].charAt(c));
                    turkishTable.append(sBasicTables[1].charAt(c));
                    portugueseTable.append(sBasicTables[3].charAt(c));
                }
            }

            String decoded = GsmAlphabet.gsm7BitPackedToString(septets, 0, numSeptets, 0,
                    0, language);

            assertEquals(defaultTable.toString(), decoded);

            decoded = GsmAlphabet.gsm7BitPackedToString(septets, 0, numSeptets, 0, 1, language);
            assertEquals(turkishTable.toString(), decoded);

            decoded = GsmAlphabet.gsm7BitPackedToString(septets, 0, numSeptets, 0, 3, language);
            assertEquals(portugueseTable.toString(), decoded);
        }
    }
}
