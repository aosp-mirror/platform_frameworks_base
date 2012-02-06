/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.telephony.SmsCbLocation;
import android.telephony.SmsCbMessage;
import android.util.Pair;

import com.android.internal.telephony.GsmAlphabet;

import java.io.UnsupportedEncodingException;

/**
 * Parses a GSM or UMTS format SMS-CB message into an {@link SmsCbMessage} object. The class is
 * public because {@link #createSmsCbMessage(SmsCbLocation, byte[][])} is used by some test cases.
 */
public class GsmSmsCbMessage {

    /**
     * Languages in the 0000xxxx DCS group as defined in 3GPP TS 23.038, section 5.
     */
    private static final String[] LANGUAGE_CODES_GROUP_0 = {
            "de", "en", "it", "fr", "es", "nl", "sv", "da", "pt", "fi", "no", "el", "tr", "hu",
            "pl", null
    };

    /**
     * Languages in the 0010xxxx DCS group as defined in 3GPP TS 23.038, section 5.
     */
    private static final String[] LANGUAGE_CODES_GROUP_2 = {
            "cs", "he", "ar", "ru", "is", null, null, null, null, null, null, null, null, null,
            null, null
    };

    private static final char CARRIAGE_RETURN = 0x0d;

    private static final int PDU_BODY_PAGE_LENGTH = 82;

    /** Utility class with only static methods. */
    private GsmSmsCbMessage() { }

    /**
     * Create a new SmsCbMessage object from a header object plus one or more received PDUs.
     *
     * @param pdus PDU bytes
     */
    static SmsCbMessage createSmsCbMessage(SmsCbHeader header, SmsCbLocation location,
            byte[][] pdus) throws IllegalArgumentException {
        if (header.isEtwsPrimaryNotification()) {
            return new SmsCbMessage(SmsCbMessage.MESSAGE_FORMAT_3GPP,
                    header.getGeographicalScope(), header.getSerialNumber(),
                    location, header.getServiceCategory(),
                    null, "ETWS", SmsCbMessage.MESSAGE_PRIORITY_EMERGENCY,
                    header.getEtwsInfo(), header.getCmasInfo());
        } else {
            String language = null;
            StringBuilder sb = new StringBuilder();
            for (byte[] pdu : pdus) {
                Pair<String, String> p = parseBody(header, pdu);
                language = p.first;
                sb.append(p.second);
            }
            int priority = header.isEmergencyMessage() ? SmsCbMessage.MESSAGE_PRIORITY_EMERGENCY
                    : SmsCbMessage.MESSAGE_PRIORITY_NORMAL;

            return new SmsCbMessage(SmsCbMessage.MESSAGE_FORMAT_3GPP,
                    header.getGeographicalScope(), header.getSerialNumber(), location,
                    header.getServiceCategory(), language, sb.toString(), priority,
                    header.getEtwsInfo(), header.getCmasInfo());
        }
    }

    /**
     * Create a new SmsCbMessage object from one or more received PDUs. This is used by some
     * CellBroadcastReceiver test cases, because SmsCbHeader is now package local.
     *
     * @param location the location (geographical scope) for the message
     * @param pdus PDU bytes
     */
    public static SmsCbMessage createSmsCbMessage(SmsCbLocation location, byte[][] pdus)
            throws IllegalArgumentException {
        SmsCbHeader header = new SmsCbHeader(pdus[0]);
        return createSmsCbMessage(header, location, pdus);
    }

