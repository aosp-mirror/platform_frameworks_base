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

import android.telephony.SmsMessage;
import android.util.SparseIntArray;

import android.util.Log;

/**
 * This class implements the character set mapping between
 * the GSM SMS 7-bit alphabet specified in TS 23.038 6.2.1
 * and UTF-16
 *
 * {@hide}
 */
public class GsmAlphabet {
    static final String LOG_TAG = "GSM";



    //***** Constants

    /**
     * This escapes extended characters, and when present indicates that the
     * following character should
     * be looked up in the "extended" table
     *
     * gsmToChar(GSM_EXTENDED_ESCAPE) returns 0xffff
     */

    public static final byte GSM_EXTENDED_ESCAPE = 0x1B;


    /**
     * char to GSM alphabet char
     * Returns ' ' in GSM alphabet if there's no possible match
     * Returns GSM_EXTENDED_ESCAPE if this character is in the extended table
     * In this case, you must call charToGsmExtended() for the value that
     * should follow GSM_EXTENDED_ESCAPE in the GSM alphabet string
     */
    public static int
    charToGsm(char c) {
        try {
            return charToGsm(c, false);
        } catch (EncodeException ex) {
            // this should never happen
            return sGsmSpaceChar;
        }
    }

    /**
     * char to GSM alphabet char
     * @param throwException If true, throws EncodeException on invalid char.
     *   If false, returns GSM alphabet ' ' char.
     *
     * Returns GSM_EXTENDED_ESCAPE if this character is in the extended table
     * In this case, you must call charToGsmExtended() for the value that
     * should follow GSM_EXTENDED_ESCAPE in the GSM alphabet string
     */

    public static int
    charToGsm(char c, boolean throwException) throws EncodeException {
        int ret;

        ret = charToGsm.get(c, -1);

        if (ret == -1) {
            ret = charToGsmExtended.get(c, -1);

            if (ret == -1) {
                if (throwException) {
                    throw new EncodeException(c);
                } else {
                    return sGsmSpaceChar;
                }
            } else {
                return GSM_EXTENDED_ESCAPE;
            }
        }

        return ret;

    }


    /**
     * char to extended GSM alphabet char
     *
     * Extended chars should be escaped with GSM_EXTENDED_ESCAPE
     *
     * Returns ' ' in GSM alphabet if there's no possible match
     *
     */
    public static int
    charToGsmExtended(char c) {
        int ret;

        ret = charToGsmExtended.get(c, -1);

        if (ret == -1) {
            return sGsmSpaceChar;
        }

        return ret;
    }

    /**
     * Converts a character in the GSM alphabet into a char
     *
     * if GSM_EXTENDED_ESCAPE is passed, 0xffff is returned. In this case,
     * the following character in the stream should be decoded with
     * gsmExtendedToChar()
     *
     * If an unmappable value is passed (one greater than 127), ' ' is returned
     */

    public static char
    gsmToChar(int gsmChar) {
        return (char)gsmToChar.get(gsmChar, ' ');
    }

    /**
     * Converts a character in the extended GSM alphabet into a char
     *
     * if GSM_EXTENDED_ESCAPE is passed, ' ' is returned since no second
     * extension page has yet been defined (see Note 1 in table 6.2.1.1 of
     * TS 23.038 v7.00)
     *
     * If an unmappable value is passed , ' ' is returned
     */

    public static char
    gsmExtendedToChar(int gsmChar) {
        int ret;

        ret = gsmExtendedToChar.get(gsmChar, -1);

        if (ret == -1) {
            return ' ';
        }

        return (char)ret;
    }

    /**
     * Converts a String into a byte array containing the 7-bit packed
     * GSM Alphabet representation of the string. If a header is provided,
     * this is included in the returned byte array and padded to a septet
     * boundary.
     *
     * Unencodable chars are encoded as spaces
     *
     * Byte 0 in the returned byte array is the count of septets used,
     * including the header and header padding. The returned byte array is
     * the minimum size required to store the packed septets. The returned
     * array cannot contain more than 255 septets.
     *
     * @param data The text string to encode.
     * @param header Optional header (includeing length byte) that precedes
     * the encoded data, padded to septet boundary.
     * @return Byte array containing header and encoded data.
     */
    public static byte[] stringToGsm7BitPackedWithHeader(String data, byte[] header)
            throws EncodeException {

        if (header == null || header.length == 0) {
            return stringToGsm7BitPacked(data);
        }

        int headerBits = (header.length + 1) * 8;
        int headerSeptets = (headerBits + 6) / 7;

        byte[] ret = stringToGsm7BitPacked(data, headerSeptets, true);

        // Paste in the header
        ret[1] = (byte)header.length;
        System.arraycopy(header, 0, ret, 2, header.length);
        return ret;
    }

