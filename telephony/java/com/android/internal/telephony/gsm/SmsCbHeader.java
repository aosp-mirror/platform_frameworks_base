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

package com.android.internal.telephony.gsm;

import android.compat.annotation.UnsupportedAppUsage;
import android.telephony.SmsCbCmasInfo;
import android.telephony.SmsCbEtwsInfo;

import com.android.internal.telephony.SmsConstants;

import java.util.Arrays;
import java.util.Locale;

/**
 * Parses a 3GPP TS 23.041 cell broadcast message header. This class is public for use by
 * CellBroadcastReceiver test cases, but should not be used by applications.
 *
 * All relevant header information is now sent as a Parcelable
 * {@link android.telephony.SmsCbMessage} object in the "message" extra of the
 * {@link android.provider.Telephony.Sms.Intents#SMS_CB_RECEIVED_ACTION} or
 * {@link android.provider.Telephony.Sms.Intents#ACTION_SMS_EMERGENCY_CB_RECEIVED} intent.
 * The raw PDU is no longer sent to SMS CB applications.
 */
public class SmsCbHeader {
    /**
     * Languages in the 0000xxxx DCS group as defined in 3GPP TS 23.038, section 5.
     */
    private static final String[] LANGUAGE_CODES_GROUP_0 = {
            Locale.GERMAN.getLanguage(),        // German
            Locale.ENGLISH.getLanguage(),       // English
            Locale.ITALIAN.getLanguage(),       // Italian
            Locale.FRENCH.getLanguage(),        // French
            new Locale("es").getLanguage(),     // Spanish
            new Locale("nl").getLanguage(),     // Dutch
            new Locale("sv").getLanguage(),     // Swedish
            new Locale("da").getLanguage(),     // Danish
            new Locale("pt").getLanguage(),     // Portuguese
            new Locale("fi").getLanguage(),     // Finnish
            new Locale("nb").getLanguage(),     // Norwegian
            new Locale("el").getLanguage(),     // Greek
            new Locale("tr").getLanguage(),     // Turkish
            new Locale("hu").getLanguage(),     // Hungarian
            new Locale("pl").getLanguage(),     // Polish
            null
    };

    /**
     * Languages in the 0010xxxx DCS group as defined in 3GPP TS 23.038, section 5.
     */
    private static final String[] LANGUAGE_CODES_GROUP_2 = {
            new Locale("cs").getLanguage(),     // Czech
            new Locale("he").getLanguage(),     // Hebrew
            new Locale("ar").getLanguage(),     // Arabic
            new Locale("ru").getLanguage(),     // Russian
            new Locale("is").getLanguage(),     // Icelandic
            null, null, null, null, null, null, null, null, null, null, null
    };

    /**
     * Length of SMS-CB header
     */
    public static final int PDU_HEADER_LENGTH = 6;

    /**
     * GSM pdu format, as defined in 3gpp TS 23.041, section 9.4.1
     */
    public static final int FORMAT_GSM = 1;

    /**
     * UMTS pdu format, as defined in 3gpp TS 23.041, section 9.4.2
     */
    public static final int FORMAT_UMTS = 2;

    /**
     * ETWS pdu format, as defined in 3gpp TS 23.041, section 9.4.1.3
     */
    public static final int FORMAT_ETWS_PRIMARY = 3;

    /**
     * Message type value as defined in 3gpp TS 25.324, section 11.1.
     */
    private static final int MESSAGE_TYPE_CBS_MESSAGE = 1;

    /**
     * Length of GSM pdus
     */
    private static final int PDU_LENGTH_GSM = 88;

    /**
     * Maximum length of ETWS primary message GSM pdus
     */
    private static final int PDU_LENGTH_ETWS = 56;

    private final int mGeographicalScope;

    /** The serial number combines geographical scope, message code, and update number. */
    private final int mSerialNumber;

    /** The Message Identifier in 3GPP is the same as the Service Category in CDMA. */
    @UnsupportedAppUsage
    private final int mMessageIdentifier;

