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

import android.content.res.Resources;
import android.telephony.SmsCbCmasInfo;
import android.telephony.cdma.CdmaSmsCbProgramData;
import android.telephony.cdma.CdmaSmsCbProgramResults;
import android.text.format.Time;
import android.telephony.Rlog;

import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.GsmAlphabet.TextEncodingDetails;
import com.android.internal.telephony.SmsConstants;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsMessageBase;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.util.BitwiseInputStream;
import com.android.internal.util.BitwiseOutputStream;

import java.util.ArrayList;
import java.util.TimeZone;

/**
 * An object to encode and decode CDMA SMS bearer data.
 */
public final class BearerData {
    private final static String LOG_TAG = "BearerData";

    /**
     * Bearer Data Subparameter Identifiers
     * (See 3GPP2 C.S0015-B, v2.0, table 4.5-1)
     * NOTE: Commented subparameter types are not implemented.
     */
    private final static byte SUBPARAM_MESSAGE_IDENTIFIER               = 0x00;
    private final static byte SUBPARAM_USER_DATA                        = 0x01;
    private final static byte SUBPARAM_USER_RESPONSE_CODE               = 0x02;
    private final static byte SUBPARAM_MESSAGE_CENTER_TIME_STAMP        = 0x03;
    private final static byte SUBPARAM_VALIDITY_PERIOD_ABSOLUTE         = 0x04;
    private final static byte SUBPARAM_VALIDITY_PERIOD_RELATIVE         = 0x05;
    private final static byte SUBPARAM_DEFERRED_DELIVERY_TIME_ABSOLUTE  = 0x06;
    private final static byte SUBPARAM_DEFERRED_DELIVERY_TIME_RELATIVE  = 0x07;
    private final static byte SUBPARAM_PRIORITY_INDICATOR               = 0x08;
    private final static byte SUBPARAM_PRIVACY_INDICATOR                = 0x09;
    private final static byte SUBPARAM_REPLY_OPTION                     = 0x0A;
    private final static byte SUBPARAM_NUMBER_OF_MESSAGES               = 0x0B;
    private final static byte SUBPARAM_ALERT_ON_MESSAGE_DELIVERY        = 0x0C;
    private final static byte SUBPARAM_LANGUAGE_INDICATOR               = 0x0D;
    private final static byte SUBPARAM_CALLBACK_NUMBER                  = 0x0E;
    private final static byte SUBPARAM_MESSAGE_DISPLAY_MODE             = 0x0F;
    //private final static byte SUBPARAM_MULTIPLE_ENCODING_USER_DATA      = 0x10;
    private final static byte SUBPARAM_MESSAGE_DEPOSIT_INDEX            = 0x11;
    private final static byte SUBPARAM_SERVICE_CATEGORY_PROGRAM_DATA    = 0x12;
    private final static byte SUBPARAM_SERVICE_CATEGORY_PROGRAM_RESULTS = 0x13;
    private final static byte SUBPARAM_MESSAGE_STATUS                   = 0x14;
    //private final static byte SUBPARAM_TP_FAILURE_CAUSE                 = 0x15;
    //private final static byte SUBPARAM_ENHANCED_VMN                     = 0x16;
    //private final static byte SUBPARAM_ENHANCED_VMN_ACK                 = 0x17;

    // All other values after this are reserved.
    private final static byte SUBPARAM_ID_LAST_DEFINED                    = 0x17;

    /**
     * Supported message types for CDMA SMS messages
     * (See 3GPP2 C.S0015-B, v2.0, table 4.5.1-1)
     */
    public static final int MESSAGE_TYPE_DELIVER        = 0x01;
    public static final int MESSAGE_TYPE_SUBMIT         = 0x02;
    public static final int MESSAGE_TYPE_CANCELLATION   = 0x03;
    public static final int MESSAGE_TYPE_DELIVERY_ACK   = 0x04;
    public static final int MESSAGE_TYPE_USER_ACK       = 0x05;
    public static final int MESSAGE_TYPE_READ_ACK       = 0x06;
    public static final int MESSAGE_TYPE_DELIVER_REPORT = 0x07;
    public static final int MESSAGE_TYPE_SUBMIT_REPORT  = 0x08;

    public int messageType;

    /**
     * 16-bit value indicating the message ID, which increments modulo 65536.
     * (Special rules apply for WAP-messages.)
     * (See 3GPP2 C.S0015-B, v2, 4.5.1)
     */
    public int messageId;

    /**
     * Supported priority modes for CDMA SMS messages
     * (See 3GPP2 C.S0015-B, v2.0, table 4.5.9-1)
     */
    public static final int PRIORITY_NORMAL        = 0x0;
    public static final int PRIORITY_INTERACTIVE   = 0x1;
    public static final int PRIORITY_URGENT        = 0x2;
    public static final int PRIORITY_EMERGENCY     = 0x3;

    public boolean priorityIndicatorSet = false;
    public int priority = PRIORITY_NORMAL;

    /**
     * Supported privacy modes for CDMA SMS messages
     * (See 3GPP2 C.S0015-B, v2.0, table 4.5.10-1)
     */
    public static final int PRIVACY_NOT_RESTRICTED = 0x0;
    public static final int PRIVACY_RESTRICTED     = 0x1;
    public static final int PRIVACY_CONFIDENTIAL   = 0x2;
    public static final int PRIVACY_SECRET         = 0x3;

    public boolean privacyIndicatorSet = false;
    public int privacy = PRIVACY_NOT_RESTRICTED;

    /**
     * Supported alert priority modes for CDMA SMS messages
     * (See 3GPP2 C.S0015-B, v2.0, table 4.5.13-1)
     */
    public static final int ALERT_DEFAULT          = 0x0;
    public static final int ALERT_LOW_PRIO         = 0x1;
    public static final int ALERT_MEDIUM_PRIO      = 0x2;
    public static final int ALERT_HIGH_PRIO        = 0x3;

    public boolean alertIndicatorSet = false;
    public int alert = ALERT_DEFAULT;

    /**
     * Supported display modes for CDMA SMS messages.  Display mode is
     * a 2-bit value used to indicate to the mobile station when to
     * display the received message.  (See 3GPP2 C.S0015-B, v2,
     * 4.5.16)
     */
    public static final int DISPLAY_MODE_IMMEDIATE      = 0x0;
    public static final int DISPLAY_MODE_DEFAULT        = 0x1;
    public static final int DISPLAY_MODE_USER           = 0x2;

    public boolean displayModeSet = false;
    public int displayMode = DISPLAY_MODE_DEFAULT;

    /**
     * Language Indicator values.  NOTE: the spec (3GPP2 C.S0015-B,
     * v2, 4.5.14) is ambiguous as to the meaning of this field, as it
     * refers to C.R1001-D but that reference has been crossed out.
     * It would seem reasonable to assume the values from C.R1001-F
     * (table 9.2-1) are to be used instead.
     */
    public static final int LANGUAGE_UNKNOWN  = 0x00;
    public static final int LANGUAGE_ENGLISH  = 0x01;
    public static final int LANGUAGE_FRENCH   = 0x02;
    public static final int LANGUAGE_SPANISH  = 0x03;
    public static final int LANGUAGE_JAPANESE = 0x04;
    public static final int LANGUAGE_KOREAN   = 0x05;
    public static final int LANGUAGE_CHINESE  = 0x06;
    public static final int LANGUAGE_HEBREW   = 0x07;

    public boolean languageIndicatorSet = false;
    public int language = LANGUAGE_UNKNOWN;

    /**
     * SMS Message Status Codes.  The first component of the Message
     * status indicates if an error has occurred and whether the error
     * is considered permanent or temporary.  The second component of
     * the Message status indicates the cause of the error (if any).
     * (See 3GPP2 C.S0015-B, v2.0, 4.5.21)
     */
    /* no-error codes */
    public static final int ERROR_NONE                   = 0x00;
    public static final int STATUS_ACCEPTED              = 0x00;
    public static final int STATUS_DEPOSITED_TO_INTERNET = 0x01;
    public static final int STATUS_DELIVERED             = 0x02;
    public static final int STATUS_CANCELLED             = 0x03;
    /* temporary-error and permanent-error codes */
    public static final int ERROR_TEMPORARY              = 0x02;
    public static final int STATUS_NETWORK_CONGESTION    = 0x04;
    public static final int STATUS_NETWORK_ERROR         = 0x05;
    public static final int STATUS_UNKNOWN_ERROR         = 0x1F;
    /* permanent-error codes */
    public static final int ERROR_PERMANENT              = 0x03;
    public static final int STATUS_CANCEL_FAILED         = 0x06;
    public static final int STATUS_BLOCKED_DESTINATION   = 0x07;
    public static final int STATUS_TEXT_TOO_LONG         = 0x08;
    public static final int STATUS_DUPLICATE_MESSAGE     = 0x09;
    public static final int STATUS_INVALID_DESTINATION   = 0x0A;
    public static final int STATUS_MESSAGE_EXPIRED       = 0x0D;
    /* undefined-status codes */
    public static final int ERROR_UNDEFINED              = 0xFF;
    public static final int STATUS_UNDEFINED             = 0xFF;

    public boolean messageStatusSet = false;
    public int errorClass = ERROR_UNDEFINED;
    public int messageStatus = STATUS_UNDEFINED;

    /**
     * 1-bit value that indicates whether a User Data Header (UDH) is present.
     * (See 3GPP2 C.S0015-B, v2, 4.5.1)
     *
     * NOTE: during encoding, this value will be set based on the
     * presence of a UDH in the structured data, any existing setting
     * will be overwritten.
     */
    public boolean hasUserDataHeader;