    /**
     * Converts a String into a byte array containing
     * the 7-bit packed GSM Alphabet representation of the string.
     *
     * Unencodable chars are encoded as spaces
     *
     * Byte 0 in the returned byte array is the count of septets used
     * The returned byte array is the minimum size required to store
     * the packed septets. The returned array cannot contain more than 255
     * septets.
     *
     * @param data the data string to endcode
     * @throws EncodeException if String is too large to encode
     */
    public static byte[] stringToGsm7BitPacked(String data)
            throws EncodeException {
        return stringToGsm7BitPacked(data, 0, true);
    }

    /**
     * Converts a String into a byte array containing
     * the 7-bit packed GSM Alphabet representation of the string.
     *
     * Byte 0 in the returned byte array is the count of septets used
     * The returned byte array is the minimum size required to store
     * the packed septets. The returned array cannot contain more than 255
     * septets.
     *
     * @param data the text to convert to septets
     * @param startingSeptetOffset the number of padding septets to put before
     *  the character data at the begining of the array
     * @param throwException If true, throws EncodeException on invalid char.
     *   If false, replaces unencodable char with GSM alphabet space char.
     *
     * @throws EncodeException if String is too large to encode
     */
    public static byte[] stringToGsm7BitPacked(String data, int startingSeptetOffset,
            boolean throwException) throws EncodeException {
        int dataLen = data.length();
        int septetCount = countGsmSeptets(data, throwException) + startingSeptetOffset;
        if (septetCount > 255) {
            throw new EncodeException("Payload cannot exceed 255 septets");
        }
        int byteCount = ((septetCount * 7) + 7) / 8;
        byte[] ret = new byte[byteCount + 1];  // Include space for one byte length prefix.
        for (int i = 0, septets = startingSeptetOffset, bitOffset = startingSeptetOffset * 7;
                 i < dataLen && septets < septetCount;
                 i++, bitOffset += 7) {
            char c = data.charAt(i);
            int v = GsmAlphabet.charToGsm(c, throwException);
            if (v == GSM_EXTENDED_ESCAPE) {
                v = GsmAlphabet.charToGsmExtended(c);  // Lookup the extended char.
                packSmsChar(ret, bitOffset, GSM_EXTENDED_ESCAPE);
                bitOffset += 7;
                septets++;
            }
            packSmsChar(ret, bitOffset, v);
            septets++;
        }
        ret[0] = (byte) (septetCount);  // Validated by check above.
        return ret;
    }

    /**
     * Pack a 7-bit char into its appropirate place in a byte array
     *
     * @param bitOffset the bit offset that the septet should be packed at
     *                  (septet index * 7)
     */
    private static void
    packSmsChar(byte[] packedChars, int bitOffset, int value) {
        int byteOffset = bitOffset / 8;
        int shift = bitOffset % 8;

        packedChars[++byteOffset] |= value << shift;

        if (shift > 1) {
            packedChars[++byteOffset] = (byte)(value >> (8 - shift));
        }
    }

    /**
     * Convert a GSM alphabet 7 bit packed string (SMS string) into a
     * {@link java.lang.String}.
     *
     * See TS 23.038 6.1.2.1 for SMS Character Packing
     *
     * @param pdu the raw data from the pdu
     * @param offset the byte offset of
     * @param lengthSeptets string length in septets, not bytes
     * @return String representation or null on decoding exception
     */
    public static String gsm7BitPackedToString(byte[] pdu, int offset,
            int lengthSeptets) {
        return gsm7BitPackedToString(pdu, offset, lengthSeptets, 0);
    }