    private final int mDataCodingScheme;

    private final int mPageIndex;

    private final int mNrOfPages;

    private final int mFormat;

    private DataCodingScheme mDataCodingSchemeStructedData;

    /** ETWS warning notification info. */
    private final SmsCbEtwsInfo mEtwsInfo;

    /** CMAS warning notification info. */
    private final SmsCbCmasInfo mCmasInfo;

    @UnsupportedAppUsage
    public SmsCbHeader(byte[] pdu) throws IllegalArgumentException {
        if (pdu == null || pdu.length < PDU_HEADER_LENGTH) {
            throw new IllegalArgumentException("Illegal PDU");
        }

        if (pdu.length <= PDU_LENGTH_GSM) {
            // can be ETWS or GSM format.
            // Per TS23.041 9.4.1.2 and 9.4.1.3.2, GSM and ETWS format both
            // contain serial number which contains GS, Message Code, and Update Number
            // per 9.4.1.2.1, and message identifier in same octets
            mGeographicalScope = (pdu[0] & 0xc0) >>> 6;
            mSerialNumber = ((pdu[0] & 0xff) << 8) | (pdu[1] & 0xff);
            mMessageIdentifier = ((pdu[2] & 0xff) << 8) | (pdu[3] & 0xff);
            if (isEtwsMessage() && pdu.length <= PDU_LENGTH_ETWS) {
                mFormat = FORMAT_ETWS_PRIMARY;
                mDataCodingScheme = -1;
                mPageIndex = -1;
                mNrOfPages = -1;
                boolean emergencyUserAlert = (pdu[4] & 0x1) != 0;
                boolean activatePopup = (pdu[5] & 0x80) != 0;
                int warningType = (pdu[4] & 0xfe) >>> 1;
                byte[] warningSecurityInfo;
                // copy the Warning-Security-Information, if present
                if (pdu.length > PDU_HEADER_LENGTH) {
                    warningSecurityInfo = Arrays.copyOfRange(pdu, 6, pdu.length);
                } else {
                    warningSecurityInfo = null;
                }
                mEtwsInfo = new SmsCbEtwsInfo(warningType, emergencyUserAlert, activatePopup,
                        true, warningSecurityInfo);
                mCmasInfo = null;
                return;     // skip the ETWS/CMAS initialization code for regular notifications
            } else {
                // GSM pdus are no more than 88 bytes
                mFormat = FORMAT_GSM;
                mDataCodingScheme = pdu[4] & 0xff;

                // Check for invalid page parameter
                int pageIndex = (pdu[5] & 0xf0) >>> 4;
                int nrOfPages = pdu[5] & 0x0f;

                if (pageIndex == 0 || nrOfPages == 0 || pageIndex > nrOfPages) {
                    pageIndex = 1;
                    nrOfPages = 1;
                }

                mPageIndex = pageIndex;
                mNrOfPages = nrOfPages;
            }
        } else {
            // UMTS pdus are always at least 90 bytes since the payload includes
            // a number-of-pages octet and also one length octet per page
            mFormat = FORMAT_UMTS;

            int messageType = pdu[0];

            if (messageType != MESSAGE_TYPE_CBS_MESSAGE) {
                throw new IllegalArgumentException("Unsupported message type " + messageType);
            }

            mMessageIdentifier = ((pdu[1] & 0xff) << 8) | pdu[2] & 0xff;
            mGeographicalScope = (pdu[3] & 0xc0) >>> 6;
            mSerialNumber = ((pdu[3] & 0xff) << 8) | (pdu[4] & 0xff);
            mDataCodingScheme = pdu[5] & 0xff;

            // We will always consider a UMTS message as having one single page
            // since there's only one instance of the header, even though the
            // actual payload may contain several pages.
            mPageIndex = 1;
            mNrOfPages = 1;
        }

        if (mDataCodingScheme != -1) {
            mDataCodingSchemeStructedData = new DataCodingScheme(mDataCodingScheme);
        }

        if (isEtwsMessage()) {
            boolean emergencyUserAlert = isEtwsEmergencyUserAlert();
            boolean activatePopup = isEtwsPopupAlert();
            int warningType = getEtwsWarningType();
            mEtwsInfo = new SmsCbEtwsInfo(warningType, emergencyUserAlert, activatePopup,
                    false, null);
            mCmasInfo = null;
        } else if (isCmasMessage()) {
            int messageClass = getCmasMessageClass();
            int severity = getCmasSeverity();
            int urgency = getCmasUrgency();
            int certainty = getCmasCertainty();
            mEtwsInfo = null;
            mCmasInfo = new SmsCbCmasInfo(messageClass, SmsCbCmasInfo.CMAS_CATEGORY_UNKNOWN,
                    SmsCbCmasInfo.CMAS_RESPONSE_TYPE_UNKNOWN, severity, urgency, certainty);
        } else {
            mEtwsInfo = null;
            mCmasInfo = null;
        }
    }