    /**
     * Parse and unpack the body text according to the encoding in the DCS.
     * After completing successfully this method will have assigned the body
     * text into mBody, and optionally the language code into mLanguage
     *
     * @param header the message header to use
     * @param pdu the PDU to decode
     * @return a Pair of Strings containing the language and body of the message
     */
    private static Pair<String, String> parseBody(SmsCbHeader header, byte[] pdu) {
        int encoding;
        String language = null;
        boolean hasLanguageIndicator = false;
        int dataCodingScheme = header.getDataCodingScheme();

        // Extract encoding and language from DCS, as defined in 3gpp TS 23.038,
        // section 5.
        switch ((dataCodingScheme & 0xf0) >> 4) {
            case 0x00:
                encoding = android.telephony.SmsMessage.ENCODING_7BIT;
                language = LANGUAGE_CODES_GROUP_0[dataCodingScheme & 0x0f];
                break;

            case 0x01:
                hasLanguageIndicator = true;
                if ((dataCodingScheme & 0x0f) == 0x01) {
                    encoding = android.telephony.SmsMessage.ENCODING_16BIT;
                } else {
                    encoding = android.telephony.SmsMessage.ENCODING_7BIT;
                }
                break;

            case 0x02:
                encoding = android.telephony.SmsMessage.ENCODING_7BIT;
                language = LANGUAGE_CODES_GROUP_2[dataCodingScheme & 0x0f];
                break;

            case 0x03:
                encoding = android.telephony.SmsMessage.ENCODING_7BIT;
                break;

            case 0x04:
            case 0x05:
                switch ((dataCodingScheme & 0x0c) >> 2) {
                    case 0x01:
                        encoding = android.telephony.SmsMessage.ENCODING_8BIT;
                        break;

                    case 0x02:
                        encoding = android.telephony.SmsMessage.ENCODING_16BIT;
                        break;

                    case 0x00:
                    default:
                        encoding = android.telephony.SmsMessage.ENCODING_7BIT;
                        break;
                }
                break;

            case 0x06:
            case 0x07:
                // Compression not supported
            case 0x09:
                // UDH structure not supported
            case 0x0e:
                // Defined by the WAP forum not supported
                throw new IllegalArgumentException("Unsupported GSM dataCodingScheme "
                        + dataCodingScheme);

            case 0x0f:
                if (((dataCodingScheme & 0x04) >> 2) == 0x01) {
                    encoding = android.telephony.SmsMessage.ENCODING_8BIT;
                } else {
                    encoding = android.telephony.SmsMessage.ENCODING_7BIT;
                }
                break;

            default:
                // Reserved values are to be treated as 7-bit
                encoding = android.telephony.SmsMessage.ENCODING_7BIT;
                break;
        }

        if (header.isUmtsFormat()) {
            // Payload may contain multiple pages
            int nrPages = pdu[SmsCbHeader.PDU_HEADER_LENGTH];

            if (pdu.length < SmsCbHeader.PDU_HEADER_LENGTH + 1 + (PDU_BODY_PAGE_LENGTH + 1)
                    * nrPages) {
                throw new IllegalArgumentException("Pdu length " + pdu.length + " does not match "
                        + nrPages + " pages");
            }

            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < nrPages; i++) {
                // Each page is 82 bytes followed by a length octet indicating
                // the number of useful octets within those 82
                int offset = SmsCbHeader.PDU_HEADER_LENGTH + 1 + (PDU_BODY_PAGE_LENGTH + 1) * i;
                int length = pdu[offset + PDU_BODY_PAGE_LENGTH];

                if (length > PDU_BODY_PAGE_LENGTH) {
                    throw new IllegalArgumentException("Page length " + length
                            + " exceeds maximum value " + PDU_BODY_PAGE_LENGTH);
                }

                Pair<String, String> p = unpackBody(pdu, encoding, offset, length,
                        hasLanguageIndicator, language);
                language = p.first;
                sb.append(p.second);
            }
            return new Pair<String, String>(language, sb.toString());
        } else {
            // Payload is one single page
            int offset = SmsCbHeader.PDU_HEADER_LENGTH;
            int length = pdu.length - offset;

            return unpackBody(pdu, encoding, offset, length, hasLanguageIndicator, language);
        }
    }

    /**
     * Unpack body text from the pdu using the given encoding, position and
     * length within the pdu
     *
     * @param pdu The pdu
     * @param encoding The encoding, as derived from the DCS
     * @param offset Position of the first byte to unpack
     * @param length Number of bytes to unpack
     * @param hasLanguageIndicator true if the body text is preceded by a
     *            language indicator. If so, this method will as a side-effect
     *            assign the extracted language code into mLanguage
     * @param language the language to return if hasLanguageIndicator is false
     * @return a Pair of Strings containing the language and body of the message
     */
    private static Pair<String, String> unpackBody(byte[] pdu, int encoding, int offset, int length,
            boolean hasLanguageIndicator, String language) {
        String body = null;

        switch (encoding) {
            case android.telephony.SmsMessage.ENCODING_7BIT:
                body = GsmAlphabet.gsm7BitPackedToString(pdu, offset, length * 8 / 7);

                if (hasLanguageIndicator && body != null && body.length() > 2) {
                    // Language is two GSM characters followed by a CR.
                    // The actual body text is offset by 3 characters.
                    language = body.substring(0, 2);
                    body = body.substring(3);
                }
                break;

            case android.telephony.SmsMessage.ENCODING_16BIT:
                if (hasLanguageIndicator && pdu.length >= offset + 2) {
                    // Language is two GSM characters.
                    // The actual body text is offset by 2 bytes.
                    language = GsmAlphabet.gsm7BitPackedToString(pdu, offset, 2);
                    offset += 2;
                    length -= 2;
                }

                try {
                    body = new String(pdu, offset, (length & 0xfffe), "utf-16");
                } catch (UnsupportedEncodingException e) {
                    // Apparently it wasn't valid UTF-16.
                    throw new IllegalArgumentException("Error decoding UTF-16 message", e);
                }
                break;

            default:
                break;
        }

        if (body != null) {
            // Remove trailing carriage return
            for (int i = body.length() - 1; i >= 0; i--) {
                if (body.charAt(i) != CARRIAGE_RETURN) {
                    body = body.substring(0, i + 1);
                    break;
                }
            }
        } else {
            body = "";
        }

        return new Pair<String, String>(language, body);
    }
}
