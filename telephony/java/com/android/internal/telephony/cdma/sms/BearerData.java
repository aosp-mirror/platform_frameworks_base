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

public final class BearerData{

    // For completeness the following fields are listed, though not used yet.
    /**
     * Supported priority modes for CDMA SMS messages
     * (See 3GPP2 C.S0015-B, v2.0, table 4.5.9-1)
     */
    //public static final int PRIORITY_NORMAL        = 0x0;
    //public static final int PRIORITY_INTERACTIVE   = 0x1;
    //public static final int PRIORITY_URGENT        = 0x2;
    //public static final int PRIORITY_EMERGENCY     = 0x3;

    /**
     * Supported privacy modes for CDMA SMS messages
     * (See 3GPP2 C.S0015-B, v2.0, table 4.5.10-1)
     */
    //public static final int PRIVACY_NOT_RESTRICTED = 0x0;
    //public static final int PRIVACY_RESTRICTED     = 0x1;
    //public static final int PRIVACY_CONFIDENTIAL   = 0x2;
    //public static final int PRIVACY_SECRET         = 0x3;

    /**
     * Supported alert modes for CDMA SMS messages
     * (See 3GPP2 C.S0015-B, v2.0, table 4.5.13-1)
     */
    //public static final int ALERT_DEFAULT          = 0x0;
    //public static final int ALERT_LOW_PRIO         = 0x1;
    //public static final int ALERT_MEDIUM_PRIO      = 0x2;
    //public static final int ALERT_HIGH_PRIO        = 0x3;

    /**
     * Supported display modes for CDMA SMS messages
     * (See 3GPP2 C.S0015-B, v2.0, table 4.5.16-1)
     */
    public static final int DISPLAY_IMMEDIATE      = 0x0;
    public static final int DISPLAY_DEFAULT        = 0x1;
    public static final int DISPLAY_USER           = 0x2;

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

    /**
     * SMS Message Status Codes
     * (See 3GPP2 C.S0015-B, v2.0, table 4.5.21-1)
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

    /** Bit-mask indicating used fields for SmsDataCoding */
    public int mask;

    /**
     * 4-bit value indicating the message type in accordance to
     *      table 4.5.1-1
     * (See 3GPP2 C.S0015-B, v2, 4.5.1)
     */
    public byte messageType;

    /**
     * 16-bit value indicating the message ID, which increments modulo 65536.
     * (Special rules apply for WAP-messages.)
     * (See 3GPP2 C.S0015-B, v2, 4.5.1)
     */
    public int messageID;

    /**
     * 1-bit value that indicates whether a User Data Header is present.
     * (See 3GPP2 C.S0015-B, v2, 4.5.1)
     */
    public boolean hasUserDataHeader;

    /**
     * provides the information for the user data
     * (e.g. padding bits, user data, user data header, etc)
     * (See 3GPP2 C.S.0015-B, v2, 4.5.2)
     */
    public UserData userData;

    //public UserResponseCode userResponseCode;

    /**
     * 6-byte-field, see 3GPP2 C.S0015-B, v2, 4.5.4
     * year, month, day, hours, minutes, seconds;
     */
    public byte[] timeStamp;

    //public SmsTime validityPeriodAbsolute;
    //public SmsRelTime validityPeriodRelative;
    //public SmsTime deferredDeliveryTimeAbsolute;
    //public SmsRelTime deferredDeliveryTimeRelative;
    //public byte priority;
    //public byte privacy;

    /**
     * Reply Option
     * 1-bit values which indicate whether SMS acknowledgment is requested or not.
     * (See 3GPP2 C.S0015-B, v2, 4.5.11)
     */
    public boolean userAckReq;
    public boolean deliveryAckReq;
    public boolean readAckReq;
    public boolean reportReq;

    /**
     * The number of Messages element (8-bit value) is a decimal number in the 0 to 99 range
     * representing the number of messages stored at the Voice Mail System. This element is
     * used by the Voice Mail Notification service.
     * (See 3GPP2 C.S0015-B, v2, 4.5.12)
     */
    public int numberOfMessages;

    //public int alert;
    //public int language;

    /**
     * 4-bit or 8-bit value that indicates the number to be dialed in reply to a
     * received SMS message.
     * (See 3GPP2 C.S0015-B, v2, 4.5.15)
     */
    public CdmaSmsAddress callbackNumber;

    /**
     * 2-bit value that is used to indicate to the mobile station when to display
     * the received message.
     * (See 3GPP2 C.S0015-B, v2, 4.5.16)
     */
    public byte displayMode = DISPLAY_DEFAULT;

    /**
     * First component of the Message status, that indicates if an error has occurred
     * and whether the error is considered permanent or temporary.
     * (See 3GPP2 C.S0015-B, v2, 4.5.21)
     */
    public int errorClass = ERROR_UNDEFINED;

    /**
     * Second component of the Message status, that indicates if an error has occurred
     * and the cause of the error.
     * (See 3GPP2 C.S0015-B, v2, 4.5.21)
     */
    public int messageStatus = STATUS_UNDEFINED;

}