    @UnsupportedAppUsage
    public int getGeographicalScope() {
        return mGeographicalScope;
    }

    @UnsupportedAppUsage
    public int getSerialNumber() {
        return mSerialNumber;
    }

    @UnsupportedAppUsage
    public int getServiceCategory() {
        return mMessageIdentifier;
    }

    public int getDataCodingScheme() {
        return mDataCodingScheme;
    }

    public DataCodingScheme getDataCodingSchemeStructedData() {
        return mDataCodingSchemeStructedData;
    }

    @UnsupportedAppUsage
    public int getPageIndex() {
        return mPageIndex;
    }

    @UnsupportedAppUsage
    public int getNumberOfPages() {
        return mNrOfPages;
    }

    public SmsCbEtwsInfo getEtwsInfo() {
        return mEtwsInfo;
    }

    public SmsCbCmasInfo getCmasInfo() {
        return mCmasInfo;
    }

    /**
     * Return whether this broadcast is an emergency (PWS) message type.
     * @return true if this message is emergency type; false otherwise
     */
    public boolean isEmergencyMessage() {
        return mMessageIdentifier >= SmsCbConstants.MESSAGE_ID_PWS_FIRST_IDENTIFIER
                && mMessageIdentifier <= SmsCbConstants.MESSAGE_ID_PWS_LAST_IDENTIFIER;
    }

    /**
     * Return whether this broadcast is an ETWS emergency message type.
     * @return true if this message is ETWS emergency type; false otherwise
     */
    private boolean isEtwsMessage() {
        return (mMessageIdentifier & SmsCbConstants.MESSAGE_ID_ETWS_TYPE_MASK)
                == SmsCbConstants.MESSAGE_ID_ETWS_TYPE;
    }

    /**
     * Return whether this broadcast is an ETWS primary notification.
     * @return true if this message is an ETWS primary notification; false otherwise
     */
    public boolean isEtwsPrimaryNotification() {
        return mFormat == FORMAT_ETWS_PRIMARY;
    }

    /**
     * Return whether this broadcast is in UMTS format.
     * @return true if this message is in UMTS format; false otherwise
     */
    public boolean isUmtsFormat() {
        return mFormat == FORMAT_UMTS;
    }

    /**
     * Return whether this message is a CMAS emergency message type.
     * @return true if this message is CMAS emergency type; false otherwise
     */
    private boolean isCmasMessage() {
        return mMessageIdentifier >= SmsCbConstants.MESSAGE_ID_CMAS_FIRST_IDENTIFIER
                && mMessageIdentifier <= SmsCbConstants.MESSAGE_ID_CMAS_LAST_IDENTIFIER;
    }

    /**
     * Return whether the popup alert flag is set for an ETWS warning notification.
     * This method assumes that the message ID has already been checked for ETWS type.
     *
     * @return true if the message code indicates a popup alert should be displayed
     */
    private boolean isEtwsPopupAlert() {
        return (mSerialNumber & SmsCbConstants.SERIAL_NUMBER_ETWS_ACTIVATE_POPUP) != 0;
    }