    /**
     * provides the information for the user data
     * (e.g. padding bits, user data, user data header, etc)
     * (See 3GPP2 C.S.0015-B, v2, 4.5.2)
     */
    public UserData userData;

    /**
     * The User Response Code subparameter is used in the SMS User
     * Acknowledgment Message to respond to previously received short
     * messages. This message center-specific element carries the
     * identifier of a predefined response. (See 3GPP2 C.S.0015-B, v2,
     * 4.5.3)
     */
    public boolean userResponseCodeSet = false;
    public int userResponseCode;

    /**
     * 6-byte-field, see 3GPP2 C.S0015-B, v2, 4.5.4
     */
    public static class TimeStamp extends Time {

        public TimeStamp() {
            super(TimeZone.getDefault().getID());   // 3GPP2 timestamps use the local timezone
        }

        public static TimeStamp fromByteArray(byte[] data) {
            TimeStamp ts = new TimeStamp();
            // C.S0015-B v2.0, 4.5.4: range is 1996-2095
            int year = IccUtils.cdmaBcdByteToInt(data[0]);
            if (year > 99 || year < 0) return null;
            ts.year = year >= 96 ? year + 1900 : year + 2000;
            int month = IccUtils.cdmaBcdByteToInt(data[1]);
            if (month < 1 || month > 12) return null;
            ts.month = month - 1;
            int day = IccUtils.cdmaBcdByteToInt(data[2]);
            if (day < 1 || day > 31) return null;
            ts.monthDay = day;
            int hour = IccUtils.cdmaBcdByteToInt(data[3]);
            if (hour < 0 || hour > 23) return null;
            ts.hour = hour;
            int minute = IccUtils.cdmaBcdByteToInt(data[4]);
            if (minute < 0 || minute > 59) return null;
            ts.minute = minute;
            int second = IccUtils.cdmaBcdByteToInt(data[5]);
            if (second < 0 || second > 59) return null;
            ts.second = second;
            return ts;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("TimeStamp ");
            builder.append("{ year=" + year);
            builder.append(", month=" + month);
            builder.append(", day=" + monthDay);
            builder.append(", hour=" + hour);
            builder.append(", minute=" + minute);
            builder.append(", second=" + second);
            builder.append(" }");
            return builder.toString();
        }
    }

    public TimeStamp msgCenterTimeStamp;
    public TimeStamp validityPeriodAbsolute;
    public TimeStamp deferredDeliveryTimeAbsolute;

    /**
     * Relative time is specified as one byte, the value of which
     * falls into a series of ranges, as specified below.  The idea is
     * that shorter time intervals allow greater precision -- the
     * value means minutes from zero until the MINS_LIMIT (inclusive),
     * upon which it means hours until the HOURS_LIMIT, and so
     * forth. (See 3GPP2 C.S0015-B, v2, 4.5.6-1)
     */
    public static final int RELATIVE_TIME_MINS_LIMIT      = 143;
    public static final int RELATIVE_TIME_HOURS_LIMIT     = 167;
    public static final int RELATIVE_TIME_DAYS_LIMIT      = 196;
    public static final int RELATIVE_TIME_WEEKS_LIMIT     = 244;
    public static final int RELATIVE_TIME_INDEFINITE      = 245;
    public static final int RELATIVE_TIME_NOW             = 246;
    public static final int RELATIVE_TIME_MOBILE_INACTIVE = 247;
    public static final int RELATIVE_TIME_RESERVED        = 248;

    public boolean validityPeriodRelativeSet;
    public int validityPeriodRelative;
    public boolean deferredDeliveryTimeRelativeSet;
    public int deferredDeliveryTimeRelative;

    /**
     * The Reply Option subparameter contains 1-bit values which
     * indicate whether SMS acknowledgment is requested or not.  (See
     * 3GPP2 C.S0015-B, v2, 4.5.11)
     */
    public boolean userAckReq;
    public boolean deliveryAckReq;
    public boolean readAckReq;
    public boolean reportReq;

    /**
     * The Number of Messages subparameter (8-bit value) is a decimal
     * number in the 0 to 99 range representing the number of messages
     * stored at the Voice Mail System. This element is used by the
     * Voice Mail Notification service.  (See 3GPP2 C.S0015-B, v2,
     * 4.5.12)
     */
    public int numberOfMessages;

    /**
     * The Message Deposit Index subparameter is assigned by the
     * message center as a unique index to the contents of the User
     * Data subparameter in each message sent to a particular mobile
     * station. The mobile station, when replying to a previously
     * received short message which included a Message Deposit Index
     * subparameter, may include the Message Deposit Index of the
     * received message to indicate to the message center that the
     * original contents of the message are to be included in the
     * reply.  (See 3GPP2 C.S0015-B, v2, 4.5.18)
     */
    public int depositIndex;

    /**
     * 4-bit or 8-bit value that indicates the number to be dialed in reply to a
     * received SMS message.
     * (See 3GPP2 C.S0015-B, v2, 4.5.15)
     */
    public CdmaSmsAddress callbackNumber;

    /**
     * CMAS warning notification information.
     * @see #decodeCmasUserData(BearerData, int)
     */
    public SmsCbCmasInfo cmasWarningInfo;

    /**
     * The Service Category Program Data subparameter is used to enable and disable
     * SMS broadcast service categories to display. If this subparameter is present,
     * this field will contain a list of one or more
     * {@link android.telephony.cdma.CdmaSmsCbProgramData} objects containing the
     * operation(s) to perform.
     */
    public ArrayList<CdmaSmsCbProgramData> serviceCategoryProgramData;

    /**
     * The Service Category Program Results subparameter informs the message center
     * of the results of a Service Category Program Data request.
     */
    public ArrayList<CdmaSmsCbProgramResults> serviceCategoryProgramResults;


    private static class CodingException extends Exception {
        public CodingException(String s) {
            super(s);
        }
    }

    /**
     * Returns the language indicator as a two-character ISO 639 string.
     * @return a two character ISO 639 language code
     */
    public String getLanguage() {
        return getLanguageCodeForValue(language);
    }

