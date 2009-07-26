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

import android.util.Log;
import android.util.SparseIntArray;

import android.telephony.SmsMessage;

import android.text.format.Time;

import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.cdma.sms.UserData;
import com.android.internal.telephony.SmsMessageBase.TextEncodingDetails;

import com.android.internal.util.HexDump;
import com.android.internal.util.BitwiseInputStream;
import com.android.internal.util.BitwiseOutputStream;


/**
 * An object to encode and decode CDMA SMS bearer data.
 */
public final class BearerData {
    private final static String LOG_TAG = "SMS";

    /**
     * Bearer Data Subparameter Indentifiers
     * (See 3GPP2 C.S0015-B, v2.0, table 4.5-1)
     * NOTE: Commented subparameter types are not implemented.
     */
    private final static byte SUBPARAM_MESSAGE_IDENTIFIER               = 0x00;
    private final static byte SUBPARAM_USER_DATA                        = 0x01;
    private final static byte SUBPARAM_USER_REPONSE_CODE                = 0x02;
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
    //private final static byte SUBPARAM_SERVICE_CATEGORY_PROGRAM_DATA    = 0x12;
    //private final static byte SUBPARAM_SERVICE_CATEGORY_PROGRAM_RESULTS = 0x13;
    private final static byte SUBPARAM_MESSAGE_STATUS                   = 0x14;
    //private final static byte SUBPARAM_TP_FAILURE_CAUSE                 = 0x15;
    //private final static byte SUBPARAM_ENHANCED_VMN                     = 0x16;
    //private final static byte SUBPARAM_ENHANCED_VMN_ACK                 = 0x17;

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
            super(Time.TIMEZONE_UTC);
        }

        public static TimeStamp fromByteArray(byte[] data) {
            TimeStamp ts = new TimeStamp();
            // C.S0015-B v2.0, 4.5.4: range is 1996-2095
            int year = IccUtils.beBcdByteToInt(data[0]);
            if (year > 99 || year < 0) return null;
            ts.year = year >= 96 ? year + 1900 : year + 2000;
            int month = IccUtils.beBcdByteToInt(data[1]);
            if (month < 1 || month > 12) return null;
            ts.month = month - 1;
            int day = IccUtils.beBcdByteToInt(data[2]);
            if (day < 1 || day > 31) return null;
            ts.monthDay = day;
            int hour = IccUtils.beBcdByteToInt(data[3]);
            if (hour < 0 || hour > 23) return null;
            ts.hour = hour;
            int minute = IccUtils.beBcdByteToInt(data[4]);
            if (minute < 0 || minute > 59) return null;
            ts.minute = minute;
            int second = IccUtils.beBcdByteToInt(data[5]);
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

    private static class CodingException extends Exception {
        public CodingException(String s) {
            super(s);
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("BearerData ");
        builder.append("{ messageType=" + messageType);
        builder.append(", messageId=" + (int)messageId);
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
        builder.append(", callbackNumber=" + callbackNumber);
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
     * @param force ignore (but still count) illegal characters if true
     * @return septet count, or -1 on failure
     */
    public static TextEncodingDetails calcTextEncodingDetails(CharSequence msg,
            boolean force7BitEncoding) {
        TextEncodingDetails ted;
        int septets = countAsciiSeptets(msg, force7BitEncoding);
        if (septets != -1 && septets <= SmsMessage.MAX_USER_DATA_SEPTETS) {
            ted = new TextEncodingDetails();
            ted.msgCount = 1;
            ted.codeUnitCount = septets;
            ted.codeUnitsRemaining = SmsMessage.MAX_USER_DATA_SEPTETS - septets;
            ted.codeUnitSize = SmsMessage.ENCODING_7BIT;
        } else {
            ted = com.android.internal.telephony.gsm.SmsMessage.calculateLength(
                    msg, force7BitEncoding);
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

    private static int calcUdhSeptetPadding(int userDataHeaderLen) {
        int udhBits = userDataHeaderLen * 8;
        int udhSeptets = (udhBits + 6) / 7;
        int paddingBits = (udhSeptets * 7) - udhBits;
        return paddingBits;
    }

    private static byte[] encode7bitGsm(String msg, int paddingBits)
        throws CodingException
    {
        try {
            /*
             * TODO(cleanup): It would be nice if GsmAlphabet provided
             * an option to produce just the data without prepending
             * the length.
             */
            byte []fullData = GsmAlphabet.stringToGsm7BitPacked(msg, 0, -1, paddingBits, true);
            byte []data = new byte[fullData.length - 1];
            System.arraycopy(fullData, 1, data, 0, fullData.length - 1);
            return data;
        } catch (com.android.internal.telephony.EncodeException ex) {
            throw new CodingException("7bit GSM encode failed: " + ex);
        }
    }

    private static void encodeUserDataPayload(UserData uData)
        throws CodingException
    {
        // TODO(cleanup): UDH can only occur in EMS mode, meaning
        // encapsulation of GSM encoding, and so the logic here should
        // be refactored to more cleanly reflect this constraint.

        byte[] headerData = null;
        if (uData.userDataHeader != null) headerData = SmsHeader.toByteArray(uData.userDataHeader);
        int headerDataLen = (headerData == null) ? 0 : headerData.length + 1;  // + length octet

        byte[] payloadData;
        int codeUnitCount;
        if (uData.msgEncodingSet) {
            if (uData.msgEncoding == UserData.ENCODING_OCTET) {
                if (uData.payload == null) {
                    Log.e(LOG_TAG, "user data with octet encoding but null payload");
                    payloadData = new byte[0];
                    codeUnitCount = 0;
                } else {
                    payloadData = uData.payload;
                    codeUnitCount = uData.payload.length;
                }
            } else {
                if (uData.payloadStr == null) {
                    Log.e(LOG_TAG, "non-octet user data with null payloadStr");
                    uData.payloadStr = "";
                }
                if (uData.msgEncoding == UserData.ENCODING_GSM_7BIT_ALPHABET) {
                    int paddingBits = calcUdhSeptetPadding(headerDataLen);
                    payloadData = encode7bitGsm(uData.payloadStr, paddingBits);
                    codeUnitCount = ((payloadData.length + headerDataLen) * 8) / 7;
                } else if (uData.msgEncoding == UserData.ENCODING_7BIT_ASCII) {
                    payloadData = encode7bitAscii(uData.payloadStr, true);
                    codeUnitCount = uData.payloadStr.length();
                } else if (uData.msgEncoding == UserData.ENCODING_UNICODE_16) {
                    payloadData = encodeUtf16(uData.payloadStr);
                    codeUnitCount = uData.payloadStr.length();
                } else {
                    throw new CodingException("unsupported user data encoding (" +
                                              uData.msgEncoding + ")");
                }
            }
        } else {
            if (uData.payloadStr == null) {
                Log.e(LOG_TAG, "user data with null payloadStr");
                uData.payloadStr = "";
            }
            try {
                if (headerData == null) {
                    payloadData = encode7bitAscii(uData.payloadStr, false);
                    codeUnitCount = uData.payloadStr.length();
                    uData.msgEncoding = UserData.ENCODING_7BIT_ASCII;
                } else {
                    // If there is a header, we are in EMS mode, in
                    // which case we use GSM encodings.
                    int paddingBits = calcUdhSeptetPadding(headerDataLen);
                    payloadData = encode7bitGsm(uData.payloadStr, paddingBits);
                    codeUnitCount = ((payloadData.length + headerDataLen) * 8) / 7;
                    uData.msgEncoding = UserData.ENCODING_GSM_7BIT_ALPHABET;
                }
            } catch (CodingException ex) {
                payloadData = encodeUtf16(uData.payloadStr);
                codeUnitCount = uData.payloadStr.length();
                uData.msgEncoding = UserData.ENCODING_UNICODE_16;
            }
            uData.msgEncodingSet = true;
        }

        int totalLength = payloadData.length + headerDataLen;
        if (totalLength > SmsMessage.MAX_USER_DATA_BYTES) {
            throw new CodingException("encoded user data too large (" + totalLength +
                                      " > " + SmsMessage.MAX_USER_DATA_BYTES + " bytes)");
        }

        uData.numFields = codeUnitCount;
        uData.payload = new byte[totalLength];
        if (headerData != null) {
            uData.payload[0] = (byte)headerData.length;
            System.arraycopy(headerData, 0, uData.payload, 1, headerData.length);
        }
        System.arraycopy(payloadData, 0, uData.payload, headerDataLen, payloadData.length);
    }

    private static void encodeUserData(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException, CodingException
    {
        encodeUserDataPayload(bData.userData);
        /**
         * XXX/TODO: figure out what the right answer is WRT padding bits
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

    /**
     * Create serialized representation for BearerData object.
     * (See 3GPP2 C.R1001-F, v1.0, section 4.5 for layout details)
     *
     * @param bearerData an instance of BearerData.
     *
     * @return data byta array of raw encoded SMS bearer data.
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
            return outStream.toByteArray();
        } catch (BitwiseOutputStream.AccessException ex) {
            Log.e(LOG_TAG, "BearerData encode failed: " + ex);
        } catch (CodingException ex) {
            Log.e(LOG_TAG, "BearerData encode failed: " + ex);
        }
        return null;
   }

    private static void decodeMessageId(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 3) {
            throw new CodingException("MESSAGE_IDENTIFIER subparam size incorrect");
        }
        bData.messageType = inStream.read(4);
        bData.messageId = inStream.read(8) << 8;
        bData.messageId |= inStream.read(8);
        bData.hasUserDataHeader = (inStream.read(1) == 1);
        inStream.skip(3);
    }

    private static void decodeUserData(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException
    {
        int paramBytes = inStream.read(8);
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
        int dataBits = (paramBytes * 8) - consumedBits;
        bData.userData.payload = inStream.readByteArray(dataBits);
    }

    private static String decodeUtf16(byte[] data, int offset, int numFields)
        throws CodingException
    {
        try {
            return new String(data, offset, numFields * 2, "utf-16be");
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new CodingException("UTF-16 decode failed: " + ex);
        }
    }

    private static String decodeIa5(byte[] data, int offset, int numFields)
        throws CodingException
    {
        try {
            offset *= 8;
            StringBuffer strBuf = new StringBuffer(numFields);
            BitwiseInputStream inStream = new BitwiseInputStream(data);
            int wantedBits = (offset * 8) + (numFields * 7);
            if (inStream.available() < wantedBits) {
                throw new CodingException("insufficient data (wanted " + wantedBits +
                                          " bits, but only have " + inStream.available() + ")");
            }
            inStream.skip(offset);
            for (int i = 0; i < numFields; i++) {
                int charCode = inStream.read(7);
                if ((charCode < UserData.IA5_MAP_BASE_INDEX) ||
                        (charCode > UserData.IA5_MAP_MAX_INDEX)) {
                    throw new CodingException("unsupported AI5 character code (" + charCode + ")");
                }
                strBuf.append(UserData.IA5_MAP[charCode - UserData.IA5_MAP_BASE_INDEX]);
            }
            return strBuf.toString();
        } catch (BitwiseInputStream.AccessException ex) {
            throw new CodingException("AI5 decode failed: " + ex);
        }
    }

    private static String decode7bitAscii(byte[] data, int offset, int numFields)
        throws CodingException
    {
        try {
            offset *= 8;
            BitwiseInputStream inStream = new BitwiseInputStream(data);
            int wantedBits = offset + (numFields * 7);
            if (inStream.available() < wantedBits) {
                throw new CodingException("insufficient data (wanted " + wantedBits +
                                          " bits, but only have " + inStream.available() + ")");
            }
            inStream.skip(offset);
            byte[] expandedData = new byte[numFields];
            for (int i = 0; i < numFields; i++) {
                expandedData[i] = (byte)inStream.read(7);
            }
            return new String(expandedData, 0, numFields, "US-ASCII");
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new CodingException("7bit ASCII decode failed: " + ex);
        } catch (BitwiseInputStream.AccessException ex) {
            throw new CodingException("7bit ASCII decode failed: " + ex);
        }
    }

    private static String decode7bitGsm(byte[] data, int offset, int numFields)
        throws CodingException
    {
        int paddingBits = calcUdhSeptetPadding(offset);
        numFields -= (((offset * 8) + paddingBits) / 7);
        // TODO: It seems wrong that only Gsm7 bit encodings would
        // take into account the header in numFields calculations.
        // This should be verified.
        String result = GsmAlphabet.gsm7BitPackedToString(data, offset, numFields, paddingBits);
        if (result == null) {
            throw new CodingException("7bit GSM decoding failed");
        }
        return result;
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
            break;
        case UserData.ENCODING_7BIT_ASCII:
            userData.payloadStr = decode7bitAscii(userData.payload, offset, userData.numFields);
            break;
        case UserData.ENCODING_IA5:
            userData.payloadStr = decodeIa5(userData.payload, offset, userData.numFields);
            break;
        case UserData.ENCODING_UNICODE_16:
            userData.payloadStr = decodeUtf16(userData.payload, offset, userData.numFields);
            break;
        case UserData.ENCODING_GSM_7BIT_ALPHABET:
            userData.payloadStr = decode7bitGsm(userData.payload, offset, userData.numFields);
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
                strbuf.append(UserData.IA5_MAP[inStream.read(6)]);
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
        if ((dataLen > 14) || (dataLen < numFields)) {
            throw new CodingException("IS-91 voicemail status decoding failed");
        }
        StringBuffer strbuf = new StringBuffer(dataLen);
        for (int i = 0; i < numFields; i++) {
            strbuf.append(UserData.IA5_MAP[inStream.read(6)]);
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

    private static void decodeReplyOption(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        int paramBytes = inStream.read(8);
        if (paramBytes != 1) {
            throw new CodingException("REPLY_OPTION subparam size incorrect");
        }
        bData.userAckReq     = (inStream.read(1) == 1);
        bData.deliveryAckReq = (inStream.read(1) == 1);
        bData.readAckReq     = (inStream.read(1) == 1);
        bData.reportReq      = (inStream.read(1) == 1);
        inStream.skip(4);
    }

    private static void decodeMsgCount(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 1) {
            throw new CodingException("NUMBER_OF_MESSAGES subparam size incorrect");
        }
        bData.numberOfMessages = inStream.read(8);
    }

    private static void decodeDepositIndex(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 2) {
            throw new CodingException("MESSAGE_DEPOSIT_INDEX subparam size incorrect");
        }
        bData.depositIndex = (inStream.read(8) << 8) | inStream.read(8);
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

    private static void decodeCallbackNumber(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        int paramBytes = inStream.read(8);
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
        int remainingBits = (paramBytes * 8) - consumedBits;
        int dataBits = addr.numberOfDigits * fieldBits;
        int paddingBits = remainingBits - dataBits;
        if (remainingBits < dataBits) {
            throw new CodingException("CALLBACK_NUMBER subparam encoding size error (" +
                                      "remainingBits " + remainingBits + ", dataBits " +
                                      dataBits + ", paddingBits " + paddingBits + ")");
        }
        addr.origBytes = inStream.readByteArray(dataBits);
        inStream.skip(paddingBits);
        decodeSmsAddress(addr);
        bData.callbackNumber = addr;
    }

    private static void decodeMsgStatus(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 1) {
            throw new CodingException("MESSAGE_STATUS subparam size incorrect");
        }
        bData.errorClass = inStream.read(2);
        bData.messageStatus = inStream.read(6);
        bData.messageStatusSet = true;
    }

    private static void decodeMsgCenterTimeStamp(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 6) {
            throw new CodingException("MESSAGE_CENTER_TIME_STAMP subparam size incorrect");
        }
        bData.msgCenterTimeStamp = TimeStamp.fromByteArray(inStream.readByteArray(6 * 8));
    }

    private static void decodeValidityAbs(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 6) {
            throw new CodingException("VALIDITY_PERIOD_ABSOLUTE subparam size incorrect");
        }
        bData.validityPeriodAbsolute = TimeStamp.fromByteArray(inStream.readByteArray(6 * 8));
    }

    private static void decodeDeferredDeliveryAbs(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 6) {
            throw new CodingException("DEFERRED_DELIVERY_TIME_ABSOLUTE subparam size incorrect");
        }
        bData.deferredDeliveryTimeAbsolute = TimeStamp.fromByteArray(inStream.readByteArray(6 * 8));
    }

    private static void decodeValidityRel(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 1) {
            throw new CodingException("VALIDITY_PERIOD_RELATIVE subparam size incorrect");
        }
        bData.deferredDeliveryTimeRelative = inStream.read(8);
        bData.deferredDeliveryTimeRelativeSet = true;
    }

    private static void decodeDeferredDeliveryRel(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 1) {
            throw new CodingException("DEFERRED_DELIVERY_TIME_RELATIVE subparam size incorrect");
        }
        bData.validityPeriodRelative = inStream.read(8);
        bData.validityPeriodRelativeSet = true;
    }

    private static void decodePrivacyIndicator(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 1) {
            throw new CodingException("PRIVACY_INDICATOR subparam size incorrect");
        }
        bData.privacy = inStream.read(2);
        inStream.skip(6);
        bData.privacyIndicatorSet = true;
    }

    private static void decodeLanguageIndicator(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 1) {
            throw new CodingException("LANGUAGE_INDICATOR subparam size incorrect");
        }
        bData.language = inStream.read(8);
        bData.languageIndicatorSet = true;
    }

    private static void decodeDisplayMode(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 1) {
            throw new CodingException("DISPLAY_MODE subparam size incorrect");
        }
        bData.displayMode = inStream.read(2);
        inStream.skip(6);
        bData.displayModeSet = true;
    }

    private static void decodePriorityIndicator(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 1) {
            throw new CodingException("PRIORITY_INDICATOR subparam size incorrect");
        }
        bData.priority = inStream.read(2);
        inStream.skip(6);
        bData.priorityIndicatorSet = true;
    }

    private static void decodeMsgDeliveryAlert(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 1) {
            throw new CodingException("ALERT_ON_MESSAGE_DELIVERY subparam size incorrect");
        }
        bData.alert = inStream.read(2);
        inStream.skip(6);
        bData.alertIndicatorSet = true;
    }

    private static void decodeUserResponseCode(BearerData bData, BitwiseInputStream inStream)
        throws BitwiseInputStream.AccessException, CodingException
    {
        if (inStream.read(8) != 1) {
            throw new CodingException("USER_REPONSE_CODE subparam size incorrect");
        }
        bData.userResponseCode = inStream.read(8);
        bData.userResponseCodeSet = true;
    }

    /**
     * Create BearerData object from serialized representation.
     * (See 3GPP2 C.R1001-F, v1.0, section 4.5 for layout details)
     *
     * @param smsData byte array of raw encoded SMS bearer data.
     *
     * @return an instance of BearerData.
     */
    public static BearerData decode(byte[] smsData) {
        try {
            BitwiseInputStream inStream = new BitwiseInputStream(smsData);
            BearerData bData = new BearerData();
            int foundSubparamMask = 0;
            while (inStream.available() > 0) {
                int subparamId = inStream.read(8);
                int subparamIdBit = 1 << subparamId;
                if ((foundSubparamMask & subparamIdBit) != 0) {
                    throw new CodingException("illegal duplicate subparameter (" +
                                              subparamId + ")");
                }
                foundSubparamMask |= subparamIdBit;
                switch (subparamId) {
                case SUBPARAM_MESSAGE_IDENTIFIER:
                    decodeMessageId(bData, inStream);
                    break;
                case SUBPARAM_USER_DATA:
                    decodeUserData(bData, inStream);
                    break;
                case SUBPARAM_USER_REPONSE_CODE:
                    decodeUserResponseCode(bData, inStream);
                    break;
                case SUBPARAM_REPLY_OPTION:
                    decodeReplyOption(bData, inStream);
                    break;
                case SUBPARAM_NUMBER_OF_MESSAGES:
                    decodeMsgCount(bData, inStream);
                    break;
                case SUBPARAM_CALLBACK_NUMBER:
                    decodeCallbackNumber(bData, inStream);
                    break;
                case SUBPARAM_MESSAGE_STATUS:
                    decodeMsgStatus(bData, inStream);
                    break;
                case SUBPARAM_MESSAGE_CENTER_TIME_STAMP:
                    decodeMsgCenterTimeStamp(bData, inStream);
                    break;
                case SUBPARAM_VALIDITY_PERIOD_ABSOLUTE:
                    decodeValidityAbs(bData, inStream);
                    break;
                case SUBPARAM_VALIDITY_PERIOD_RELATIVE:
                    decodeValidityRel(bData, inStream);
                    break;
                case SUBPARAM_DEFERRED_DELIVERY_TIME_ABSOLUTE:
                    decodeDeferredDeliveryAbs(bData, inStream);
                    break;
                case SUBPARAM_DEFERRED_DELIVERY_TIME_RELATIVE:
                    decodeDeferredDeliveryRel(bData, inStream);
                    break;
                case SUBPARAM_PRIVACY_INDICATOR:
                    decodePrivacyIndicator(bData, inStream);
                    break;
                case SUBPARAM_LANGUAGE_INDICATOR:
                    decodeLanguageIndicator(bData, inStream);
                    break;
                case SUBPARAM_MESSAGE_DISPLAY_MODE:
                    decodeDisplayMode(bData, inStream);
                    break;
                case SUBPARAM_PRIORITY_INDICATOR:
                    decodePriorityIndicator(bData, inStream);
                    break;
                case SUBPARAM_ALERT_ON_MESSAGE_DELIVERY:
                    decodeMsgDeliveryAlert(bData, inStream);
                    break;
                case SUBPARAM_MESSAGE_DEPOSIT_INDEX:
                    decodeDepositIndex(bData, inStream);
                    break;
                default:
                    throw new CodingException("unsupported bearer data subparameter ("
                                              + subparamId + ")");
                }
            }
            if ((foundSubparamMask & (1 << SUBPARAM_MESSAGE_IDENTIFIER)) == 0) {
                throw new CodingException("missing MESSAGE_IDENTIFIER subparam");
            }
            if (bData.userData != null) {
                if (bData.userData.msgEncoding == UserData.ENCODING_IS91_EXTENDED_PROTOCOL) {
                    if ((foundSubparamMask ^
                             (1 << SUBPARAM_MESSAGE_IDENTIFIER) ^
                             (1 << SUBPARAM_USER_DATA))
                            != 0) {
                        Log.e(LOG_TAG, "IS-91 must occur without extra subparams (" +
                              foundSubparamMask + ")");
                    }
                    decodeIs91(bData);
                } else {
                    decodeUserDataPayload(bData.userData, bData.hasUserDataHeader);
                }
            }
            return bData;
        } catch (BitwiseInputStream.AccessException ex) {
            Log.e(LOG_TAG, "BearerData decode failed: " + ex);
        } catch (CodingException ex) {
            Log.e(LOG_TAG, "BearerData decode failed: " + ex);
        }
        return null;
    }
}