    /**
     * Return whether the emergency user alert flag is set for an ETWS warning notification.
     * This method assumes that the message ID has already been checked for ETWS type.
     *
     * @return true if the message code indicates an emergency user alert
     */
    private boolean isEtwsEmergencyUserAlert() {
        return (mSerialNumber & SmsCbConstants.SERIAL_NUMBER_ETWS_EMERGENCY_USER_ALERT) != 0;
    }

    /**
     * Returns the warning type for an ETWS warning notification.
     * This method assumes that the message ID has already been checked for ETWS type.
     *
     * @return the ETWS warning type defined in 3GPP TS 23.041 section 9.3.24
     */
    private int getEtwsWarningType() {
        return mMessageIdentifier - SmsCbConstants.MESSAGE_ID_ETWS_EARTHQUAKE_WARNING;
    }

    /**
     * Returns the message class for a CMAS warning notification.
     * This method assumes that the message ID has already been checked for CMAS type.
     * @return the CMAS message class as defined in {@link SmsCbCmasInfo}
     */
    private int getCmasMessageClass() {
        switch (mMessageIdentifier) {
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_PRESIDENTIAL_LEVEL_LANGUAGE:
                return SmsCbCmasInfo.CMAS_CLASS_PRESIDENTIAL_LEVEL_ALERT;

            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY_LANGUAGE:
                return SmsCbCmasInfo.CMAS_CLASS_EXTREME_THREAT;

            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY_LANGUAGE:
                return SmsCbCmasInfo.CMAS_CLASS_SEVERE_THREAT;

            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_CHILD_ABDUCTION_EMERGENCY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_CHILD_ABDUCTION_EMERGENCY_LANGUAGE:
                return SmsCbCmasInfo.CMAS_CLASS_CHILD_ABDUCTION_EMERGENCY;

            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_REQUIRED_MONTHLY_TEST:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_REQUIRED_MONTHLY_TEST_LANGUAGE:
                return SmsCbCmasInfo.CMAS_CLASS_REQUIRED_MONTHLY_TEST;

            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXERCISE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXERCISE_LANGUAGE:
                return SmsCbCmasInfo.CMAS_CLASS_CMAS_EXERCISE;

            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_OPERATOR_DEFINED_USE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_OPERATOR_DEFINED_USE_LANGUAGE:
                return SmsCbCmasInfo.CMAS_CLASS_OPERATOR_DEFINED_USE;

            default:
                return SmsCbCmasInfo.CMAS_CLASS_UNKNOWN;
        }
    }

    /**
     * Returns the severity for a CMAS warning notification. This is only available for extreme
     * and severe alerts, not for other types such as Presidential Level and AMBER alerts.
     * This method assumes that the message ID has already been checked for CMAS type.
     * @return the CMAS severity as defined in {@link SmsCbCmasInfo}
     */
    private int getCmasSeverity() {
        switch (mMessageIdentifier) {
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY_LANGUAGE:
                return SmsCbCmasInfo.CMAS_SEVERITY_EXTREME;

            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY_LANGUAGE:
                return SmsCbCmasInfo.CMAS_SEVERITY_SEVERE;

            default:
                return SmsCbCmasInfo.CMAS_SEVERITY_UNKNOWN;
        }
    }

    /**
     * Returns the urgency for a CMAS warning notification. This is only available for extreme
     * and severe alerts, not for other types such as Presidential Level and AMBER alerts.
     * This method assumes that the message ID has already been checked for CMAS type.
     * @return the CMAS urgency as defined in {@link SmsCbCmasInfo}
     */
    private int getCmasUrgency() {
        switch (mMessageIdentifier) {
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY_LANGUAGE:
                return SmsCbCmasInfo.CMAS_URGENCY_IMMEDIATE;

            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY_LANGUAGE:
                return SmsCbCmasInfo.CMAS_URGENCY_EXPECTED;

            default:
                return SmsCbCmasInfo.CMAS_URGENCY_UNKNOWN;
        }
    }