    /**
     * Converts a CDMA language indicator value to an ISO 639 two character language code.
     * @param languageValue the CDMA language value to convert
     * @return the two character ISO 639 language code for the specified value, or null if unknown
     */
    private static String getLanguageCodeForValue(int languageValue) {
        switch (languageValue) {
            case LANGUAGE_ENGLISH:
                return "en";

            case LANGUAGE_FRENCH:
                return "fr";

            case LANGUAGE_SPANISH:
                return "es";

            case LANGUAGE_JAPANESE:
                return "ja";

            case LANGUAGE_KOREAN:
                return "ko";

            case LANGUAGE_CHINESE:
                return "zh";

            case LANGUAGE_HEBREW:
                return "he";

            default:
                return null;
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("BearerData ");
        builder.append("{ messageType=" + messageType);
        builder.append(", messageId=" + messageId);
        builder.append(", priority=" + (priorityIndicatorSet ? priority : "unset"));
        builder.append(", privacy=" + (privacyIndicatorSet ? privacy : "unset"));
        builder.append(", alert=" + (alertIndicatorSet ? alert : "unset"));
        builder.append(", displayMode=" + (displayModeSet ? displayMode : "unset"));
        builder.append(", language=" + (languageIndicatorSet ? language : "unset"));
        builder.append(", errorClass=" + (messageStatusSet ? errorClass : "unset"));
        builder.append(", msgStatus=" + (messageStatusSet ? messageStatus : "unset"));
        builder.append(", msgCenterTimeStamp=" +
                ((msgCenterTimeStamp != null) ? msgCenterTimeStamp : "unset"));
        builder.append(", validityPeriodAbsolute=" +
                ((validityPeriodAbsolute != null) ? validityPeriodAbsolute : "unset"));
        builder.append(", validityPeriodRelative=" +
                ((validityPeriodRelativeSet) ? validityPeriodRelative : "unset"));
        builder.append(", deferredDeliveryTimeAbsolute=" +
                ((deferredDeliveryTimeAbsolute != null) ? deferredDeliveryTimeAbsolute : "unset"));
        builder.append(", deferredDeliveryTimeRelative=" +
                ((deferredDeliveryTimeRelativeSet) ? deferredDeliveryTimeRelative : "unset"));
        builder.append(", userAckReq=" + userAckReq);
        builder.append(", deliveryAckReq=" + deliveryAckReq);
        builder.append(", readAckReq=" + readAckReq);
        builder.append(", reportReq=" + reportReq);
        builder.append(", numberOfMessages=" + numberOfMessages);
        builder.append(", callbackNumber=" + Rlog.pii(LOG_TAG, callbackNumber));
        builder.append(", depositIndex=" + depositIndex);
        builder.append(", hasUserDataHeader=" + hasUserDataHeader);
        builder.append(", userData=" + userData);
        builder.append(" }");
        return builder.toString();
    }

    private static void encodeMessageId(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException
    {
        outStream.write(8, 3);
        outStream.write(4, bData.messageType);
        outStream.write(8, bData.messageId >> 8);
        outStream.write(8, bData.messageId);
        outStream.write(1, bData.hasUserDataHeader ? 1 : 0);
        outStream.skip(3);
    }

    private static int countAsciiSeptets(CharSequence msg, boolean force) {
        int msgLen = msg.length();
        if (force) return msgLen;
        for (int i = 0; i < msgLen; i++) {
            if (UserData.charToAscii.get(msg.charAt(i), -1) == -1) {
                return -1;
            }
        }
        return msgLen;
    }

    /**
     * Calculate the message text encoding length, fragmentation, and other details.
     *
     * @param msg message text
     * @param force7BitEncoding ignore (but still count) illegal characters if true
     * @param isEntireMsg indicates if this is entire msg or a segment in multipart msg
     * @return septet count, or -1 on failure
     */
    public static TextEncodingDetails calcTextEncodingDetails(CharSequence msg,
            boolean force7BitEncoding, boolean isEntireMsg) {
        TextEncodingDetails ted;
        int septets = countAsciiSeptets(msg, force7BitEncoding);
        if (septets != -1 && septets <= SmsConstants.MAX_USER_DATA_SEPTETS) {
            ted = new TextEncodingDetails();
            ted.msgCount = 1;
            ted.codeUnitCount = septets;
            ted.codeUnitsRemaining = SmsConstants.MAX_USER_DATA_SEPTETS - septets;
            ted.codeUnitSize = SmsConstants.ENCODING_7BIT;
        } else {
            ted = com.android.internal.telephony.gsm.SmsMessage.calculateLength(
                    msg, force7BitEncoding);
            if (ted.msgCount == 1 && ted.codeUnitSize == SmsConstants.ENCODING_7BIT &&
                    isEntireMsg) {
                // We don't support single-segment EMS, so calculate for 16-bit
                // TODO: Consider supporting single-segment EMS
                return SmsMessageBase.calcUnicodeEncodingDetails(msg);
            }
        }
        return ted;
    }

    private static byte[] encode7bitAscii(String msg, boolean force)
        throws CodingException
    {
        try {
            BitwiseOutputStream outStream = new BitwiseOutputStream(msg.length());
            int msgLen = msg.length();
            for (int i = 0; i < msgLen; i++) {
                int charCode = UserData.charToAscii.get(msg.charAt(i), -1);
                if (charCode == -1) {
                    if (force) {
                        outStream.write(7, UserData.UNENCODABLE_7_BIT_CHAR);
                    } else {
                        throw new CodingException("cannot ASCII encode (" + msg.charAt(i) + ")");
                    }
                } else {
                    outStream.write(7, charCode);
                }
            }
            return outStream.toByteArray();
        } catch (BitwiseOutputStream.AccessException ex) {
            throw new CodingException("7bit ASCII encode failed: " + ex);
        }
    }

    private static byte[] encodeUtf16(String msg)
        throws CodingException
    {
        try {
            return msg.getBytes("utf-16be");
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new CodingException("UTF-16 encode failed: " + ex);
        }
    }

    private static class Gsm7bitCodingResult {
        int septets;
        byte[] data;
    }

    private static Gsm7bitCodingResult encode7bitGsm(String msg, int septetOffset, boolean force)
        throws CodingException
    {
        try {
            /*
             * TODO(cleanup): It would be nice if GsmAlphabet provided
             * an option to produce just the data without prepending
             * the septet count, as this function is really just a
             * wrapper to strip that off.  Not to mention that the
             * septet count is generally known prior to invocation of
             * the encoder.  Note that it cannot be derived from the
             * resulting array length, since that cannot distinguish
             * if the last contains either 1 or 8 valid bits.
             *
             * TODO(cleanup): The BitwiseXStreams could also be
             * extended with byte-wise reversed endianness read/write
             * routines to allow a corresponding implementation of
             * stringToGsm7BitPacked, and potentially directly support
             * access to the main bitwise stream from encode/decode.
             */
            byte[] fullData = GsmAlphabet.stringToGsm7BitPacked(msg, septetOffset, !force, 0, 0);
            Gsm7bitCodingResult result = new Gsm7bitCodingResult();
            result.data = new byte[fullData.length - 1];
            System.arraycopy(fullData, 1, result.data, 0, fullData.length - 1);
            result.septets = fullData[0] & 0x00FF;
            return result;
        } catch (com.android.internal.telephony.EncodeException ex) {
            throw new CodingException("7bit GSM encode failed: " + ex);
        }
    }

    private static void encode7bitEms(UserData uData, byte[] udhData, boolean force)
        throws CodingException
    {
        int udhBytes = udhData.length + 1;  // Add length octet.
        int udhSeptets = ((udhBytes * 8) + 6) / 7;
        Gsm7bitCodingResult gcr = encode7bitGsm(uData.payloadStr, udhSeptets, force);
        uData.msgEncoding = UserData.ENCODING_GSM_7BIT_ALPHABET;
        uData.msgEncodingSet = true;
        uData.numFields = gcr.septets;
        uData.payload = gcr.data;
        uData.payload[0] = (byte)udhData.length;
        System.arraycopy(udhData, 0, uData.payload, 1, udhData.length);
    }

    private static void encode16bitEms(UserData uData, byte[] udhData)
        throws CodingException
    {
        byte[] payload = encodeUtf16(uData.payloadStr);
        int udhBytes = udhData.length + 1;  // Add length octet.
        int udhCodeUnits = (udhBytes + 1) / 2;
        int payloadCodeUnits = payload.length / 2;
        uData.msgEncoding = UserData.ENCODING_UNICODE_16;
        uData.msgEncodingSet = true;
        uData.numFields = udhCodeUnits + payloadCodeUnits;
        uData.payload = new byte[uData.numFields * 2];
        uData.payload[0] = (byte)udhData.length;
        System.arraycopy(udhData, 0, uData.payload, 1, udhData.length);
        System.arraycopy(payload, 0, uData.payload, udhBytes, payload.length);
    }

    private static void encode7bitAsciiEms(UserData uData, byte[] udhData, boolean force)
            throws CodingException
    {
        try {
            Rlog.d(LOG_TAG, "encode7bitAsciiEms");
            int udhBytes = udhData.length + 1;  // Add length octet.
            int udhSeptets = ((udhBytes * 8) + 6) / 7;
            int paddingBits = (udhSeptets * 7) - (udhBytes * 8);
            String msg = uData.payloadStr;
            byte[] payload ;
            int msgLen = msg.length();
            BitwiseOutputStream outStream = new BitwiseOutputStream(msgLen +
                    (paddingBits > 0 ? 1 : 0));
            outStream.write(paddingBits, 0);
            for (int i = 0; i < msgLen; i++) {
                int charCode = UserData.charToAscii.get(msg.charAt(i), -1);
                if (charCode == -1) {
                    if (force) {
                        outStream.write(7, UserData.UNENCODABLE_7_BIT_CHAR);
                    } else {
                        throw new CodingException("cannot ASCII encode (" + msg.charAt(i) + ")");
                    }
                } else {
                    outStream.write(7, charCode);
                }
            }
            payload = outStream.toByteArray();
            uData.msgEncoding = UserData.ENCODING_7BIT_ASCII;
            uData.msgEncodingSet = true;
            uData.numFields = udhSeptets + uData.payloadStr.length();
            uData.payload = new byte[udhBytes + payload.length];
            uData.payload[0] = (byte)udhData.length;
            System.arraycopy(udhData, 0, uData.payload, 1, udhData.length);
            System.arraycopy(payload, 0, uData.payload, udhBytes, payload.length);
        } catch (BitwiseOutputStream.AccessException ex) {
            throw new CodingException("7bit ASCII encode failed: " + ex);
        }
    }

    private static void encodeEmsUserDataPayload(UserData uData)
        throws CodingException
    {
        byte[] headerData = SmsHeader.toByteArray(uData.userDataHeader);
        if (uData.msgEncodingSet) {
            if (uData.msgEncoding == UserData.ENCODING_GSM_7BIT_ALPHABET) {
                encode7bitEms(uData, headerData, true);
            } else if (uData.msgEncoding == UserData.ENCODING_UNICODE_16) {
                encode16bitEms(uData, headerData);
            } else if (uData.msgEncoding == UserData.ENCODING_7BIT_ASCII) {
                encode7bitAsciiEms(uData, headerData, true);
            } else {
                throw new CodingException("unsupported EMS user data encoding (" +
                                          uData.msgEncoding + ")");
            }
        } else {
            try {
                encode7bitEms(uData, headerData, false);
            } catch (CodingException ex) {
                encode16bitEms(uData, headerData);
            }
        }
    }

    private static byte[] encodeShiftJis(String msg) throws CodingException {
        try {
            return msg.getBytes("Shift_JIS");
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new CodingException("Shift-JIS encode failed: " + ex);
        }
    }

    private static void encodeUserDataPayload(UserData uData)
        throws CodingException
    {
        if ((uData.payloadStr == null) && (uData.msgEncoding != UserData.ENCODING_OCTET)) {
            Rlog.e(LOG_TAG, "user data with null payloadStr");
            uData.payloadStr = "";
        }

        if (uData.userDataHeader != null) {
            encodeEmsUserDataPayload(uData);
            return;
        }

        if (uData.msgEncodingSet) {
            if (uData.msgEncoding == UserData.ENCODING_OCTET) {
                if (uData.payload == null) {
                    Rlog.e(LOG_TAG, "user data with octet encoding but null payload");
                    uData.payload = new byte[0];
                    uData.numFields = 0;
                } else {
                    uData.numFields = uData.payload.length;
                }
            } else {
                if (uData.payloadStr == null) {
                    Rlog.e(LOG_TAG, "non-octet user data with null payloadStr");
                    uData.payloadStr = "";
                }
                if (uData.msgEncoding == UserData.ENCODING_GSM_7BIT_ALPHABET) {
                    Gsm7bitCodingResult gcr = encode7bitGsm(uData.payloadStr, 0, true);
                    uData.payload = gcr.data;
                    uData.numFields = gcr.septets;
                } else if (uData.msgEncoding == UserData.ENCODING_7BIT_ASCII) {
                    uData.payload = encode7bitAscii(uData.payloadStr, true);
                    uData.numFields = uData.payloadStr.length();
                } else if (uData.msgEncoding == UserData.ENCODING_UNICODE_16) {
                    uData.payload = encodeUtf16(uData.payloadStr);
                    uData.numFields = uData.payloadStr.length();
                } else if (uData.msgEncoding == UserData.ENCODING_SHIFT_JIS) {
                    uData.payload = encodeShiftJis(uData.payloadStr);
                    uData.numFields = uData.payload.length;
                } else {
                    throw new CodingException("unsupported user data encoding (" +
                                              uData.msgEncoding + ")");
                }
            }
        } else {
            try {
                uData.payload = encode7bitAscii(uData.payloadStr, false);
                uData.msgEncoding = UserData.ENCODING_7BIT_ASCII;
            } catch (CodingException ex) {
                uData.payload = encodeUtf16(uData.payloadStr);
                uData.msgEncoding = UserData.ENCODING_UNICODE_16;
            }
            uData.numFields = uData.payloadStr.length();
            uData.msgEncodingSet = true;
        }
    }

    private static void encodeUserData(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException, CodingException
    {
        /*
         * TODO(cleanup): Do we really need to set userData.payload as
         * a side effect of encoding?  If not, we could avoid data
         * copies by passing outStream directly.
         */
        encodeUserDataPayload(bData.userData);
        bData.hasUserDataHeader = bData.userData.userDataHeader != null;

        if (bData.userData.payload.length > SmsConstants.MAX_USER_DATA_BYTES) {
            throw new CodingException("encoded user data too large (" +
                                      bData.userData.payload.length +
                                      " > " + SmsConstants.MAX_USER_DATA_BYTES + " bytes)");
        }

        /*
         * TODO(cleanup): figure out what the right answer is WRT paddingBits field
         *
         *   userData.paddingBits = (userData.payload.length * 8) - (userData.numFields * 7);
         *   userData.paddingBits = 0; // XXX this seems better, but why?
         *
         */
        int dataBits = (bData.userData.payload.length * 8) - bData.userData.paddingBits;
        int paramBits = dataBits + 13;
        if ((bData.userData.msgEncoding == UserData.ENCODING_IS91_EXTENDED_PROTOCOL) ||
            (bData.userData.msgEncoding == UserData.ENCODING_GSM_DCS)) {
            paramBits += 8;
        }
        int paramBytes = (paramBits / 8) + ((paramBits % 8) > 0 ? 1 : 0);
        int paddingBits = (paramBytes * 8) - paramBits;
        outStream.write(8, paramBytes);
        outStream.write(5, bData.userData.msgEncoding);
        if ((bData.userData.msgEncoding == UserData.ENCODING_IS91_EXTENDED_PROTOCOL) ||
            (bData.userData.msgEncoding == UserData.ENCODING_GSM_DCS)) {
            outStream.write(8, bData.userData.msgType);
        }
        outStream.write(8, bData.userData.numFields);
        outStream.writeByteArray(dataBits, bData.userData.payload);
        if (paddingBits > 0) outStream.write(paddingBits, 0);
    }

    private static void encodeReplyOption(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException
    {
        outStream.write(8, 1);
        outStream.write(1, bData.userAckReq     ? 1 : 0);
        outStream.write(1, bData.deliveryAckReq ? 1 : 0);
        outStream.write(1, bData.readAckReq     ? 1 : 0);
        outStream.write(1, bData.reportReq      ? 1 : 0);
        outStream.write(4, 0);
    }

    private static byte[] encodeDtmfSmsAddress(String address) {
        int digits = address.length();
        int dataBits = digits * 4;
        int dataBytes = (dataBits / 8);
        dataBytes += (dataBits % 8) > 0 ? 1 : 0;
        byte[] rawData = new byte[dataBytes];
        for (int i = 0; i < digits; i++) {
            char c = address.charAt(i);
            int val = 0;
            if ((c >= '1') && (c <= '9')) val = c - '0';
            else if (c == '0') val = 10;
            else if (c == '*') val = 11;
            else if (c == '#') val = 12;
            else return null;
            rawData[i / 2] |= val << (4 - ((i % 2) * 4));
        }
        return rawData;
    }

    /*
     * TODO(cleanup): CdmaSmsAddress encoding should make use of
     * CdmaSmsAddress.parse provided that DTMF encoding is unified,
     * and the difference in 4-bit vs. 8-bit is resolved.
     */

    private static void encodeCdmaSmsAddress(CdmaSmsAddress addr) throws CodingException {
        if (addr.digitMode == CdmaSmsAddress.DIGIT_MODE_8BIT_CHAR) {
            try {
                addr.origBytes = addr.address.getBytes("US-ASCII");
            } catch (java.io.UnsupportedEncodingException ex) {
                throw new CodingException("invalid SMS address, cannot convert to ASCII");
            }
        } else {
            addr.origBytes = encodeDtmfSmsAddress(addr.address);
        }
    }

    private static void encodeCallbackNumber(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException, CodingException
    {
        CdmaSmsAddress addr = bData.callbackNumber;
        encodeCdmaSmsAddress(addr);
        int paramBits = 9;
        int dataBits = 0;
        if (addr.digitMode == CdmaSmsAddress.DIGIT_MODE_8BIT_CHAR) {
            paramBits += 7;
            dataBits = addr.numberOfDigits * 8;
        } else {
            dataBits = addr.numberOfDigits * 4;
        }
        paramBits += dataBits;
        int paramBytes = (paramBits / 8) + ((paramBits % 8) > 0 ? 1 : 0);
        int paddingBits = (paramBytes * 8) - paramBits;
        outStream.write(8, paramBytes);
        outStream.write(1, addr.digitMode);
        if (addr.digitMode == CdmaSmsAddress.DIGIT_MODE_8BIT_CHAR) {
            outStream.write(3, addr.ton);
            outStream.write(4, addr.numberPlan);
        }
        outStream.write(8, addr.numberOfDigits);
        outStream.writeByteArray(dataBits, addr.origBytes);
        if (paddingBits > 0) outStream.write(paddingBits, 0);
    }

    private static void encodeMsgStatus(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException
    {
        outStream.write(8, 1);
        outStream.write(2, bData.errorClass);
        outStream.write(6, bData.messageStatus);
    }

    private static void encodeMsgCount(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException
    {
        outStream.write(8, 1);
        outStream.write(8, bData.numberOfMessages);
    }

    private static void encodeValidityPeriodRel(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException
    {
        outStream.write(8, 1);
        outStream.write(8, bData.validityPeriodRelative);
    }

    private static void encodePrivacyIndicator(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException
    {
        outStream.write(8, 1);
        outStream.write(2, bData.privacy);
        outStream.skip(6);
    }

    private static void encodeLanguageIndicator(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException
    {
        outStream.write(8, 1);
        outStream.write(8, bData.language);
    }

    private static void encodeDisplayMode(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException
    {
        outStream.write(8, 1);
        outStream.write(2, bData.displayMode);
        outStream.skip(6);
    }

    private static void encodePriorityIndicator(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException
    {
        outStream.write(8, 1);
        outStream.write(2, bData.priority);
        outStream.skip(6);
    }

    private static void encodeMsgDeliveryAlert(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException
    {
        outStream.write(8, 1);
        outStream.write(2, bData.alert);
        outStream.skip(6);
    }

    private static void encodeScpResults(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException
    {
        ArrayList<CdmaSmsCbProgramResults> results = bData.serviceCategoryProgramResults;
        outStream.write(8, (results.size() * 4));   // 4 octets per program result
        for (CdmaSmsCbProgramResults result : results) {
            int category = result.getCategory();
            outStream.write(8, category >> 8);
            outStream.write(8, category);
            outStream.write(8, result.getLanguage());
            outStream.write(4, result.getCategoryResult());
            outStream.skip(4);
        }
    }

    /**
     * Create serialized representation for BearerData object.
     * (See 3GPP2 C.R1001-F, v1.0, section 4.5 for layout details)
     *
     * @param bData an instance of BearerData.
     *
     * @return byte array of raw encoded SMS bearer data.
     */
    public static byte[] encode(BearerData bData) {
        bData.hasUserDataHeader = ((bData.userData != null) &&
                (bData.userData.userDataHeader != null));
        try {
            BitwiseOutputStream outStream = new BitwiseOutputStream(200);
            outStream.write(8, SUBPARAM_MESSAGE_IDENTIFIER);
            encodeMessageId(bData, outStream);
            if (bData.userData != null) {
                outStream.write(8, SUBPARAM_USER_DATA);
                encodeUserData(bData, outStream);
            }
            if (bData.callbackNumber != null) {
                outStream.write(8, SUBPARAM_CALLBACK_NUMBER);
                encodeCallbackNumber(bData, outStream);
            }
            if (bData.userAckReq || bData.deliveryAckReq || bData.readAckReq || bData.reportReq) {
                outStream.write(8, SUBPARAM_REPLY_OPTION);
                encodeReplyOption(bData, outStream);
            }
            if (bData.numberOfMessages != 0) {
                outStream.write(8, SUBPARAM_NUMBER_OF_MESSAGES);
                encodeMsgCount(bData, outStream);
            }
            if (bData.validityPeriodRelativeSet) {
                outStream.write(8, SUBPARAM_VALIDITY_PERIOD_RELATIVE);
                encodeValidityPeriodRel(bData, outStream);
            }
            if (bData.privacyIndicatorSet) {
                outStream.write(8, SUBPARAM_PRIVACY_INDICATOR);
                encodePrivacyIndicator(bData, outStream);
            }
            if (bData.languageIndicatorSet) {
                outStream.write(8, SUBPARAM_LANGUAGE_INDICATOR);
                encodeLanguageIndicator(bData, outStream);
            }
            if (bData.displayModeSet) {
                outStream.write(8, SUBPARAM_MESSAGE_DISPLAY_MODE);
                encodeDisplayMode(bData, outStream);
            }
            if (bData.priorityIndicatorSet) {
                outStream.write(8, SUBPARAM_PRIORITY_INDICATOR);
                encodePriorityIndicator(bData, outStream);
            }
            if (bData.alertIndicatorSet) {
                outStream.write(8, SUBPARAM_ALERT_ON_MESSAGE_DELIVERY);
                encodeMsgDeliveryAlert(bData, outStream);
            }
            if (bData.messageStatusSet) {
                outStream.write(8, SUBPARAM_MESSAGE_STATUS);
                encodeMsgStatus(bData, outStream);
            }
            if (bData.serviceCategoryProgramResults != null) {
                outStream.write(8, SUBPARAM_SERVICE_CATEGORY_PROGRAM_RESULTS);
                encodeScpResults(bData, outStream);
            }
            return outStream.toByteArray();
        } catch (BitwiseOutputStream.AccessException ex) {
            Rlog.e(LOG_TAG, "BearerData encode failed: " + ex);
        } catch (CodingException ex) {
            Rlog.e(LOG_TAG, "BearerData encode failed: " + ex);
        }
        return null;
   }

    private static boolean decodeMessageId(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 3 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.messageType = inStream.read(4);
            bData.messageId = inStream.read(8) << 8;
            bData.messageId |= inStream.read(8);
            bData.hasUserDataHeader = (inStream.read(1) == 1);
            inStream.skip(3);
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "MESSAGE_IDENTIFIER decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        return decodeSuccess;
    }

    private static boolean decodeReserved(
            BearerData bData, BitwiseInputStream inStream, int subparamId)
        throws BitwiseInputStream.AccessException, CodingException
    {
        boolean decodeSuccess = false;
        int subparamLen = inStream.read(8); // SUBPARAM_LEN
        int paramBits = subparamLen * 8;
        if (paramBits <= inStream.available()) {
            decodeSuccess = true;
            inStream.skip(paramBits);
        }
        Rlog.d(LOG_TAG, "RESERVED bearer data subparameter " + subparamId + " decode "
                + (decodeSuccess ? "succeeded" : "failed") + " (param bits = " + paramBits + ")");
        if (!decodeSuccess) {
            throw new CodingException("RESERVED bearer data subparameter " + subparamId
                    + " had invalid SUBPARAM_LEN " + subparamLen);
        }

        return decodeSuccess;
    }

    private static boolean decodeUserData(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException
    {
        int paramBits = inStream.read(8) * 8;
        bData.userData = new UserData();
        bData.userData.msgEncoding = inStream.read(5);
        bData.userData.msgEncodingSet = true;
        bData.userData.msgType = 0;
        int consumedBits = 5;
        if ((bData.userData.msgEncoding == UserData.ENCODING_IS91_EXTENDED_PROTOCOL) ||
            (bData.userData.msgEncoding == UserData.ENCODING_GSM_DCS)) {
            bData.userData.msgType = inStream.read(8);
            consumedBits += 8;
        }
        bData.userData.numFields = inStream.read(8);
        consumedBits += 8;
        int dataBits = paramBits - consumedBits;
        bData.userData.payload = inStream.readByteArray(dataBits);
        return true;
    }

    private static String decodeUtf8(byte[] data, int offset, int numFields)
        throws CodingException
    {
        return decodeCharset(data, offset, numFields, 1, "UTF-8");
    }

    private static String decodeUtf16(byte[] data, int offset, int numFields)
        throws CodingException
    {
        // Subtract header and possible padding byte (at end) from num fields.
        int padding = offset % 2;
        numFields -= (offset + padding) / 2;
        return decodeCharset(data, offset, numFields, 2, "utf-16be");
    }

    private static String decodeCharset(byte[] data, int offset, int numFields, int width,
            String charset) throws CodingException
    {
        if (numFields < 0 || (numFields * width + offset) > data.length) {
            // Try to decode the max number of characters in payload
            int padding = offset % width;
            int maxNumFields = (data.length - offset - padding) / width;
            if (maxNumFields < 0) {
                throw new CodingException(charset + " decode failed: offset out of range");
            }
            Rlog.e(LOG_TAG, charset + " decode error: offset = " + offset + " numFields = "
                    + numFields + " data.length = " + data.length + " maxNumFields = "
                    + maxNumFields);
            numFields = maxNumFields;
        }
        try {
            return new String(data, offset, numFields * width, charset);
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new CodingException(charset + " decode failed: " + ex);
        }
    }

    private static String decode7bitAscii(byte[] data, int offset, int numFields)
        throws CodingException
    {
        try {
            int offsetBits = offset * 8;
            int offsetSeptets = (offsetBits + 6) / 7;
            numFields -= offsetSeptets;

            StringBuffer strBuf = new StringBuffer(numFields);
            BitwiseInputStream inStream = new BitwiseInputStream(data);
            int wantedBits = (offsetSeptets * 7) + (numFields * 7);
            if (inStream.available() < wantedBits) {
                throw new CodingException("insufficient data (wanted " + wantedBits +
                                          " bits, but only have " + inStream.available() + ")");
            }
            inStream.skip(offsetSeptets * 7);
            for (int i = 0; i < numFields; i++) {
                int charCode = inStream.read(7);
                if ((charCode >= UserData.ASCII_MAP_BASE_INDEX) &&
                        (charCode <= UserData.ASCII_MAP_MAX_INDEX)) {
                    strBuf.append(UserData.ASCII_MAP[charCode - UserData.ASCII_MAP_BASE_INDEX]);
                } else if (charCode == UserData.ASCII_NL_INDEX) {
                    strBuf.append('\n');
                } else if (charCode == UserData.ASCII_CR_INDEX) {
                    strBuf.append('\r');
                } else {
                    /* For other charCodes, they are unprintable, and so simply use SPACE. */
                    strBuf.append(' ');
                }
            }
            return strBuf.toString();
        } catch (BitwiseInputStream.AccessException ex) {
            throw new CodingException("7bit ASCII decode failed: " + ex);
        }
    }

    private static String decode7bitGsm(byte[] data, int offset, int numFields)
        throws CodingException
    {
        // Start reading from the next 7-bit aligned boundary after offset.
        int offsetBits = offset * 8;
        int offsetSeptets = (offsetBits + 6) / 7;
        numFields -= offsetSeptets;
        int paddingBits = (offsetSeptets * 7) - offsetBits;
        String result = GsmAlphabet.gsm7BitPackedToString(data, offset, numFields, paddingBits,
                0, 0);
        if (result == null) {
            throw new CodingException("7bit GSM decoding failed");
        }
        return result;
    }

    private static String decodeLatin(byte[] data, int offset, int numFields)
        throws CodingException
    {
        return decodeCharset(data, offset, numFields, 1, "ISO-8859-1");
    }

    private static String decodeShiftJis(byte[] data, int offset, int numFields)
        throws CodingException
    {
        return decodeCharset(data, offset, numFields, 1, "Shift_JIS");
    }

    private static String decodeGsmDcs(byte[] data, int offset, int numFields, int msgType)
            throws CodingException
    {
        if ((msgType & 0xC0) != 0) {
            throw new CodingException("unsupported coding group ("
                    + msgType + ")");
        }

        switch ((msgType >> 2) & 0x3) {
        case UserData.ENCODING_GSM_DCS_7BIT:
            return decode7bitGsm(data, offset, numFields);
        case UserData.ENCODING_GSM_DCS_8BIT:
            return decodeUtf8(data, offset, numFields);
        case UserData.ENCODING_GSM_DCS_16BIT:
            return decodeUtf16(data, offset, numFields);
        default:
            throw new CodingException("unsupported user msgType encoding ("
                    + msgType + ")");
        }
    }

    private static void decodeUserDataPayload(UserData userData, boolean hasUserDataHeader)
        throws CodingException
    {
        int offset = 0;
        if (hasUserDataHeader) {
            int udhLen = userData.payload[0] & 0x00FF;
            offset += udhLen + 1;
            byte[] headerData = new byte[udhLen];
            System.arraycopy(userData.payload, 1, headerData, 0, udhLen);
            userData.userDataHeader = SmsHeader.fromByteArray(headerData);
        }
        switch (userData.msgEncoding) {
        case UserData.ENCODING_OCTET:
            /*
            *  Octet decoding depends on the carrier service.
            */
            boolean decodingtypeUTF8 = Resources.getSystem()
                    .getBoolean(com.android.internal.R.bool.config_sms_utf8_support);

            // Strip off any padding bytes, meaning any differences between the length of the
            // array and the target length specified by numFields.  This is to avoid any
            // confusion by code elsewhere that only considers the payload array length.
            byte[] payload = new byte[userData.numFields];
            int copyLen = userData.numFields < userData.payload.length
                    ? userData.numFields : userData.payload.length;

            System.arraycopy(userData.payload, 0, payload, 0, copyLen);
            userData.payload = payload;

            if (!decodingtypeUTF8) {
                // There are many devices in the market that send 8bit text sms (latin encoded) as
                // octet encoded.
                userData.payloadStr = decodeLatin(userData.payload, offset, userData.numFields);
            } else {
                userData.payloadStr = decodeUtf8(userData.payload, offset, userData.numFields);
            }
            break;

        case UserData.ENCODING_IA5:
        case UserData.ENCODING_7BIT_ASCII:
            userData.payloadStr = decode7bitAscii(userData.payload, offset, userData.numFields);
            break;
        case UserData.ENCODING_UNICODE_16:
            userData.payloadStr = decodeUtf16(userData.payload, offset, userData.numFields);
            break;
        case UserData.ENCODING_GSM_7BIT_ALPHABET:
            userData.payloadStr = decode7bitGsm(userData.payload, offset, userData.numFields);
            break;
        case UserData.ENCODING_LATIN:
            userData.payloadStr = decodeLatin(userData.payload, offset, userData.numFields);
            break;
        case UserData.ENCODING_SHIFT_JIS:
            userData.payloadStr = decodeShiftJis(userData.payload, offset, userData.numFields);
            break;
        case UserData.ENCODING_GSM_DCS:
            userData.payloadStr = decodeGsmDcs(userData.payload, offset,
                    userData.numFields, userData.msgType);
            break;
        default:
            throw new CodingException("unsupported user data encoding ("
                                      + userData.msgEncoding + ")");
        }
    }

    /**
     * IS-91 Voice Mail message decoding
     * (See 3GPP2 C.S0015-A, Table 4.3.1.4.1-1)
     * (For character encodings, see TIA/EIA/IS-91, Annex B)
     *
     * Protocol Summary: The user data payload may contain 3-14
     * characters.  The first two characters are parsed as a number
     * and indicate the number of voicemails.  The third character is
     * either a SPACE or '!' to indicate normal or urgent priority,
     * respectively.  Any following characters are treated as normal
     * text user data payload.
     *
     * Note that the characters encoding is 6-bit packed.
     */
    private static void decodeIs91VoicemailStatus(BearerData bData)
        throws BitwiseInputStream.AccessException, CodingException
    {
        BitwiseInputStream inStream = new BitwiseInputStream(bData.userData.payload);
        int dataLen = inStream.available() / 6;  // 6-bit packed character encoding.
        int numFields = bData.userData.numFields;
        if ((dataLen > 14) || (dataLen < 3) || (dataLen < numFields)) {
            throw new CodingException("IS-91 voicemail status decoding failed");
        }
        try {
            StringBuffer strbuf = new StringBuffer(dataLen);
            while (inStream.available() >= 6) {
                strbuf.append(UserData.ASCII_MAP[inStream.read(6)]);
            }
            String data = strbuf.toString();
            bData.numberOfMessages = Integer.parseInt(data.substring(0, 2));
            char prioCode = data.charAt(2);
            if (prioCode == ' ') {
                bData.priority = PRIORITY_NORMAL;
            } else if (prioCode == '!') {
                bData.priority = PRIORITY_URGENT;
            } else {
                throw new CodingException("IS-91 voicemail status decoding failed: " +
                        "illegal priority setting (" + prioCode + ")");
            }
            bData.priorityIndicatorSet = true;
            bData.userData.payloadStr = data.substring(3, numFields - 3);
       } catch (java.lang.NumberFormatException ex) {
            throw new CodingException("IS-91 voicemail status decoding failed: " + ex);
        } catch (java.lang.IndexOutOfBoundsException ex) {
            throw new CodingException("IS-91 voicemail status decoding failed: " + ex);
        }
    }

    /**
     * IS-91 Short Message decoding
     * (See 3GPP2 C.S0015-A, Table 4.3.1.4.1-1)
     * (For character encodings, see TIA/EIA/IS-91, Annex B)
     *
     * Protocol Summary: The user data payload may contain 1-14
     * characters, which are treated as normal text user data payload.
     * Note that the characters encoding is 6-bit packed.
     */
    private static void decodeIs91ShortMessage(BearerData bData)
        throws BitwiseInputStream.AccessException, CodingException
    {
        BitwiseInputStream inStream = new BitwiseInputStream(bData.userData.payload);
        int dataLen = inStream.available() / 6;  // 6-bit packed character encoding.
        int numFields = bData.userData.numFields;
        // dataLen may be > 14 characters due to octet padding
        if ((numFields > 14) || (dataLen < numFields)) {
            throw new CodingException("IS-91 short message decoding failed");
        }
        StringBuffer strbuf = new StringBuffer(dataLen);
        for (int i = 0; i < numFields; i++) {
            strbuf.append(UserData.ASCII_MAP[inStream.read(6)]);
        }
        bData.userData.payloadStr = strbuf.toString();
    }

    /**
     * IS-91 CLI message (callback number) decoding
     * (See 3GPP2 C.S0015-A, Table 4.3.1.4.1-1)
     *
     * Protocol Summary: The data payload may contain 1-32 digits,
     * encoded using standard 4-bit DTMF, which are treated as a
     * callback number.
     */
    private static void decodeIs91Cli(BearerData bData) throws CodingException {
        BitwiseInputStream inStream = new BitwiseInputStream(bData.userData.payload);
        int dataLen = inStream.available() / 4;  // 4-bit packed DTMF digit encoding.
        int numFields = bData.userData.numFields;
        if ((dataLen > 14) || (dataLen < 3) || (dataLen < numFields)) {
            throw new CodingException("IS-91 voicemail status decoding failed");
        }
        CdmaSmsAddress addr = new CdmaSmsAddress();
        addr.digitMode = CdmaSmsAddress.DIGIT_MODE_4BIT_DTMF;
        addr.origBytes = bData.userData.payload;
        addr.numberOfDigits = (byte)numFields;
        decodeSmsAddress(addr);
        bData.callbackNumber = addr;
    }

    private static void decodeIs91(BearerData bData)
        throws BitwiseInputStream.AccessException, CodingException
    {
        switch (bData.userData.msgType) {
        case UserData.IS91_MSG_TYPE_VOICEMAIL_STATUS:
            decodeIs91VoicemailStatus(bData);
            break;
        case UserData.IS91_MSG_TYPE_CLI:
            decodeIs91Cli(bData);
            break;
        case UserData.IS91_MSG_TYPE_SHORT_MESSAGE_FULL:
        case UserData.IS91_MSG_TYPE_SHORT_MESSAGE:
            decodeIs91ShortMessage(bData);
            break;
        default:
            throw new CodingException("unsupported IS-91 message type (" +
                    bData.userData.msgType + ")");
        }
    }

    private static boolean decodeReplyOption(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 1 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.userAckReq     = (inStream.read(1) == 1);
            bData.deliveryAckReq = (inStream.read(1) == 1);
            bData.readAckReq     = (inStream.read(1) == 1);
            bData.reportReq      = (inStream.read(1) == 1);
            inStream.skip(4);
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "REPLY_OPTION decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        return decodeSuccess;
    }

    private static boolean decodeMsgCount(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 1 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.numberOfMessages = IccUtils.cdmaBcdByteToInt((byte)inStream.read(8));
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "NUMBER_OF_MESSAGES decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        return decodeSuccess;
    }

    private static boolean decodeDepositIndex(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 2 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.depositIndex = (inStream.read(8) << 8) | inStream.read(8);
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "MESSAGE_DEPOSIT_INDEX decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        return decodeSuccess;
    }

    private static String decodeDtmfSmsAddress(byte[] rawData, int numFields)
        throws CodingException
    {
        /* DTMF 4-bit digit encoding, defined in at
         * 3GPP2 C.S005-D, v2.0, table 2.7.1.3.2.4-4 */
        StringBuffer strBuf = new StringBuffer(numFields);
        for (int i = 0; i < numFields; i++) {
            int val = 0x0F & (rawData[i / 2] >>> (4 - ((i % 2) * 4)));
            if ((val >= 1) && (val <= 9)) strBuf.append(Integer.toString(val, 10));
            else if (val == 10) strBuf.append('0');
            else if (val == 11) strBuf.append('*');
            else if (val == 12) strBuf.append('#');
            else throw new CodingException("invalid SMS address DTMF code (" + val + ")");
        }
        return strBuf.toString();
    }

    private static void decodeSmsAddress(CdmaSmsAddress addr) throws CodingException {
        if (addr.digitMode == CdmaSmsAddress.DIGIT_MODE_8BIT_CHAR) {
            try {
                /* As specified in 3GPP2 C.S0015-B, v2, 4.5.15 -- actually
                 * just 7-bit ASCII encoding, with the MSB being zero. */
                addr.address = new String(addr.origBytes, 0, addr.origBytes.length, "US-ASCII");
            } catch (java.io.UnsupportedEncodingException ex) {
                throw new CodingException("invalid SMS address ASCII code");
            }
        } else {
            addr.address = decodeDtmfSmsAddress(addr.origBytes, addr.numberOfDigits);
        }
    }

    private static boolean decodeCallbackNumber(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        final int EXPECTED_PARAM_SIZE = 1 * 8; //at least
        int paramBits = inStream.read(8) * 8;
        if (paramBits < EXPECTED_PARAM_SIZE) {
            inStream.skip(paramBits);
            return false;
        }
        CdmaSmsAddress addr = new CdmaSmsAddress();
        addr.digitMode = inStream.read(1);
        byte fieldBits = 4;
        byte consumedBits = 1;
        if (addr.digitMode == CdmaSmsAddress.DIGIT_MODE_8BIT_CHAR) {
            addr.ton = inStream.read(3);
            addr.numberPlan = inStream.read(4);
            fieldBits = 8;
            consumedBits += 7;
        }
        addr.numberOfDigits = inStream.read(8);
        consumedBits += 8;
        int remainingBits = paramBits - consumedBits;
        int dataBits = addr.numberOfDigits * fieldBits;
        int paddingBits = remainingBits - dataBits;
        if (remainingBits < dataBits) {
            throw new CodingException("CALLBACK_NUMBER subparam encoding size error (" +
                                      "remainingBits + " + remainingBits + ", dataBits + " +
                                      dataBits + ", paddingBits + " + paddingBits + ")");
        }
        addr.origBytes = inStream.readByteArray(dataBits);
        inStream.skip(paddingBits);
        decodeSmsAddress(addr);
        bData.callbackNumber = addr;
        return true;
    }

    private static boolean decodeMsgStatus(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 1 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.errorClass = inStream.read(2);
            bData.messageStatus = inStream.read(6);
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "MESSAGE_STATUS decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        bData.messageStatusSet = decodeSuccess;
        return decodeSuccess;
    }

    private static boolean decodeMsgCenterTimeStamp(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 6 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.msgCenterTimeStamp = TimeStamp.fromByteArray(inStream.readByteArray(6 * 8));
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "MESSAGE_CENTER_TIME_STAMP decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        return decodeSuccess;
    }

    private static boolean decodeValidityAbs(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 6 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.validityPeriodAbsolute = TimeStamp.fromByteArray(inStream.readByteArray(6 * 8));
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "VALIDITY_PERIOD_ABSOLUTE decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        return decodeSuccess;
    }

    private static boolean decodeDeferredDeliveryAbs(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 6 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.deferredDeliveryTimeAbsolute = TimeStamp.fromByteArray(
                    inStream.readByteArray(6 * 8));
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "DEFERRED_DELIVERY_TIME_ABSOLUTE decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        return decodeSuccess;
    }

    private static boolean decodeValidityRel(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 1 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.deferredDeliveryTimeRelative = inStream.read(8);
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "VALIDITY_PERIOD_RELATIVE decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        bData.deferredDeliveryTimeRelativeSet = decodeSuccess;
        return decodeSuccess;
    }

    private static boolean decodeDeferredDeliveryRel(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 1 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.validityPeriodRelative = inStream.read(8);
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "DEFERRED_DELIVERY_TIME_RELATIVE decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        bData.validityPeriodRelativeSet = decodeSuccess;
        return decodeSuccess;
    }

    private static boolean decodePrivacyIndicator(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 1 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.privacy = inStream.read(2);
            inStream.skip(6);
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "PRIVACY_INDICATOR decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        bData.privacyIndicatorSet = decodeSuccess;
        return decodeSuccess;
    }

    private static boolean decodeLanguageIndicator(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 1 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.language = inStream.read(8);
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "LANGUAGE_INDICATOR decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        bData.languageIndicatorSet = decodeSuccess;
        return decodeSuccess;
    }

    private static boolean decodeDisplayMode(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 1 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.displayMode = inStream.read(2);
            inStream.skip(6);
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "DISPLAY_MODE decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        bData.displayModeSet = decodeSuccess;
        return decodeSuccess;
    }

    private static boolean decodePriorityIndicator(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 1 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.priority = inStream.read(2);
            inStream.skip(6);
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "PRIORITY_INDICATOR decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        bData.priorityIndicatorSet = decodeSuccess;
        return decodeSuccess;
    }

    private static boolean decodeMsgDeliveryAlert(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 1 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.alert = inStream.read(2);
            inStream.skip(6);
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "ALERT_ON_MESSAGE_DELIVERY decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        bData.alertIndicatorSet = decodeSuccess;
        return decodeSuccess;
    }

    private static boolean decodeUserResponseCode(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException {
        final int EXPECTED_PARAM_SIZE = 1 * 8;
        boolean decodeSuccess = false;
        int paramBits = inStream.read(8) * 8;
        if (paramBits >= EXPECTED_PARAM_SIZE) {
            paramBits -= EXPECTED_PARAM_SIZE;
            decodeSuccess = true;
            bData.userResponseCode = inStream.read(8);
        }
        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "USER_RESPONSE_CODE decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ")");
        }
        inStream.skip(paramBits);
        bData.userResponseCodeSet = decodeSuccess;
        return decodeSuccess;
    }

    private static boolean decodeServiceCategoryProgramData(BearerData bData,
            BitwiseInputStream inStream) throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.available() < 13) {
            throw new CodingException("SERVICE_CATEGORY_PROGRAM_DATA decode failed: only "
                    + inStream.available() + " bits available");
        }

        int paramBits = inStream.read(8) * 8;
        int msgEncoding = inStream.read(5);
        paramBits -= 5;

        if (inStream.available() < paramBits) {
            throw new CodingException("SERVICE_CATEGORY_PROGRAM_DATA decode failed: only "
                    + inStream.available() + " bits available (" + paramBits + " bits expected)");
        }

        ArrayList<CdmaSmsCbProgramData> programDataList = new ArrayList<CdmaSmsCbProgramData>();

        final int CATEGORY_FIELD_MIN_SIZE = 6 * 8;
        boolean decodeSuccess = false;
        while (paramBits >= CATEGORY_FIELD_MIN_SIZE) {
            int operation = inStream.read(4);
            int category = (inStream.read(8) << 8) | inStream.read(8);
            int language = inStream.read(8);
            int maxMessages = inStream.read(8);
            int alertOption = inStream.read(4);
            int numFields = inStream.read(8);
            paramBits -= CATEGORY_FIELD_MIN_SIZE;

            int textBits = getBitsForNumFields(msgEncoding, numFields);
            if (paramBits < textBits) {
                throw new CodingException("category name is " + textBits + " bits in length,"
                        + " but there are only " + paramBits + " bits available");
            }

            UserData userData = new UserData();
            userData.msgEncoding = msgEncoding;
            userData.msgEncodingSet = true;
            userData.numFields = numFields;
            userData.payload = inStream.readByteArray(textBits);
            paramBits -= textBits;

            decodeUserDataPayload(userData, false);
            String categoryName = userData.payloadStr;
            CdmaSmsCbProgramData programData = new CdmaSmsCbProgramData(operation, category,
                    language, maxMessages, alertOption, categoryName);
            programDataList.add(programData);

            decodeSuccess = true;
        }

        if ((! decodeSuccess) || (paramBits > 0)) {
            Rlog.d(LOG_TAG, "SERVICE_CATEGORY_PROGRAM_DATA decode " +
                      (decodeSuccess ? "succeeded" : "failed") +
                      " (extra bits = " + paramBits + ')');
        }

        inStream.skip(paramBits);
        bData.serviceCategoryProgramData = programDataList;
        return decodeSuccess;
    }

    private static int serviceCategoryToCmasMessageClass(int serviceCategory) {
        switch (serviceCategory) {
            case SmsEnvelope.SERVICE_CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT:
                return SmsCbCmasInfo.CMAS_CLASS_PRESIDENTIAL_LEVEL_ALERT;

            case SmsEnvelope.SERVICE_CATEGORY_CMAS_EXTREME_THREAT:
                return SmsCbCmasInfo.CMAS_CLASS_EXTREME_THREAT;

            case SmsEnvelope.SERVICE_CATEGORY_CMAS_SEVERE_THREAT:
                return SmsCbCmasInfo.CMAS_CLASS_SEVERE_THREAT;

            case SmsEnvelope.SERVICE_CATEGORY_CMAS_CHILD_ABDUCTION_EMERGENCY:
                return SmsCbCmasInfo.CMAS_CLASS_CHILD_ABDUCTION_EMERGENCY;

            case SmsEnvelope.SERVICE_CATEGORY_CMAS_TEST_MESSAGE:
                return SmsCbCmasInfo.CMAS_CLASS_REQUIRED_MONTHLY_TEST;

            default:
                return SmsCbCmasInfo.CMAS_CLASS_UNKNOWN;
        }
    }

    /**
     * Calculates the number of bits to read for the specified number of encoded characters.
     * @param msgEncoding the message encoding to use
     * @param numFields the number of characters to read. For Shift-JIS and Korean encodings,
     *  this is the number of bytes to read.
     * @return the number of bits to read from the stream
     * @throws CodingException if the specified encoding is not supported
     */
    private static int getBitsForNumFields(int msgEncoding, int numFields)
            throws CodingException {
        switch (msgEncoding) {
            case UserData.ENCODING_OCTET:
            case UserData.ENCODING_SHIFT_JIS:
            case UserData.ENCODING_KOREAN:
            case UserData.ENCODING_LATIN:
            case UserData.ENCODING_LATIN_HEBREW:
                return numFields * 8;

            case UserData.ENCODING_IA5:
            case UserData.ENCODING_7BIT_ASCII:
            case UserData.ENCODING_GSM_7BIT_ALPHABET:
                return numFields * 7;

            case UserData.ENCODING_UNICODE_16:
                return numFields * 16;

            default:
                throw new CodingException("unsupported message encoding (" + msgEncoding + ')');
        }
    }

    /**
     * CMAS message decoding.
     * (See TIA-1149-0-1, CMAS over CDMA)
     *
     * @param serviceCategory is the service category from the SMS envelope
     */
    private static void decodeCmasUserData(BearerData bData, int serviceCategory)
            throws BitwiseInputStream.AccessException, CodingException {
        BitwiseInputStream inStream = new BitwiseInputStream(bData.userData.payload);
        if (inStream.available() < 8) {
            throw new CodingException("emergency CB with no CMAE_protocol_version");
        }
        int protocolVersion = inStream.read(8);
        if (protocolVersion != 0) {
            throw new CodingException("unsupported CMAE_protocol_version " + protocolVersion);
        }

        int messageClass = serviceCategoryToCmasMessageClass(serviceCategory);
        int category = SmsCbCmasInfo.CMAS_CATEGORY_UNKNOWN;
        int responseType = SmsCbCmasInfo.CMAS_RESPONSE_TYPE_UNKNOWN;
        int severity = SmsCbCmasInfo.CMAS_SEVERITY_UNKNOWN;
        int urgency = SmsCbCmasInfo.CMAS_URGENCY_UNKNOWN;
        int certainty = SmsCbCmasInfo.CMAS_CERTAINTY_UNKNOWN;

        while (inStream.available() >= 16) {
            int recordType = inStream.read(8);
            int recordLen = inStream.read(8);
            switch (recordType) {
                case 0:     // Type 0 elements (Alert text)
                    UserData alertUserData = new UserData();
                    alertUserData.msgEncoding = inStream.read(5);
                    alertUserData.msgEncodingSet = true;
                    alertUserData.msgType = 0;

                    int numFields;                          // number of chars to decode
                    switch (alertUserData.msgEncoding) {
                        case UserData.ENCODING_OCTET:
                        case UserData.ENCODING_LATIN:
                            numFields = recordLen - 1;      // subtract 1 byte for encoding
                            break;

                        case UserData.ENCODING_IA5:
                        case UserData.ENCODING_7BIT_ASCII:
                        case UserData.ENCODING_GSM_7BIT_ALPHABET:
                            numFields = ((recordLen * 8) - 5) / 7;  // subtract 5 bits for encoding
                            break;

                        case UserData.ENCODING_UNICODE_16:
                            numFields = (recordLen - 1) / 2;
                            break;

                        default:
                            numFields = 0;      // unsupported encoding
                    }

                    alertUserData.numFields = numFields;
                    alertUserData.payload = inStream.readByteArray(recordLen * 8 - 5);
                    decodeUserDataPayload(alertUserData, false);
                    bData.userData = alertUserData;
                    break;

                case 1:     // Type 1 elements
                    category = inStream.read(8);
                    responseType = inStream.read(8);
                    severity = inStream.read(4);
                    urgency = inStream.read(4);
                    certainty = inStream.read(4);
                    inStream.skip(recordLen * 8 - 28);
                    break;

                default:
                    Rlog.w(LOG_TAG, "skipping unsupported CMAS record type " + recordType);
                    inStream.skip(recordLen * 8);
                    break;
            }
        }

        bData.cmasWarningInfo = new SmsCbCmasInfo(messageClass, category, responseType, severity,
                urgency, certainty);
    }

    /**
     * Create BearerData object from serialized representation.
     * (See 3GPP2 C.R1001-F, v1.0, section 4.5 for layout details)
     *
     * @param smsData byte array of raw encoded SMS bearer data.
     * @return an instance of BearerData.
     */
    public static BearerData decode(byte[] smsData) {
        return decode(smsData, 0);
    }

    private static boolean isCmasAlertCategory(int category) {
        return category >= SmsEnvelope.SERVICE_CATEGORY_CMAS_PRESIDENTIAL_LEVEL_ALERT
                && category <= SmsEnvelope.SERVICE_CATEGORY_CMAS_LAST_RESERVED_VALUE;
    }

    /**
     * Create BearerData object from serialized representation.
     * (See 3GPP2 C.R1001-F, v1.0, section 4.5 for layout details)
     *
     * @param smsData byte array of raw encoded SMS bearer data.
     * @param serviceCategory the envelope service category (for CMAS alert handling)
     * @return an instance of BearerData.
     */
    public static BearerData decode(byte[] smsData, int serviceCategory) {
        try {
            BitwiseInputStream inStream = new BitwiseInputStream(smsData);
            BearerData bData = new BearerData();
            int foundSubparamMask = 0;
            while (inStream.available() > 0) {
                int subparamId = inStream.read(8);
                int subparamIdBit = 1 << subparamId;
                // int is 4 bytes. This duplicate check has a limit to Id number up to 32 (4*8)
                // as 32th bit is the max bit in int.
                // Per 3GPP2 C.S0015-B Table 4.5-1 Bearer Data Subparameter Identifiers:
                // last defined subparam ID is 23 (00010111 = 0x17 = 23).
                // Only do duplicate subparam ID check if subparam is within defined value as
                // reserved subparams are just skipped.
                if ((foundSubparamMask & subparamIdBit) != 0 &&
                        (subparamId >= SUBPARAM_MESSAGE_IDENTIFIER &&
                        subparamId <= SUBPARAM_ID_LAST_DEFINED)) {
                    throw new CodingException("illegal duplicate subparameter (" +
                                              subparamId + ")");
                }
                boolean decodeSuccess;
                switch (subparamId) {
                case SUBPARAM_MESSAGE_IDENTIFIER:
                    decodeSuccess = decodeMessageId(bData, inStream);
                    break;
                case SUBPARAM_USER_DATA:
                    decodeSuccess = decodeUserData(bData, inStream);
                    break;
                case SUBPARAM_USER_RESPONSE_CODE:
                    decodeSuccess = decodeUserResponseCode(bData, inStream);
                    break;
                case SUBPARAM_REPLY_OPTION:
                    decodeSuccess = decodeReplyOption(bData, inStream);
                    break;
                case SUBPARAM_NUMBER_OF_MESSAGES:
                    decodeSuccess = decodeMsgCount(bData, inStream);
                    break;
                case SUBPARAM_CALLBACK_NUMBER:
                    decodeSuccess = decodeCallbackNumber(bData, inStream);
                    break;
                case SUBPARAM_MESSAGE_STATUS:
                    decodeSuccess = decodeMsgStatus(bData, inStream);
                    break;
                case SUBPARAM_MESSAGE_CENTER_TIME_STAMP:
                    decodeSuccess = decodeMsgCenterTimeStamp(bData, inStream);
                    break;
                case SUBPARAM_VALIDITY_PERIOD_ABSOLUTE:
                    decodeSuccess = decodeValidityAbs(bData, inStream);
                    break;
                case SUBPARAM_VALIDITY_PERIOD_RELATIVE:
                    decodeSuccess = decodeValidityRel(bData, inStream);
                    break;
                case SUBPARAM_DEFERRED_DELIVERY_TIME_ABSOLUTE:
                    decodeSuccess = decodeDeferredDeliveryAbs(bData, inStream);
                    break;
                case SUBPARAM_DEFERRED_DELIVERY_TIME_RELATIVE:
                    decodeSuccess = decodeDeferredDeliveryRel(bData, inStream);
                    break;
                case SUBPARAM_PRIVACY_INDICATOR:
                    decodeSuccess = decodePrivacyIndicator(bData, inStream);
                    break;
                case SUBPARAM_LANGUAGE_INDICATOR:
                    decodeSuccess = decodeLanguageIndicator(bData, inStream);
                    break;
                case SUBPARAM_MESSAGE_DISPLAY_MODE:
                    decodeSuccess = decodeDisplayMode(bData, inStream);
                    break;
                case SUBPARAM_PRIORITY_INDICATOR:
                    decodeSuccess = decodePriorityIndicator(bData, inStream);
                    break;
                case SUBPARAM_ALERT_ON_MESSAGE_DELIVERY:
                    decodeSuccess = decodeMsgDeliveryAlert(bData, inStream);
                    break;
                case SUBPARAM_MESSAGE_DEPOSIT_INDEX:
                    decodeSuccess = decodeDepositIndex(bData, inStream);
                    break;
                case SUBPARAM_SERVICE_CATEGORY_PROGRAM_DATA:
                    decodeSuccess = decodeServiceCategoryProgramData(bData, inStream);
                    break;
                default:
                    decodeSuccess = decodeReserved(bData, inStream, subparamId);
                }
                if (decodeSuccess &&
                        (subparamId >= SUBPARAM_MESSAGE_IDENTIFIER &&
                        subparamId <= SUBPARAM_ID_LAST_DEFINED)) {
                    foundSubparamMask |= subparamIdBit;
                }
            }
            if ((foundSubparamMask & (1 << SUBPARAM_MESSAGE_IDENTIFIER)) == 0) {
                throw new CodingException("missing MESSAGE_IDENTIFIER subparam");
            }
            if (bData.userData != null) {
                if (isCmasAlertCategory(serviceCategory)) {
                    decodeCmasUserData(bData, serviceCategory);
                } else if (bData.userData.msgEncoding == UserData.ENCODING_IS91_EXTENDED_PROTOCOL) {
                    if ((foundSubparamMask ^
                             (1 << SUBPARAM_MESSAGE_IDENTIFIER) ^
                             (1 << SUBPARAM_USER_DATA))
                            != 0) {
                        Rlog.e(LOG_TAG, "IS-91 must occur without extra subparams (" +
                              foundSubparamMask + ")");
                    }
                    decodeIs91(bData);
                } else {
                    decodeUserDataPayload(bData.userData, bData.hasUserDataHeader);
                }
            }
            return bData;
        } catch (BitwiseInputStream.AccessException ex) {
            Rlog.e(LOG_TAG, "BearerData decode failed: " + ex);
        } catch (CodingException ex) {
            Rlog.e(LOG_TAG, "BearerData decode failed: " + ex);
        }
        return null;
    }
}
