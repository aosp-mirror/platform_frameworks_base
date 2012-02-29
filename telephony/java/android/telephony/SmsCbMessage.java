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

package android.telephony;

import android.text.format.Time;
import android.util.Log;

import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.gsm.SmsCbHeader;

import java.io.UnsupportedEncodingException;

/**
 * Describes an SMS-CB message.
 *
 * {@hide}
 */
public class SmsCbMessage {

    /**
     * Cell wide immediate geographical scope
     */
    public static final int GEOGRAPHICAL_SCOPE_CELL_WIDE_IMMEDIATE = 0;

    /**
     * PLMN wide geographical scope
     */
    public static final int GEOGRAPHICAL_SCOPE_PLMN_WIDE = 1;

    /**
     * Location / service area wide geographical scope
     */
    public static final int GEOGRAPHICAL_SCOPE_LA_WIDE = 2;

    /**
     * Cell wide geographical scope
     */
    public static final int GEOGRAPHICAL_SCOPE_CELL_WIDE = 3;

    /**
     * Create an instance of this class from a received PDU
     *
     * @param pdu PDU bytes
     * @return An instance of this class, or null if invalid pdu
     */
    public static SmsCbMessage createFromPdu(byte[] pdu) {
        try {
            return new SmsCbMessage(pdu);
        } catch (IllegalArgumentException e) {
            Log.w(LOG_TAG, "Failed parsing SMS-CB pdu", e);
            return null;
        }
    }

    private static final String LOG_TAG = "SMSCB";

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

    private SmsCbHeader mHeader;

    private String mLanguage;

    private String mBody;

    /** Timestamp of ETWS primary notification with security. */
    private long mPrimaryNotificationTimestamp;

    /** 43 byte digital signature of ETWS primary notification with security. */
    private byte[] mPrimaryNotificationDigitalSignature;

    private SmsCbMessage(byte[] pdu) throws IllegalArgumentException {
        mHeader = new SmsCbHeader(pdu);
        if (mHeader.format == SmsCbHeader.FORMAT_ETWS_PRIMARY) {
            mBody = "ETWS";
            // ETWS primary notification with security is 56 octets in length
            if (pdu.length >= SmsCbHeader.PDU_LENGTH_ETWS) {
                mPrimaryNotificationTimestamp = getTimestampMillis(pdu);
                mPrimaryNotificationDigitalSignature = new byte[43];
                // digital signature starts after 6 byte header and 7 byte timestamp
                System.arraycopy(pdu, 13, mPrimaryNotificationDigitalSignature, 0, 43);
            }
        } else {
            parseBody(pdu);
        }
    }

    /**
     * Return the geographical scope of this message, one of
     * {@link #GEOGRAPHICAL_SCOPE_CELL_WIDE_IMMEDIATE},
     * {@link #GEOGRAPHICAL_SCOPE_PLMN_WIDE},
     * {@link #GEOGRAPHICAL_SCOPE_LA_WIDE},
     * {@link #GEOGRAPHICAL_SCOPE_CELL_WIDE}
     *
     * @return Geographical scope
     */
    public int getGeographicalScope() {
        return mHeader.geographicalScope;
    }

    /**
     * Get the ISO-639-1 language code for this message, or null if unspecified
     *
     * @return Language code
     */
    public String getLanguageCode() {
        return mLanguage;
    }

    /**
     * Get the body of this message, or null if no body available
     *
     * @return Body, or null
     */
    public String getMessageBody() {
        return mBody;
    }

    /**
     * Get the message identifier of this message (0-65535)
     *
     * @return Message identifier
     */
    public int getMessageIdentifier() {
        return mHeader.messageIdentifier;
    }

    /**
     * Get the message code of this message (0-1023)
     *
     * @return Message code
     */
    public int getMessageCode() {
        return mHeader.messageCode;
    }

    /**
     * Get the update number of this message (0-15)
     *
     * @return Update number
     */
    public int getUpdateNumber() {
        return mHeader.updateNumber;
    }

    /**
     * Get the format of this message.
     * @return {@link SmsCbHeader#FORMAT_GSM}, {@link SmsCbHeader#FORMAT_UMTS}, or
     *         {@link SmsCbHeader#FORMAT_ETWS_PRIMARY}
     */
    public int getMessageFormat() {
        return mHeader.format;
    }

    /**
     * For ETWS primary notifications, return the emergency user alert flag.
     * @return true to notify terminal to activate emergency user alert; false otherwise
     */
    public boolean getEtwsEmergencyUserAlert() {
        return mHeader.etwsEmergencyUserAlert;
    }

    /**
     * For ETWS primary notifications, return the popup flag.
     * @return true to notify terminal to activate display popup; false otherwise
     */
    public boolean getEtwsPopup() {
        return mHeader.etwsPopup;
    }

    /**
     * For ETWS primary notifications, return the warning type.
     * @return a value such as {@link SmsCbConstants#ETWS_WARNING_TYPE_EARTHQUAKE}
     */
    public int getEtwsWarningType() {
        return mHeader.etwsWarningType;
    }

    /**
     * For ETWS primary notifications, return the Warning-Security-Information timestamp.
     * @return a timestamp in System.currentTimeMillis() format.
     */
    public long getEtwsSecurityTimestamp() {
        return mPrimaryNotificationTimestamp;
    }

    /**
     * For ETWS primary notifications, return the 43 byte digital signature.
     * @return a byte array containing a copy of the digital signature
     */
    public byte[] getEtwsSecuritySignature() {
        return mPrimaryNotificationDigitalSignature.clone();
    }