    /**
     * Returns the certainty for a CMAS warning notification. This is only available for extreme
     * and severe alerts, not for other types such as Presidential Level and AMBER alerts.
     * This method assumes that the message ID has already been checked for CMAS type.
     * @return the CMAS certainty as defined in {@link SmsCbCmasInfo}
     */
    private int getCmasCertainty() {
        switch (mMessageIdentifier) {
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_OBSERVED_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_OBSERVED_LANGUAGE:
                return SmsCbCmasInfo.CMAS_CERTAINTY_OBSERVED;

            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_IMMEDIATE_LIKELY_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_EXTREME_EXPECTED_LIKELY_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_IMMEDIATE_LIKELY_LANGUAGE:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY:
            case SmsCbConstants.MESSAGE_ID_CMAS_ALERT_SEVERE_EXPECTED_LIKELY_LANGUAGE:
                return SmsCbCmasInfo.CMAS_CERTAINTY_LIKELY;

            default:
                return SmsCbCmasInfo.CMAS_CERTAINTY_UNKNOWN;
        }
    }

    @Override
    public String toString() {
        return "SmsCbHeader{GS=" + mGeographicalScope + ", serialNumber=0x"
                + Integer.toHexString(mSerialNumber)
                + ", messageIdentifier=0x" + Integer.toHexString(mMessageIdentifier)
                + ", format=" + mFormat
                + ", DCS=0x" + Integer.toHexString(mDataCodingScheme)
                + ", page " + mPageIndex + " of " + mNrOfPages + '}';
    }

    /**
     * CBS Data Coding Scheme.
     * Reference: 3GPP TS 23.038 version 15.0.0 section #5, CBS Data Coding Scheme
     */
    public static final class DataCodingScheme {
        public final int encoding;
        public final String language;
        public final boolean hasLanguageIndicator;

        public DataCodingScheme(int dataCodingScheme) {
            int encoding = 0;
            String language = null;
            boolean hasLanguageIndicator = false;

            // Extract encoding and language from DCS, as defined in 3gpp TS 23.038,
            // section 5.
            switch ((dataCodingScheme & 0xf0) >> 4) {
                case 0x00:
                    encoding = SmsConstants.ENCODING_7BIT;
                    language = LANGUAGE_CODES_GROUP_0[dataCodingScheme & 0x0f];
                    break;

                case 0x01:
                    hasLanguageIndicator = true;
                    if ((dataCodingScheme & 0x0f) == 0x01) {
                        encoding = SmsConstants.ENCODING_16BIT;
                    } else {
                        encoding = SmsConstants.ENCODING_7BIT;
                    }
                    break;

                case 0x02:
                    encoding = SmsConstants.ENCODING_7BIT;
                    language = LANGUAGE_CODES_GROUP_2[dataCodingScheme & 0x0f];
                    break;

                case 0x03:
                    encoding = SmsConstants.ENCODING_7BIT;
                    break;

                case 0x04:
                case 0x05:
                    switch ((dataCodingScheme & 0x0c) >> 2) {
                        case 0x01:
                            encoding = SmsConstants.ENCODING_8BIT;
                            break;

                        case 0x02:
                            encoding = SmsConstants.ENCODING_16BIT;
                            break;

                        case 0x00:
                        default:
                            encoding = SmsConstants.ENCODING_7BIT;
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
                        encoding = SmsConstants.ENCODING_8BIT;
                    } else {
                        encoding = SmsConstants.ENCODING_7BIT;
                    }
                    break;

                default:
                    // Reserved values are to be treated as 7-bit
                    encoding = SmsConstants.ENCODING_7BIT;
                    break;
            }


            this.encoding = encoding;
            this.language = language;
            this.hasLanguageIndicator = hasLanguageIndicator;
        }
    }
}