    /**
     * Convert a GSM alphabet 7 bit packed string (SMS string) into a
     * {@link java.lang.String}.
     *
     * See TS 23.038 6.1.2.1 for SMS Character Packing
     *
     * @param pdu the raw data from the pdu
     * @param offset the byte offset of
     * @param lengthSeptets string length in septets, not bytes
     * @param numPaddingBits the number of padding bits before the start of the
     *  string in the first byte
     * @return String representation or null on decoding exception
     */
    public static String gsm7BitPackedToString(byte[] pdu, int offset,
            int lengthSeptets, int numPaddingBits) {
        StringBuilder ret = new StringBuilder(lengthSeptets);
        boolean prevCharWasEscape;

        try {
            prevCharWasEscape = false;

            for (int i = 0 ; i < lengthSeptets ; i++) {
                int bitOffset = (7 * i) + numPaddingBits;

                int byteOffset = bitOffset / 8;
                int shift = bitOffset % 8;
                int gsmVal;

                gsmVal = (0x7f & (pdu[offset + byteOffset] >> shift));

                // if it crosses a byte boundry
                if (shift > 1) {
                    // set msb bits to 0
                    gsmVal &= 0x7f >> (shift - 1);

                    gsmVal |= 0x7f & (pdu[offset + byteOffset + 1] << (8 - shift));
                }

                if (prevCharWasEscape) {
                    ret.append(GsmAlphabet.gsmExtendedToChar(gsmVal));
                    prevCharWasEscape = false;
                } else if (gsmVal == GSM_EXTENDED_ESCAPE) {
                    prevCharWasEscape = true;
                } else {
                    ret.append(GsmAlphabet.gsmToChar(gsmVal));
                }
            }
        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "Error GSM 7 bit packed: ", ex);
            return null;
        }

