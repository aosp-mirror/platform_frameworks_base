/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.telephony.cdma.sms;

import android.compat.annotation.UnsupportedAppUsage;
import android.util.SparseIntArray;

import com.android.internal.telephony.SmsHeader;
import com.android.internal.util.HexDump;

public class UserData {

    /**
     * User data encoding types.
     * (See 3GPP2 C.R1001-F, v1.0, table 9.1-1)
     */
    public static final int ENCODING_OCTET                      = 0x00;
    public static final int ENCODING_IS91_EXTENDED_PROTOCOL     = 0x01;
    public static final int ENCODING_7BIT_ASCII                 = 0x02;
    public static final int ENCODING_IA5                        = 0x03;
    public static final int ENCODING_UNICODE_16                 = 0x04;
    public static final int ENCODING_SHIFT_JIS                  = 0x05;
    public static final int ENCODING_KOREAN                     = 0x06;
    public static final int ENCODING_LATIN_HEBREW               = 0x07;
    public static final int ENCODING_LATIN                      = 0x08;
    public static final int ENCODING_GSM_7BIT_ALPHABET          = 0x09;
    public static final int ENCODING_GSM_DCS                    = 0x0A;

    /**
     * User data message type encoding types.
     * (See 3GPP2 C.S0015-B, 4.5.2 and 3GPP 23.038, Section 4)
     */
    public static final int ENCODING_GSM_DCS_7BIT               = 0x00;
    public static final int ENCODING_GSM_DCS_8BIT               = 0x01;
    public static final int ENCODING_GSM_DCS_16BIT              = 0x02;

    /**
     * IS-91 message types.
     * (See TIA/EIS/IS-91-A-ENGL 1999, table 3.7.1.1-3)
     */
    public static final int IS91_MSG_TYPE_VOICEMAIL_STATUS   = 0x82;
    public static final int IS91_MSG_TYPE_SHORT_MESSAGE_FULL = 0x83;
    public static final int IS91_MSG_TYPE_CLI                = 0x84;
    public static final int IS91_MSG_TYPE_SHORT_MESSAGE      = 0x85;

    /**
     * US ASCII character mapping table.
     *
     * This table contains only the printable ASCII characters, with a
     * 0x20 offset, meaning that the ASCII SPACE character is at index
     * 0, with the resulting code of 0x20.
     *
     * Note this mapping is also equivalent to that used by both the
     * IA5 and the IS-91 encodings.  For the former this is defined
     * using CCITT Rec. T.50 Tables 1 and 3.  For the latter IS 637 B,
     * Table 4.3.1.4.1-1 -- and note the encoding uses only 6 bits,
     * and hence only maps entries up to the '_' character.
     *
     */
    public static final char[] ASCII_MAP = {
        ' ', '!', '"', '#', '$', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', ';', '<', '=', '>', '?',
        '@', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O',
        'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '[', '\\', ']', '^', '_',
        '`', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o',
        'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '{', '|', '}', '~'};

    /**
     * Character to use when forced to encode otherwise unencodable
     * characters, meaning those not in the respective ASCII or GSM
     * 7-bit encoding tables.  Current choice is SPACE, which is 0x20
     * in both the GSM-7bit and ASCII-7bit encodings.
     */
    static final byte UNENCODABLE_7_BIT_CHAR = 0x20;

    /**
     * Only elements between these indices in the ASCII table are printable.
     */
    public static final int PRINTABLE_ASCII_MIN_INDEX = 0x20;
    public static final int ASCII_NL_INDEX = 0x0A;
    public static final int ASCII_CR_INDEX = 0x0D;
    @UnsupportedAppUsage
    public static final SparseIntArray charToAscii = new SparseIntArray();
    static {
        for (int i = 0; i < ASCII_MAP.length; i++) {
            charToAscii.put(ASCII_MAP[i], PRINTABLE_ASCII_MIN_INDEX + i);
        }
        charToAscii.put('\n', ASCII_NL_INDEX);
        charToAscii.put('\r', ASCII_CR_INDEX);
    }

    @UnsupportedAppUsage
    public UserData() {
    }

    /*
     * TODO(cleanup): Move this very generic functionality somewhere
     * more general.
     */
    /**
     * Given a string generate a corresponding ASCII-encoded byte
     * array, but limited to printable characters.  If the input
     * contains unprintable characters, return null.
     */
    public static byte[] stringToAscii(String str) {
        int len = str.length();
        byte[] result = new byte[len];
        for (int i = 0; i < len; i++) {
            int charCode = charToAscii.get(str.charAt(i), -1);
            if (charCode == -1) return null;
            result[i] = (byte)charCode;
        }
        return result;
    }

    /**
     * Mapping for ASCII values less than 32 are flow control signals
     * and not used here.
     */
    public static final int ASCII_MAP_BASE_INDEX = 0x20;
    public static final int ASCII_MAP_MAX_INDEX = ASCII_MAP_BASE_INDEX + ASCII_MAP.length - 1;

    /**
     * Contains the data header of the user data
     */
    @UnsupportedAppUsage
    public SmsHeader userDataHeader;

    /**
     * Contains the data encoding type for the SMS message
     */
    @UnsupportedAppUsage
    public int msgEncoding;
    @UnsupportedAppUsage
    public boolean msgEncodingSet = false;

    public int msgType;

    /**
     * Number of invalid bits in the last byte of data.
     */
    public int paddingBits;

    @UnsupportedAppUsage
    public int numFields;

    /**
     * Contains the user data of a SMS message
     * (See 3GPP2 C.S0015-B, v2, 4.5.2)
     */
    @UnsupportedAppUsage
    public byte[] payload;
    @UnsupportedAppUsage
    public String payloadStr;

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("UserData ");
        builder.append("{ msgEncoding=" + (msgEncodingSet ? msgEncoding : "unset"));
        builder.append(", msgType=" + msgType);
        builder.append(", paddingBits=" + paddingBits);
        builder.append(", numFields=" + numFields);
        builder.append(", userDataHeader=" + userDataHeader);
        builder.append(", payload='" + HexDump.toHexString(payload) + "'");
        builder.append(", payloadStr='" + payloadStr + "'");
        builder.append(" }");
        return builder.toString();
    }

}