    /**
     * Parse and unpack the body text according to the encoding in the DCS.
     * After completing successfully this method will have assigned the body
     * text into mBody, and optionally the language code into mLanguage
     *
     * @param pdu The pdu
     */
    private void parseBody(byte[] pdu) {
        int encoding;
        boolean hasLanguageIndicator = false;

        // Extract encoding and language from DCS, as defined in 3gpp TS 23.038,
        // section 5.
        switch ((mHeader.dataCodingScheme & 0xf0) >> 4) {
            case 0x00:
                encoding = SmsMessage.ENCODING_7BIT;
                mLanguage = LANGUAGE_CODES_GROUP_0[mHeader.dataCodingScheme & 0x0f];
                break;

            case 0x01:
                hasLanguageIndicator = true;
                if ((mHeader.dataCodingScheme & 0x0f) == 0x01) {
                    encoding = SmsMessage.ENCODING_16BIT;
                } else {
                    encoding = SmsMessage.ENCODING_7BIT;
                }
                break;

            case 0x02:
                encoding = SmsMessage.ENCODING_7BIT;
                mLanguage = LANGUAGE_CODES_GROUP_2[mHeader.dataCodingScheme & 0x0f];
                break;

            case 0x03:
                encoding = SmsMessage.ENCODING_7BIT;
                break;

            case 0x04:
            case 0x05:
                switch ((mHeader.dataCodingScheme & 0x0c) >> 2) {
                    case 0x01:
                        encoding = SmsMessage.ENCODING_8BIT;
                        break;

                    case 0x02:
                        encoding = SmsMessage.ENCODING_16BIT;
                        break;

                    case 0x00:
                    default:
                        encoding = SmsMessage.ENCODING_7BIT;
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
                encoding = SmsMessage.ENCODING_UNKNOWN;
                break;

            case 0x0f:
                if (((mHeader.dataCodingScheme & 0x04) >> 2) == 0x01) {
                    encoding = SmsMessage.ENCODING_8BIT;
                } else {
                    encoding = SmsMessage.ENCODING_7BIT;
                }
                break;

            default:
                // Reserved values are to be treated as 7-bit
                encoding = SmsMessage.ENCODING_7BIT;
                break;
        }

        if (mHeader.format == SmsCbHeader.FORMAT_UMTS) {
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

                sb.append(unpackBody(pdu, encoding, offset, length, hasLanguageIndicator));
            }
            mBody = sb.toString();
        } else {
            // Payload is one single page
            int offset = SmsCbHeader.PDU_HEADER_LENGTH;
            int length = pdu.length - offset;

            mBody = unpackBody(pdu, encoding, offset, length, hasLanguageIndicator);
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
     * @return Body text
     */
    private String unpackBody(byte[] pdu, int encoding, int offset, int length,
            boolean hasLanguageIndicator) {
        String body = null;

        switch (encoding) {
            case SmsMessage.ENCODING_7BIT:
                body = GsmAlphabet.gsm7BitPackedToString(pdu, offset, length * 8 / 7);

                if (hasLanguageIndicator && body != null && body.length() > 2) {
                    // Language is two GSM characters followed by a CR.
                    // The actual body text is offset by 3 characters.
                    mLanguage = body.substring(0, 2);
                    body = body.substring(3);
                }
                break;

            case SmsMessage.ENCODING_16BIT:
                if (hasLanguageIndicator && pdu.length >= offset + 2) {
                    // Language is two GSM characters.
                    // The actual body text is offset by 2 bytes.
                    mLanguage = GsmAlphabet.gsm7BitPackedToString(pdu, offset, 2);
                    offset += 2;
                    length -= 2;
                }

                try {
                    body = new String(pdu, offset, (length & 0xfffe), "utf-16");
                } catch (UnsupportedEncodingException e) {
                    // Eeeek
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

        return body;
    }

    /**
     * Parses an ETWS primary notification timestamp and returns a currentTimeMillis()-style
     * timestamp. Copied from com.android.internal.telephony.gsm.SmsMessage.
     * @param pdu the ETWS primary notification PDU to decode
     * @return the UTC timestamp from the Warning-Security-Information parameter
     */
    private long getTimestampMillis(byte[] pdu) {
        // Timestamp starts after CB header, in pdu[6]
        int year = IccUtils.gsmBcdByteToInt(pdu[6]);
        int month = IccUtils.gsmBcdByteToInt(pdu[7]);
        int day = IccUtils.gsmBcdByteToInt(pdu[8]);
        int hour = IccUtils.gsmBcdByteToInt(pdu[9]);
        int minute = IccUtils.gsmBcdByteToInt(pdu[10]);
        int second = IccUtils.gsmBcdByteToInt(pdu[11]);

        // For the timezone, the most significant bit of the
        // least significant nibble is the sign byte
        // (meaning the max range of this field is 79 quarter-hours,
        // which is more than enough)

        byte tzByte = pdu[12];

        // Mask out sign bit.
        int timezoneOffset = IccUtils.gsmBcdByteToInt((byte) (tzByte & (~0x08)));

        timezoneOffset = ((tzByte & 0x08) == 0) ? timezoneOffset : -timezoneOffset;

        Time time = new Time(Time.TIMEZONE_UTC);

        // It's 2006.  Should I really support years < 2000?
        time.year = year >= 90 ? year + 1900 : year + 2000;
        time.month = month - 1;
        time.monthDay = day;
        time.hour = hour;
        time.minute = minute;
        time.second = second;

        // Timezone offset is in quarter hours.
        return time.toMillis(true) - (timezoneOffset * 15 * 60 * 1000);
    }

    /**
     * Append text to the message body. This is used to concatenate multi-page GSM broadcasts.
     * @param body the text to append to this message
     */
    public void appendToBody(String body) {
        mBody = mBody + body;
    }

    @Override
    public String toString() {
        return "SmsCbMessage{" + mHeader.toString() + ", language=" + mLanguage +
                ", body=\"" + mBody + "\"}";
    }
}
