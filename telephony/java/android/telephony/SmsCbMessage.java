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

import com.android.internal.telephony.GsmAlphabet;
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
            return null;
        }
    }

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

    private SmsCbHeader mHeader;

    private String mLanguage;

    private String mBody;

    private SmsCbMessage(byte[] pdu) throws IllegalArgumentException {
        mHeader = new SmsCbHeader(pdu);
        parseBody(pdu);
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

        switch (encoding) {
            case SmsMessage.ENCODING_7BIT:
                mBody = GsmAlphabet.gsm7BitPackedToString(pdu, SmsCbHeader.PDU_HEADER_LENGTH,
                        (pdu.length - SmsCbHeader.PDU_HEADER_LENGTH) * 8 / 7);

                if (hasLanguageIndicator && mBody != null && mBody.length() > 2) {
                    mLanguage = mBody.substring(0, 2);
                    mBody = mBody.substring(3);
                }
                break;

            case SmsMessage.ENCODING_16BIT:
                int offset = SmsCbHeader.PDU_HEADER_LENGTH;

                if (hasLanguageIndicator && pdu.length >= SmsCbHeader.PDU_HEADER_LENGTH + 2) {
                    mLanguage = GsmAlphabet.gsm7BitPackedToString(pdu,
                            SmsCbHeader.PDU_HEADER_LENGTH, 2);
                    offset += 2;
                }

                try {
                    mBody = new String(pdu, offset, (pdu.length & 0xfffe) - offset, "utf-16");
                } catch (UnsupportedEncodingException e) {
                    // Eeeek
                }
                break;

            default:
                break;
        }

        if (mBody != null) {
            // Remove trailing carriage return
            for (int i = mBody.length() - 1; i >= 0; i--) {
                if (mBody.charAt(i) != CARRIAGE_RETURN) {
                    mBody = mBody.substring(0, i + 1);
                    break;
                }
            }
        } else {
            mBody = "";
        }
    }
}
