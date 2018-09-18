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

package com.android.internal.telephony.uicc;

import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.telephony.Rlog;

import com.android.internal.telephony.GsmAlphabet;

import java.io.UnsupportedEncodingException;

/**
 * Various methods, useful for dealing with SIM data.
 */
public class IccUtils {
    static final String LOG_TAG="IccUtils";

    // A table mapping from a number to a hex character for fast encoding hex strings.
    private static final char[] HEX_CHARS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };


    /**
     * Many fields in GSM SIM's are stored as nibble-swizzled BCD
     *
     * Assumes left-justified field that may be padded right with 0xf
     * values.
     *
     * Stops on invalid BCD value, returning string so far
     */
    public static String
    bcdToString(byte[] data, int offset, int length) {
        StringBuilder ret = new StringBuilder(length*2);

        for (int i = offset ; i < offset + length ; i++) {
            int v;

            v = data[i] & 0xf;
            if (v > 9)  break;
            ret.append((char)('0' + v));

            v = (data[i] >> 4) & 0xf;
            // Some PLMNs have 'f' as high nibble, ignore it
            if (v == 0xf) continue;
            if (v > 9)  break;
            ret.append((char)('0' + v));
        }

        return ret.toString();
    }

    /**
     * Converts a bcd byte array to String with offset 0 and byte array length.
     */
    public static String bcdToString(byte[] data) {
        return bcdToString(data, 0, data.length);
    }

    /**
     * Converts BCD string to bytes.
     *
     * @param bcd This should have an even length. If not, an "0" will be appended to the string.
     */
    public static byte[] bcdToBytes(String bcd) {
        byte[] output = new byte[(bcd.length() + 1) / 2];
        bcdToBytes(bcd, output);
        return output;
    }

    /**
     * Converts BCD string to bytes and put it into the given byte array.
     *
     * @param bcd This should have an even length. If not, an "0" will be appended to the string.
     * @param bytes If the array size is less than needed, the rest of the BCD string isn't be
     *     converted. If the array size is more than needed, the rest of array remains unchanged.
     */
    public static void bcdToBytes(String bcd, byte[] bytes) {
        bcdToBytes(bcd, bytes, 0);
    }

    /**
     * Converts BCD string to bytes and put it into the given byte array.
     *
     * @param bcd This should have an even length. If not, an "0" will be appended to the string.
     * @param bytes If the array size is less than needed, the rest of the BCD string isn't be
     *     converted. If the array size is more than needed, the rest of array remains unchanged.
     * @param offset the offset into the bytes[] to fill the data
     */
    public static void bcdToBytes(String bcd, byte[] bytes, int offset) {
        if (bcd.length() % 2 != 0) {
            bcd += "0";
        }
        int size = Math.min((bytes.length - offset) * 2, bcd.length());
        for (int i = 0, j = offset; i + 1 < size; i += 2, j++) {
            bytes[j] = (byte) (charToByte(bcd.charAt(i + 1)) << 4 | charToByte(bcd.charAt(i)));
        }
    }

    /**
     * PLMN (MCC/MNC) is encoded as per 24.008 10.5.1.3
     * Returns a concatenated string of MCC+MNC, stripping
     * all invalid character 'F'
     */
    public static String bcdPlmnToString(byte[] data, int offset) {
        if (offset + 3 > data.length) {
            return null;
        }
        byte[] trans = new byte[3];
        trans[0] = (byte) ((data[0 + offset] << 4) | ((data[0 + offset] >> 4) & 0xF));
        trans[1] = (byte) ((data[1 + offset] << 4) | (data[2 + offset] & 0xF));
        trans[2] = (byte) ((data[2 + offset] & 0xF0) | ((data[1 + offset] >> 4) & 0xF));
        String ret = bytesToHexString(trans);

        // For a valid plmn we trim all character 'F'
        if (ret.contains("F")) {
            ret = ret.replaceAll("F", "");
        }
        return ret;
    }

    /**
     * Convert a 5 or 6 - digit PLMN string to a nibble-swizzled encoding as per 24.008 10.5.1.3
     *
     * @param plmn the PLMN to convert
     * @param data a byte array for the output
     * @param offset the offset into data to start writing
     */
    public static void stringToBcdPlmn(final String plmn, byte[] data, int offset) {
        char digit6 = (plmn.length() > 5) ? plmn.charAt(5) : 'F';
        data[offset] = (byte) (charToByte(plmn.charAt(1)) << 4 | charToByte(plmn.charAt(0)));
        data[offset + 1] = (byte) (charToByte(digit6) << 4 | charToByte(plmn.charAt(2)));
        data[offset + 2] = (byte) (charToByte(plmn.charAt(4)) << 4 | charToByte(plmn.charAt(3)));
    }

    /**
     * Some fields (like ICC ID) in GSM SIMs are stored as nibble-swizzled BCH
     */
    public static String
    bchToString(byte[] data, int offset, int length) {
        StringBuilder ret = new StringBuilder(length*2);

        for (int i = offset ; i < offset + length ; i++) {
            int v;

            v = data[i] & 0xf;
            ret.append(HEX_CHARS[v]);

            v = (data[i] >> 4) & 0xf;
            ret.append(HEX_CHARS[v]);
        }

        return ret.toString();
    }

    /**
     * Decode cdma byte into String.
     */
    public static String
    cdmaBcdToString(byte[] data, int offset, int length) {
        StringBuilder ret = new StringBuilder(length);

        int count = 0;
        for (int i = offset; count < length; i++) {
            int v;
            v = data[i] & 0xf;
            if (v > 9)  v = 0;
            ret.append((char)('0' + v));

            if (++count == length) break;

            v = (data[i] >> 4) & 0xf;
            if (v > 9)  v = 0;
            ret.append((char)('0' + v));
            ++count;
        }
        return ret.toString();
    }

    /**
     * Decodes a GSM-style BCD byte, returning an int ranging from 0-99.
     *
     * In GSM land, the least significant BCD digit is stored in the most
     * significant nibble.
     *
     * Out-of-range digits are treated as 0 for the sake of the time stamp,
     * because of this:
     *
     * TS 23.040 section 9.2.3.11
     * "if the MS receives a non-integer value in the SCTS, it shall
     * assume the digit is set to 0 but shall store the entire field
     * exactly as received"
     */
    public static int
    gsmBcdByteToInt(byte b) {
        int ret = 0;

        // treat out-of-range BCD values as 0
        if ((b & 0xf0) <= 0x90) {
            ret = (b >> 4) & 0xf;
        }

        if ((b & 0x0f) <= 0x09) {
            ret +=  (b & 0xf) * 10;
        }

        return ret;
    }

    /**
     * Decodes a CDMA style BCD byte like {@link #gsmBcdByteToInt}, but
     * opposite nibble format. The least significant BCD digit
     * is in the least significant nibble and the most significant
     * is in the most significant nibble.
     */
    public static int
    cdmaBcdByteToInt(byte b) {
        int ret = 0;

        // treat out-of-range BCD values as 0
        if ((b & 0xf0) <= 0x90) {
            ret = ((b >> 4) & 0xf) * 10;
        }

        if ((b & 0x0f) <= 0x09) {
            ret +=  (b & 0xf);
        }

        return ret;
    }

    /**
     * Decodes a string field that's formatted like the EF[ADN] alpha
     * identifier
     *
     * From TS 51.011 10.5.1:
     *   Coding:
     *       this alpha tagging shall use either
     *      -    the SMS default 7 bit coded alphabet as defined in
     *          TS 23.038 [12] with bit 8 set to 0. The alpha identifier
     *          shall be left justified. Unused bytes shall be set to 'FF'; or
     *      -    one of the UCS2 coded options as defined in annex B.
     *
     * Annex B from TS 11.11 V8.13.0:
     *      1)  If the first octet in the alpha string is '80', then the
     *          remaining octets are 16 bit UCS2 characters ...
     *      2)  if the first octet in the alpha string is '81', then the
     *          second octet contains a value indicating the number of
     *          characters in the string, and the third octet contains an
     *          8 bit number which defines bits 15 to 8 of a 16 bit
     *          base pointer, where bit 16 is set to zero and bits 7 to 1
     *          are also set to zero.  These sixteen bits constitute a
     *          base pointer to a "half page" in the UCS2 code space, to be
     *          used with some or all of the remaining octets in the string.
     *          The fourth and subsequent octets contain codings as follows:
     *          If bit 8 of the octet is set to zero, the remaining 7 bits
     *          of the octet contain a GSM Default Alphabet character,
     *          whereas if bit 8 of the octet is set to one, then the
     *          remaining seven bits are an offset value added to the
     *          16 bit base pointer defined earlier...
     *      3)  If the first octet of the alpha string is set to '82', then
     *          the second octet contains a value indicating the number of
     *          characters in the string, and the third and fourth octets
     *          contain a 16 bit number which defines the complete 16 bit
     *          base pointer to a "half page" in the UCS2 code space...
     */
    public static String
    adnStringFieldToString(byte[] data, int offset, int length) {
        if (length == 0) {
            return "";
        }
        if (length >= 1) {
            if (data[offset] == (byte) 0x80) {
                int ucslen = (length - 1) / 2;
                String ret = null;

                try {
                    ret = new String(data, offset + 1, ucslen * 2, "utf-16be");
                } catch (UnsupportedEncodingException ex) {
                    Rlog.e(LOG_TAG, "implausible UnsupportedEncodingException",
                          ex);
                }

                if (ret != null) {
                    // trim off trailing FFFF characters

                    ucslen = ret.length();
                    while (ucslen > 0 && ret.charAt(ucslen - 1) == '\uFFFF')
                        ucslen--;

                    return ret.substring(0, ucslen);
                }
            }
        }

        boolean isucs2 = false;
        char base = '\0';
        int len = 0;

        if (length >= 3 && data[offset] == (byte) 0x81) {
            len = data[offset + 1] & 0xFF;
            if (len > length - 3)
                len = length - 3;

            base = (char) ((data[offset + 2] & 0xFF) << 7);
            offset += 3;
            isucs2 = true;
        } else if (length >= 4 && data[offset] == (byte) 0x82) {
            len = data[offset + 1] & 0xFF;
            if (len > length - 4)
                len = length - 4;

            base = (char) (((data[offset + 2] & 0xFF) << 8) |
                            (data[offset + 3] & 0xFF));
            offset += 4;
            isucs2 = true;
        }

        if (isucs2) {
            StringBuilder ret = new StringBuilder();

            while (len > 0) {
                // UCS2 subset case

                if (data[offset] < 0) {
                    ret.append((char) (base + (data[offset] & 0x7F)));
                    offset++;
                    len--;
                }

                // GSM character set case

                int count = 0;
                while (count < len && data[offset + count] >= 0)
                    count++;

                ret.append(GsmAlphabet.gsm8BitUnpackedToString(data,
                           offset, count));

                offset += count;
                len -= count;
            }

            return ret.toString();
        }

        Resources resource = Resources.getSystem();
        String defaultCharset = "";
        try {
            defaultCharset =
                    resource.getString(com.android.internal.R.string.gsm_alphabet_default_charset);
        } catch (NotFoundException e) {
            // Ignore Exception and defaultCharset is set to a empty string.
        }
        return GsmAlphabet.gsm8BitUnpackedToString(data, offset, length, defaultCharset.trim());
    }

    public static int
    hexCharToInt(char c) {
        if (c >= '0' && c <= '9') return (c - '0');
        if (c >= 'A' && c <= 'F') return (c - 'A' + 10);
        if (c >= 'a' && c <= 'f') return (c - 'a' + 10);

        throw new RuntimeException ("invalid hex char '" + c + "'");
    }

    /**
     * Converts a hex String to a byte array.
     *
     * @param s A string of hexadecimal characters, must be an even number of
     *          chars long
     *
     * @return byte array representation
     *
     * @throws RuntimeException on invalid format
     */
    public static byte[]
    hexStringToBytes(String s) {
        byte[] ret;

        if (s == null) return null;

        int sz = s.length();

        ret = new byte[sz/2];

        for (int i=0 ; i <sz ; i+=2) {
            ret[i/2] = (byte) ((hexCharToInt(s.charAt(i)) << 4)
                                | hexCharToInt(s.charAt(i+1)));
        }

        return ret;
    }


    /**
     * Converts a byte array into a String of hexadecimal characters.
     *
     * @param bytes an array of bytes
     *
     * @return hex string representation of bytes array
     */
    public static String
    bytesToHexString(byte[] bytes) {
        if (bytes == null) return null;

        StringBuilder ret = new StringBuilder(2*bytes.length);

        for (int i = 0 ; i < bytes.length ; i++) {
            int b;

            b = 0x0f & (bytes[i] >> 4);

            ret.append(HEX_CHARS[b]);

            b = 0x0f & bytes[i];

            ret.append(HEX_CHARS[b]);
        }

        return ret.toString();
    }


    /**
     * Convert a TS 24.008 Section 10.5.3.5a Network Name field to a string
     * "offset" points to "octet 3", the coding scheme byte
     * empty string returned on decode error
     */
    public static String
    networkNameToString(byte[] data, int offset, int length) {
        String ret;

        if ((data[offset] & 0x80) != 0x80 || length < 1) {
            return "";
        }

        switch ((data[offset] >>> 4) & 0x7) {
            case 0:
                // SMS character set
                int countSeptets;
                int unusedBits = data[offset] & 7;
                countSeptets = (((length - 1) * 8) - unusedBits) / 7 ;
                ret =  GsmAlphabet.gsm7BitPackedToString(data, offset + 1, countSeptets);
            break;
            case 1:
                // UCS2
                try {
                    ret = new String(data,
                            offset + 1, length - 1, "utf-16");
                } catch (UnsupportedEncodingException ex) {
                    ret = "";
                    Rlog.e(LOG_TAG,"implausible UnsupportedEncodingException", ex);
                }
            break;

            // unsupported encoding
            default:
                ret = "";
            break;
        }

        // "Add CI"
        // "The MS should add the letters for the Country's Initials and
        //  a separator (e.g. a space) to the text string"

        if ((data[offset] & 0x40) != 0) {
            // FIXME(mkf) add country initials here
        }

        return ret;
    }

    /**
     * Convert a TS 131.102 image instance of code scheme '11' into Bitmap
     * @param data The raw data
     * @param length The length of image body
     * @return The bitmap
     */
    public static Bitmap parseToBnW(byte[] data, int length){
        int valueIndex = 0;
        int width = data[valueIndex++] & 0xFF;
        int height = data[valueIndex++] & 0xFF;
        int numOfPixels = width*height;

        int[] pixels = new int[numOfPixels];

        int pixelIndex = 0;
        int bitIndex = 7;
        byte currentByte = 0x00;
        while (pixelIndex < numOfPixels) {
            // reassign data and index for every byte (8 bits).
            if (pixelIndex % 8 == 0) {
                currentByte = data[valueIndex++];
                bitIndex = 7;
            }
            pixels[pixelIndex++] = bitToRGB((currentByte >> bitIndex-- ) & 0x01);
        }

        if (pixelIndex != numOfPixels) {
            Rlog.e(LOG_TAG, "parse end and size error");
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static int bitToRGB(int bit){
        if(bit == 1){
            return Color.WHITE;
        } else {
            return Color.BLACK;
        }
    }

    /**
     * a TS 131.102 image instance of code scheme '11' into color Bitmap
     *
     * @param data The raw data
     * @param length the length of image body
     * @param transparency with or without transparency
     * @return The color bitmap
     */
    public static Bitmap parseToRGB(byte[] data, int length,
            boolean transparency) {
        int valueIndex = 0;
        int width = data[valueIndex++] & 0xFF;
        int height = data[valueIndex++] & 0xFF;
        int bits = data[valueIndex++] & 0xFF;
        int colorNumber = data[valueIndex++] & 0xFF;
        int clutOffset = ((data[valueIndex++] & 0xFF) << 8)
                | (data[valueIndex++] & 0xFF);

        int[] colorIndexArray = getCLUT(data, clutOffset, colorNumber);
        if (true == transparency) {
            colorIndexArray[colorNumber - 1] = Color.TRANSPARENT;
        }

        int[] resultArray = null;
        if (0 == (8 % bits)) {
            resultArray = mapTo2OrderBitColor(data, valueIndex,
                    (width * height), colorIndexArray, bits);
        } else {
            resultArray = mapToNon2OrderBitColor(data, valueIndex,
                    (width * height), colorIndexArray, bits);
        }

        return Bitmap.createBitmap(resultArray, width, height,
                Bitmap.Config.RGB_565);
    }

    private static int[] mapTo2OrderBitColor(byte[] data, int valueIndex,
            int length, int[] colorArray, int bits) {
        if (0 != (8 % bits)) {
            Rlog.e(LOG_TAG, "not event number of color");
            return mapToNon2OrderBitColor(data, valueIndex, length, colorArray,
                    bits);
        }

        int mask = 0x01;
        switch (bits) {
        case 1:
            mask = 0x01;
            break;
        case 2:
            mask = 0x03;
            break;
        case 4:
            mask = 0x0F;
            break;
        case 8:
            mask = 0xFF;
            break;
        }

        int[] resultArray = new int[length];
        int resultIndex = 0;
        int run = 8 / bits;
        while (resultIndex < length) {
            byte tempByte = data[valueIndex++];
            for (int runIndex = 0; runIndex < run; ++runIndex) {
                int offset = run - runIndex - 1;
                resultArray[resultIndex++] = colorArray[(tempByte >> (offset * bits))
                        & mask];
            }
        }
        return resultArray;
    }

    private static int[] mapToNon2OrderBitColor(byte[] data, int valueIndex,
            int length, int[] colorArray, int bits) {
        if (0 == (8 % bits)) {
            Rlog.e(LOG_TAG, "not odd number of color");
            return mapTo2OrderBitColor(data, valueIndex, length, colorArray,
                    bits);
        }

        int[] resultArray = new int[length];
        // TODO fix me:
        return resultArray;
    }

    private static int[] getCLUT(byte[] rawData, int offset, int number) {
        if (null == rawData) {
            return null;
        }

        int[] result = new int[number];
        int endIndex = offset + (number * 3); // 1 color use 3 bytes
        int valueIndex = offset;
        int colorIndex = 0;
        int alpha = 0xff << 24;
        do {
            result[colorIndex++] = alpha
                    | ((rawData[valueIndex++] & 0xFF) << 16)
                    | ((rawData[valueIndex++] & 0xFF) << 8)
                    | ((rawData[valueIndex++] & 0xFF));
        } while (valueIndex < endIndex);
        return result;
    }

    public static String getDecimalSubstring(String iccId) {
        int position;
        for (position = 0; position < iccId.length(); position ++) {
            if (!Character.isDigit(iccId.charAt(position))) break;
        }
        return iccId.substring( 0, position );
    }

    /**
     * Converts a series of bytes to an integer. This method currently only supports positive 32-bit
     * integers.
     *
     * @param src The source bytes.
     * @param offset The position of the first byte of the data to be converted. The data is base
     *     256 with the most significant digit first.
     * @param length The length of the data to be converted. It must be <= 4.
     * @throws IllegalArgumentException If {@code length} is bigger than 4 or {@code src} cannot be
     *     parsed as a positive integer.
     * @throws IndexOutOfBoundsException If the range defined by {@code offset} and {@code length}
     *     exceeds the bounds of {@code src}.
     */
    public static int bytesToInt(byte[] src, int offset, int length) {
        if (length > 4) {
            throw new IllegalArgumentException(
                    "length must be <= 4 (only 32-bit integer supported): " + length);
        }
        if (offset < 0 || length < 0 || offset + length > src.length) {
            throw new IndexOutOfBoundsException(
                    "Out of the bounds: src=["
                            + src.length
                            + "], offset="
                            + offset
                            + ", length="
                            + length);
        }
        int result = 0;
        for (int i = 0; i < length; i++) {
            result = (result << 8) | (src[offset + i] & 0xFF);
        }
        if (result < 0) {
            throw new IllegalArgumentException(
                    "src cannot be parsed as a positive integer: " + result);
        }
        return result;
    }

    /**
     * Converts a series of bytes to a raw long variable which can be both positive and negative.
     * This method currently only supports 64-bit long variable.
     *
     * @param src The source bytes.
     * @param offset The position of the first byte of the data to be converted. The data is base
     *     256 with the most significant digit first.
     * @param length The length of the data to be converted. It must be <= 8.
     * @throws IllegalArgumentException If {@code length} is bigger than 8.
     * @throws IndexOutOfBoundsException If the range defined by {@code offset} and {@code length}
     *     exceeds the bounds of {@code src}.
     */
    public static long bytesToRawLong(byte[] src, int offset, int length) {
        if (length > 8) {
            throw new IllegalArgumentException(
                    "length must be <= 8 (only 64-bit long supported): " + length);
        }
        if (offset < 0 || length < 0 || offset + length > src.length) {
            throw new IndexOutOfBoundsException(
                    "Out of the bounds: src=["
                            + src.length
                            + "], offset="
                            + offset
                            + ", length="
                            + length);
        }
        long result = 0;
        for (int i = 0; i < length; i++) {
            result = (result << 8) | (src[offset + i] & 0xFF);
        }
        return result;
    }

    /**
     * Converts an integer to a new byte array with base 256 and the most significant digit first.
     *
     * @throws IllegalArgumentException If {@code value} is negative.
     */
    public static byte[] unsignedIntToBytes(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be 0 or positive: " + value);
        }
        byte[] bytes = new byte[byteNumForUnsignedInt(value)];
        unsignedIntToBytes(value, bytes, 0);
        return bytes;
    }

    /**
     * Converts an integer to a new byte array with base 256 and the most significant digit first.
     * The first byte's highest bit is used for sign. If the most significant digit is larger than
     * 127, an extra byte (0) will be prepended before it. This method currently doesn't support
     * negative values.
     *
     * @throws IllegalArgumentException If {@code value} is negative.
     */
    public static byte[] signedIntToBytes(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be 0 or positive: " + value);
        }
        byte[] bytes = new byte[byteNumForSignedInt(value)];
        signedIntToBytes(value, bytes, 0);
        return bytes;
    }

    /**
     * Converts an integer to a series of bytes with base 256 and the most significant digit first.
     *
     * @param value The integer to be converted.
     * @param dest The destination byte array.
     * @param offset The start offset of the byte array.
     * @return The number of byte needeed.
     * @throws IllegalArgumentException If {@code value} is negative.
     * @throws IndexOutOfBoundsException If {@code offset} exceeds the bounds of {@code dest}.
     */
    public static int unsignedIntToBytes(int value, byte[] dest, int offset) {
        return intToBytes(value, dest, offset, false);
    }

    /**
     * Converts an integer to a series of bytes with base 256 and the most significant digit first.
     * The first byte's highest bit is used for sign. If the most significant digit is larger than
     * 127, an extra byte (0) will be prepended before it. This method currently doesn't support
     * negative values.
     *
     * @throws IllegalArgumentException If {@code value} is negative.
     * @throws IndexOutOfBoundsException If {@code offset} exceeds the bounds of {@code dest}.
     */
    public static int signedIntToBytes(int value, byte[] dest, int offset) {
        return intToBytes(value, dest, offset, true);
    }

    /**
     * Calculates the number of required bytes to represent {@code value}. The bytes will be base
     * 256 with the most significant digit first.
     *
     * @throws IllegalArgumentException If {@code value} is negative.
     */
    public static int byteNumForUnsignedInt(int value) {
        return byteNumForInt(value, false);
    }

    /**
     * Calculates the number of required bytes to represent {@code value}. The bytes will be base
     * 256 with the most significant digit first. If the most significant digit is larger than 127,
     * an extra byte (0) will be prepended before it. This method currently only supports positive
     * integers.
     *
     * @throws IllegalArgumentException If {@code value} is negative.
     */
    public static int byteNumForSignedInt(int value) {
        return byteNumForInt(value, true);
    }

    private static int intToBytes(int value, byte[] dest, int offset, boolean signed) {
        int l = byteNumForInt(value, signed);
        if (offset < 0 || offset + l > dest.length) {
            throw new IndexOutOfBoundsException("Not enough space to write. Required bytes: " + l);
        }
        for (int i = l - 1, v = value; i >= 0; i--, v >>>= 8) {
            byte b = (byte) (v & 0xFF);
            dest[offset + i] = b;
        }
        return l;
    }

    private static int byteNumForInt(int value, boolean signed) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be 0 or positive: " + value);
        }
        if (signed) {
            if (value <= 0x7F) {
                return 1;
            }
            if (value <= 0x7FFF) {
                return 2;
            }
            if (value <= 0x7FFFFF) {
                return 3;
            }
        } else {
            if (value <= 0xFF) {
                return 1;
            }
            if (value <= 0xFFFF) {
                return 2;
            }
            if (value <= 0xFFFFFF) {
                return 3;
            }
        }
        return 4;
    }


    /**
     * Counts the number of trailing zero bits of a byte.
     */
    public static byte countTrailingZeros(byte b) {
        if (b == 0) {
            return 8;
        }
        int v = b & 0xFF;
        byte c = 7;
        if ((v & 0x0F) != 0) {
            c -= 4;
        }
        if ((v & 0x33) != 0) {
            c -= 2;
        }
        if ((v & 0x55) != 0) {
            c -= 1;
        }
        return c;
    }

    /**
     * Converts a byte to a hex string.
     */
    public static String byteToHex(byte b) {
        return new String(new char[] {HEX_CHARS[(b & 0xFF) >>> 4], HEX_CHARS[b & 0xF]});
    }

    /**
     * Strip all the trailing 'F' characters of a string, e.g., an ICCID.
     */
    public static String stripTrailingFs(String s) {
        return s == null ? null : s.replaceAll("(?i)f*$", "");
    }

    /**
     * Converts a character of [0-9a-fA-F] to its hex value in a byte. If the character is not a
     * hex number, 0 will be returned.
     */
    private static byte charToByte(char c) {
        if (c >= 0x30 && c <= 0x39) {
            return (byte) (c - 0x30);
        } else if (c >= 0x41 && c <= 0x46) {
            return (byte) (c - 0x37);
        } else if (c >= 0x61 && c <= 0x66) {
            return (byte) (c - 0x57);
        }
        return 0;
    }
}