        return ret.toString();
    }


    /**
     * Convert a GSM alphabet string that's stored in 8-bit unpacked
     * format (as it often appears in SIM records) into a String
     *
     * Field may be padded with trailing 0xff's. The decode stops
     * at the first 0xff encountered.
     */
    public static String
    gsm8BitUnpackedToString(byte[] data, int offset, int length) {
        boolean prevWasEscape;
        StringBuilder ret = new StringBuilder(length);

        prevWasEscape = false;
        for (int i = offset ; i < offset + length ; i++) {
            // Never underestimate the pain that can be caused
            // by signed bytes
            int c = data[i] & 0xff;

            if (c == 0xff) {
                break;
            } else if (c == GSM_EXTENDED_ESCAPE) {
                if (prevWasEscape) {
                    // Two escape chars in a row
                    // We treat this as a space
                    // See Note 1 in table 6.2.1.1 of TS 23.038 v7.00
                    ret.append(' ');
                    prevWasEscape = false;
                } else {
                    prevWasEscape = true;
                }
            } else {
                if (prevWasEscape) {
                    ret.append((char)gsmExtendedToChar.get(c, ' '));
                } else {
                    ret.append((char)gsmToChar.get(c, ' '));
                }
                prevWasEscape = false;
            }
        }

        return ret.toString();
    }

    /**
     * Convert a string into an 8-bit unpacked GSM alphabet byte
     * array
     */
    public static byte[]
    stringToGsm8BitPacked(String s) {
        byte[] ret;

        int septets = 0;

        septets = countGsmSeptets(s);

        // Enough for all the septets and the length byte prefix
        ret = new byte[septets];

        stringToGsm8BitUnpackedField(s, ret, 0, ret.length);

        return ret;
    }


    /**
     * Write a String into a GSM 8-bit unpacked field of
     * @param length size at @param offset in @param dest
     *
     * Field is padded with 0xff's, string is truncated if necessary
     */

    public static void
    stringToGsm8BitUnpackedField(String s, byte dest[], int offset, int length) {
        int outByteIndex = offset;

        // Septets are stored in byte-aligned octets
        for (int i = 0, sz = s.length()
                ; i < sz && (outByteIndex - offset) < length
                ; i++
        ) {
            char c = s.charAt(i);

            int v = GsmAlphabet.charToGsm(c);

            if (v == GSM_EXTENDED_ESCAPE) {
                // make sure we can fit an escaped char
                if (! (outByteIndex + 1 - offset < length)) {
                    break;
                }

                dest[outByteIndex++] = GSM_EXTENDED_ESCAPE;

                v = GsmAlphabet.charToGsmExtended(c);
            }

            dest[outByteIndex++] = (byte)v;
        }

        // pad with 0xff's
        while((outByteIndex - offset) < length) {
            dest[outByteIndex++] = (byte)0xff;
        }
    }

    /**
     * Returns the count of 7-bit GSM alphabet characters
     * needed to represent this character. Counts unencodable char as 1 septet.
     */
    public static int
    countGsmSeptets(char c) {
        try {
            return countGsmSeptets(c, false);
        } catch (EncodeException ex) {
            // This should never happen.
            return 0;
        }
    }

    /**
     * Returns the count of 7-bit GSM alphabet characters
     * needed to represent this character
     * @param throwsException If true, throws EncodeException if unencodable
     * char. Otherwise, counts invalid char as 1 septet
     */
    public static int
    countGsmSeptets(char c, boolean throwsException) throws EncodeException {
        if (charToGsm.get(c, -1) != -1) {
            return 1;
        }

        if (charToGsmExtended.get(c, -1) != -1) {
            return 2;
        }

        if (throwsException) {
            throw new EncodeException(c);
        } else {
            // count as a space char
            return 1;
        }
    }

    /**
     * Returns the count of 7-bit GSM alphabet characters
     * needed to represent this string. Counts unencodable char as 1 septet.
     */
    public static int
    countGsmSeptets(CharSequence s) {
        try {
            return countGsmSeptets(s, false);
        } catch (EncodeException ex) {
            // this should never happen
            return 0;
        }
    }

    /**
     * Returns the count of 7-bit GSM alphabet characters
     * needed to represent this string.
     * @param throwsException If true, throws EncodeException if unencodable
     * char. Otherwise, counts invalid char as 1 septet
     */
    public static int
    countGsmSeptets(CharSequence s, boolean throwsException) throws EncodeException {
        int charIndex = 0;
        int sz = s.length();
        int count = 0;

        while (charIndex < sz) {
            count += countGsmSeptets(s.charAt(charIndex), throwsException);
            charIndex++;
        }

        return count;
    }

    /**
     * Returns the index into <code>s</code> of the first character
     * after <code>limit</code> septets have been reached, starting at
     * index <code>start</code>.  This is used when dividing messages
     * into units within the SMS message size limit.
     *
     * @param s source string
     * @param start index of where to start counting septets
     * @param limit maximum septets to include,
     *   e.g. <code>MAX_USER_DATA_SEPTETS</code>
     * @return index of first character that won't fit, or the length
     *   of the entire string if everything fits
     */
    public static int
    findGsmSeptetLimitIndex(String s, int start, int limit) {
        int accumulator = 0;
        int size = s.length();

        for (int i = start; i < size; i++) {
            accumulator += countGsmSeptets(s.charAt(i));
            if (accumulator > limit) {
                return i;
            }
        }
        return size;
    }

    // Set in the static initializer
    private static int sGsmSpaceChar;

    private static final SparseIntArray charToGsm = new SparseIntArray();
    private static final SparseIntArray gsmToChar = new SparseIntArray();
    private static final SparseIntArray charToGsmExtended = new SparseIntArray();
    private static final SparseIntArray gsmExtendedToChar = new SparseIntArray();

    static {
        int i = 0;

        charToGsm.put('@', i++);
        charToGsm.put('\u00a3', i++);
        charToGsm.put('$', i++);
        charToGsm.put('\u00a5', i++);
        charToGsm.put('\u00e8', i++);
        charToGsm.put('\u00e9', i++);
        charToGsm.put('\u00f9', i++);
        charToGsm.put('\u00ec', i++);
        charToGsm.put('\u00f2', i++);
        charToGsm.put('\u00c7', i++);
        charToGsm.put('\n', i++);
        charToGsm.put('\u00d8', i++);
        charToGsm.put('\u00f8', i++);
        charToGsm.put('\r', i++);
        charToGsm.put('\u00c5', i++);
        charToGsm.put('\u00e5', i++);

        charToGsm.put('\u0394', i++);
        charToGsm.put('_', i++);
        charToGsm.put('\u03a6', i++);
        charToGsm.put('\u0393', i++);
        charToGsm.put('\u039b', i++);
        charToGsm.put('\u03a9', i++);
        charToGsm.put('\u03a0', i++);
        charToGsm.put('\u03a8', i++);
        charToGsm.put('\u03a3', i++);
        charToGsm.put('\u0398', i++);
        charToGsm.put('\u039e', i++);
        charToGsm.put('\uffff', i++);
        charToGsm.put('\u00c6', i++);
        charToGsm.put('\u00e6', i++);
        charToGsm.put('\u00df', i++);
        charToGsm.put('\u00c9', i++);

        charToGsm.put(' ', i++);
        charToGsm.put('!', i++);
        charToGsm.put('"', i++);
        charToGsm.put('#', i++);
        charToGsm.put('\u00a4', i++);
        charToGsm.put('%', i++);
        charToGsm.put('&', i++);
        charToGsm.put('\'', i++);
        charToGsm.put('(', i++);
        charToGsm.put(')', i++);
        charToGsm.put('*', i++);
        charToGsm.put('+', i++);
        charToGsm.put(',', i++);
        charToGsm.put('-', i++);
        charToGsm.put('.', i++);
        charToGsm.put('/', i++);

        charToGsm.put('0', i++);
        charToGsm.put('1', i++);
        charToGsm.put('2', i++);
        charToGsm.put('3', i++);
        charToGsm.put('4', i++);
        charToGsm.put('5', i++);
        charToGsm.put('6', i++);
        charToGsm.put('7', i++);
        charToGsm.put('8', i++);
        charToGsm.put('9', i++);
        charToGsm.put(':', i++);
        charToGsm.put(';', i++);
        charToGsm.put('<', i++);
        charToGsm.put('=', i++);
        charToGsm.put('>', i++);
        charToGsm.put('?', i++);

        charToGsm.put('\u00a1', i++);
        charToGsm.put('A', i++);
        charToGsm.put('B', i++);
        charToGsm.put('C', i++);
        charToGsm.put('D', i++);
        charToGsm.put('E', i++);
        charToGsm.put('F', i++);
        charToGsm.put('G', i++);
        charToGsm.put('H', i++);
        charToGsm.put('I', i++);
        charToGsm.put('J', i++);
        charToGsm.put('K', i++);
        charToGsm.put('L', i++);
        charToGsm.put('M', i++);
        charToGsm.put('N', i++);
        charToGsm.put('O', i++);

        charToGsm.put('P', i++);
        charToGsm.put('Q', i++);
        charToGsm.put('R', i++);
        charToGsm.put('S', i++);
        charToGsm.put('T', i++);
        charToGsm.put('U', i++);
        charToGsm.put('V', i++);
        charToGsm.put('W', i++);
        charToGsm.put('X', i++);
        charToGsm.put('Y', i++);
        charToGsm.put('Z', i++);
        charToGsm.put('\u00c4', i++);
        charToGsm.put('\u00d6', i++);
        charToGsm.put('\u00d1', i++);
        charToGsm.put('\u00dc', i++);
        charToGsm.put('\u00a7', i++);

        charToGsm.put('\u00bf', i++);
        charToGsm.put('a', i++);
        charToGsm.put('b', i++);
        charToGsm.put('c', i++);
        charToGsm.put('d', i++);
        charToGsm.put('e', i++);
        charToGsm.put('f', i++);
        charToGsm.put('g', i++);
        charToGsm.put('h', i++);
        charToGsm.put('i', i++);
        charToGsm.put('j', i++);
        charToGsm.put('k', i++);
        charToGsm.put('l', i++);
        charToGsm.put('m', i++);
        charToGsm.put('n', i++);
        charToGsm.put('o', i++);

        charToGsm.put('p', i++);
        charToGsm.put('q', i++);
        charToGsm.put('r', i++);
        charToGsm.put('s', i++);
        charToGsm.put('t', i++);
        charToGsm.put('u', i++);
        charToGsm.put('v', i++);
        charToGsm.put('w', i++);
        charToGsm.put('x', i++);
        charToGsm.put('y', i++);
        charToGsm.put('z', i++);
        charToGsm.put('\u00e4', i++);
        charToGsm.put('\u00f6', i++);
        charToGsm.put('\u00f1', i++);
        charToGsm.put('\u00fc', i++);
        charToGsm.put('\u00e0', i++);


        charToGsmExtended.put('\f', 10);
        charToGsmExtended.put('^', 20);
        charToGsmExtended.put('{', 40);
        charToGsmExtended.put('}', 41);
        charToGsmExtended.put('\\', 47);
        charToGsmExtended.put('[', 60);
        charToGsmExtended.put('~', 61);
        charToGsmExtended.put(']', 62);
        charToGsmExtended.put('|', 64);
        charToGsmExtended.put('\u20ac', 101);

        int size = charToGsm.size();
        for (int j=0; j<size; j++) {
            gsmToChar.put(charToGsm.valueAt(j), charToGsm.keyAt(j));
        }

        size = charToGsmExtended.size();
        for (int j=0; j<size; j++) {
            gsmExtendedToChar.put(charToGsmExtended.valueAt(j), charToGsmExtended.keyAt(j));
        }


        sGsmSpaceChar = charToGsm.get(' ');
    }


}
