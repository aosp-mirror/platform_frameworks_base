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

package com.android.internal.telephony.gsm;

import android.telephony.PhoneNumberUtils;
import android.text.format.Time;
import android.telephony.Rlog;
import android.content.res.Resources;
import android.text.TextUtils;

import com.android.internal.telephony.EncodeException;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.GsmAlphabet.TextEncodingDetails;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsMessageBase;
import com.android.internal.telephony.Sms7BitEncodingTranslator;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;

import static com.android.internal.telephony.SmsConstants.MessageClass;
import static com.android.internal.telephony.SmsConstants.ENCODING_UNKNOWN;
import static com.android.internal.telephony.SmsConstants.ENCODING_7BIT;
import static com.android.internal.telephony.SmsConstants.ENCODING_8BIT;
import static com.android.internal.telephony.SmsConstants.ENCODING_16BIT;
import static com.android.internal.telephony.SmsConstants.ENCODING_KSC5601;
import static com.android.internal.telephony.SmsConstants.MAX_USER_DATA_SEPTETS;
import static com.android.internal.telephony.SmsConstants.MAX_USER_DATA_BYTES;
import static com.android.internal.telephony.SmsConstants.MAX_USER_DATA_BYTES_WITH_HEADER;

/**
 * A Short Message Service message.
 *
 */
public class SmsMessage extends SmsMessageBase {
    static final String LOG_TAG = "SmsMessage";
    private static final boolean VDBG = false;

    private MessageClass messageClass;

    /**
     * TP-Message-Type-Indicator
     * 9.2.3
     */
    private int mMti;

    /** TP-Protocol-Identifier (TP-PID) */
    private int mProtocolIdentifier;

    // TP-Data-Coding-Scheme
    // see TS 23.038
    private int mDataCodingScheme;

    // TP-Reply-Path
    // e.g. 23.040 9.2.2.1
    private boolean mReplyPathPresent = false;

    /** The address of the receiver. */
    private GsmSmsAddress mRecipientAddress;

    /**
     *  TP-Status - status of a previously submitted SMS.
     *  This field applies to SMS-STATUS-REPORT messages.  0 indicates success;
     *  see TS 23.040, 9.2.3.15 for description of other possible values.
     */
    private int mStatus;

    /**
     *  TP-Status - status of a previously submitted SMS.
     *  This field is true iff the message is a SMS-STATUS-REPORT message.
     */
    private boolean mIsStatusReportMessage = false;

    private int mVoiceMailCount = 0;

    private static final int VALIDITY_PERIOD_FORMAT_NONE = 0x00;
    private static final int VALIDITY_PERIOD_FORMAT_ENHANCED = 0x01;
    private static final int VALIDITY_PERIOD_FORMAT_RELATIVE = 0x02;
    private static final int VALIDITY_PERIOD_FORMAT_ABSOLUTE = 0x03;

    //Validity Period min - 5 mins
    private static final int VALIDITY_PERIOD_MIN = 5;
    //Validity Period max - 63 weeks
    private static final int VALIDITY_PERIOD_MAX = 635040;

    private static final int INVALID_VALIDITY_PERIOD = -1;

    public static class SubmitPdu extends SubmitPduBase {
    }

    /**
     * Create an SmsMessage from a raw PDU.
     */
    public static SmsMessage createFromPdu(byte[] pdu) {
        try {
            SmsMessage msg = new SmsMessage();
            msg.parsePdu(pdu);
            return msg;
        } catch (RuntimeException ex) {
            Rlog.e(LOG_TAG, "SMS PDU parsing failed: ", ex);
            return null;
        } catch (OutOfMemoryError e) {
            Rlog.e(LOG_TAG, "SMS PDU parsing failed with out of memory: ", e);
            return null;
        }
    }

    /**
     * 3GPP TS 23.040 9.2.3.9 specifies that Type Zero messages are indicated
     * by TP_PID field set to value 0x40
     */
    public boolean isTypeZero() {
        return (mProtocolIdentifier == 0x40);
    }

    /**
     * TS 27.005 3.4.1 lines[0] and lines[1] are the two lines read from the
     * +CMT unsolicited response (PDU mode, of course)
     *  +CMT: [&lt;alpha>],<length><CR><LF><pdu>
     *
     * Only public for debugging
     *
     * {@hide}
     */
    public static SmsMessage newFromCMT(byte[] pdu) {
        try {
            SmsMessage msg = new SmsMessage();
            msg.parsePdu(pdu);
            return msg;
        } catch (RuntimeException ex) {
            Rlog.e(LOG_TAG, "SMS PDU parsing failed: ", ex);
            return null;
        }
    }

    /** @hide */
    public static SmsMessage newFromCDS(byte[] pdu) {
        try {
            SmsMessage msg = new SmsMessage();
            msg.parsePdu(pdu);
            return msg;
        } catch (RuntimeException ex) {
            Rlog.e(LOG_TAG, "CDS SMS PDU parsing failed: ", ex);
            return null;
        }
    }

    /**
     * Create an SmsMessage from an SMS EF record.
     *
     * @param index Index of SMS record. This should be index in ArrayList
     *              returned by SmsManager.getAllMessagesFromSim + 1.
     * @param data Record data.
     * @return An SmsMessage representing the record.
     *
     * @hide
     */
    public static SmsMessage createFromEfRecord(int index, byte[] data) {
        try {
            SmsMessage msg = new SmsMessage();

            msg.mIndexOnIcc = index;

            // First byte is status: RECEIVED_READ, RECEIVED_UNREAD, STORED_SENT,
            // or STORED_UNSENT
            // See TS 51.011 10.5.3
            if ((data[0] & 1) == 0) {
                Rlog.w(LOG_TAG,
                        "SMS parsing failed: Trying to parse a free record");
                return null;
            } else {
                msg.mStatusOnIcc = data[0] & 0x07;
            }

            int size = data.length - 1;

            // Note: Data may include trailing FF's.  That's OK; message
            // should still parse correctly.
            byte[] pdu = new byte[size];
            System.arraycopy(data, 1, pdu, 0, size);
            msg.parsePdu(pdu);
            return msg;
        } catch (RuntimeException ex) {
            Rlog.e(LOG_TAG, "SMS PDU parsing failed: ", ex);
            return null;
        }
    }

    /**
     * Get the TP-Layer-Length for the given SMS-SUBMIT PDU Basically, the
     * length in bytes (not hex chars) less the SMSC header
     */
    public static int getTPLayerLengthForPDU(String pdu) {
        int len = pdu.length() / 2;
        int smscLen = Integer.parseInt(pdu.substring(0, 2), 16);

        return len - smscLen - 1;
    }

    /**
     * Get Encoded Relative Validty Period Value from Validity period in mins.
     *
     * @param validityPeriod Validity period in mins.
     *
     * Refer specification 3GPP TS 23.040 V6.8.1 section 9.2.3.12.1.
     * ||relValidityPeriod (TP-VP)  ||                 ||  validityPeriod   ||
     *
     *      0 to 143                            --->       (TP-VP + 1) x 5 minutes
     *
     *      144 to 167                         --->        12 hours + ((TP-VP -143) x 30 minutes)
     *
     *      168 to 196                         --->        (TP-VP - 166) x 1 day
     *
     *      197 to 255                         --->        (TP-VP - 192) x 1 week
     *
     * @return relValidityPeriod Encoded Relative Validity Period Value.
     * @hide
     */
    public static int getRelativeValidityPeriod(int validityPeriod) {
        int relValidityPeriod = INVALID_VALIDITY_PERIOD;

        if (validityPeriod < VALIDITY_PERIOD_MIN  || validityPeriod > VALIDITY_PERIOD_MAX) {
            Rlog.e(LOG_TAG,"Invalid Validity Period" + validityPeriod);
            return relValidityPeriod;
        }

        if (validityPeriod <= 720) {
            relValidityPeriod = (validityPeriod  / 5) - 1;
        } else if (validityPeriod <= 1440) {
            relValidityPeriod = ((validityPeriod - 720) / 30) + 143;
        } else if (validityPeriod <= 43200) {
            relValidityPeriod = (validityPeriod  / 1440) + 166;
        } else if (validityPeriod <= 635040) {
            relValidityPeriod = (validityPeriod  / 10080) + 192;
        }
        return relValidityPeriod;
    }

    /**
     * Get an SMS-SUBMIT PDU for a destination address and a message
     *
     * @param scAddress Service Centre address.  Null means use default.
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     * @hide
     */
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destinationAddress, String message,
            boolean statusReportRequested, byte[] header) {
        return getSubmitPdu(scAddress, destinationAddress, message, statusReportRequested, header,
                ENCODING_UNKNOWN, 0, 0);
    }


    /**
     * Get an SMS-SUBMIT PDU for a destination address and a message using the
     * specified encoding.
     *
     * @param scAddress Service Centre address.  Null means use default.
     * @param encoding Encoding defined by constants in
     *        com.android.internal.telephony.SmsConstants.ENCODING_*
     * @param languageTable
     * @param languageShiftTable
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     * @hide
     */
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destinationAddress, String message,
            boolean statusReportRequested, byte[] header, int encoding,
            int languageTable, int languageShiftTable) {
        return getSubmitPdu(scAddress, destinationAddress, message, statusReportRequested,
            header, encoding, languageTable, languageShiftTable, -1);
    }

    /**
     * Get an SMS-SUBMIT PDU for a destination address and a message using the
     * specified encoding.
     *
     * @param scAddress Service Centre address.  Null means use default.
     * @param encoding Encoding defined by constants in
     *        com.android.internal.telephony.SmsConstants.ENCODING_*
     * @param languageTable
     * @param languageShiftTable
     * @param validityPeriod Validity Period of the message in Minutes.
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     * @hide
     */
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destinationAddress, String message,
            boolean statusReportRequested, byte[] header, int encoding,
            int languageTable, int languageShiftTable, int validityPeriod) {

        // Perform null parameter checks.
        if (message == null || destinationAddress == null) {
            return null;
        }

        if (encoding == ENCODING_UNKNOWN) {
            // Find the best encoding to use
            TextEncodingDetails ted = calculateLength(message, false);
            encoding = ted.codeUnitSize;
            languageTable = ted.languageTable;
            languageShiftTable = ted.languageShiftTable;

            if (encoding == ENCODING_7BIT &&
                    (languageTable != 0 || languageShiftTable != 0)) {
                if (header != null) {
                    SmsHeader smsHeader = SmsHeader.fromByteArray(header);
                    if (smsHeader.languageTable != languageTable
                            || smsHeader.languageShiftTable != languageShiftTable) {
                        Rlog.w(LOG_TAG, "Updating language table in SMS header: "
                                + smsHeader.languageTable + " -> " + languageTable + ", "
                                + smsHeader.languageShiftTable + " -> " + languageShiftTable);
                        smsHeader.languageTable = languageTable;
                        smsHeader.languageShiftTable = languageShiftTable;
                        header = SmsHeader.toByteArray(smsHeader);
                    }
                } else {
                    SmsHeader smsHeader = new SmsHeader();
                    smsHeader.languageTable = languageTable;
                    smsHeader.languageShiftTable = languageShiftTable;
                    header = SmsHeader.toByteArray(smsHeader);
                }
            }
        }

        SubmitPdu ret = new SubmitPdu();

        int validityPeriodFormat = VALIDITY_PERIOD_FORMAT_NONE;
        int relativeValidityPeriod = INVALID_VALIDITY_PERIOD;

        // TP-Validity-Period-Format (TP-VPF) in 3GPP TS 23.040 V6.8.1 section 9.2.3.3
        //bit 4:3 = 10 - TP-VP field present - relative format
        if((relativeValidityPeriod = getRelativeValidityPeriod(validityPeriod)) >= 0) {
            validityPeriodFormat = VALIDITY_PERIOD_FORMAT_RELATIVE;
        }

        byte mtiByte = (byte)(0x01 | (validityPeriodFormat << 0x03) |
                (header != null ? 0x40 : 0x00));

        ByteArrayOutputStream bo = getSubmitPduHead(
                scAddress, destinationAddress, mtiByte,
                statusReportRequested, ret);

        // Skip encoding pdu if error occurs when create pdu head and the error will be handled
        // properly later on encodedMessage sanity check.
        if (bo == null) return ret;

        // User Data (and length)
        byte[] userData;
        try {
            if (encoding == ENCODING_7BIT) {
                userData = GsmAlphabet.stringToGsm7BitPackedWithHeader(message, header,
                        languageTable, languageShiftTable);
            } else { //assume UCS-2
                try {
                    userData = encodeUCS2(message, header);
                } catch(UnsupportedEncodingException uex) {
                    Rlog.e(LOG_TAG,
                            "Implausible UnsupportedEncodingException ",
                            uex);
                    return null;
                }
            }
        } catch (EncodeException ex) {
            // Encoding to the 7-bit alphabet failed. Let's see if we can
            // send it as a UCS-2 encoded message
            try {
                userData = encodeUCS2(message, header);
                encoding = ENCODING_16BIT;
            } catch(UnsupportedEncodingException uex) {
                Rlog.e(LOG_TAG,
                        "Implausible UnsupportedEncodingException ",
                        uex);
                return null;
            }
        }

        if (encoding == ENCODING_7BIT) {
            if ((0xff & userData[0]) > MAX_USER_DATA_SEPTETS) {
                // Message too long
                Rlog.e(LOG_TAG, "Message too long (" + (0xff & userData[0]) + " septets)");
                return null;
            }
            // TP-Data-Coding-Scheme
            // Default encoding, uncompressed
            // To test writing messages to the SIM card, change this value 0x00
            // to 0x12, which means "bits 1 and 0 contain message class, and the
            // class is 2". Note that this takes effect for the sender. In other
            // words, messages sent by the phone with this change will end up on
            // the receiver's SIM card. You can then send messages to yourself
            // (on a phone with this change) and they'll end up on the SIM card.
            bo.write(0x00);
        } else { // assume UCS-2
            if ((0xff & userData[0]) > MAX_USER_DATA_BYTES) {
                // Message too long
                Rlog.e(LOG_TAG, "Message too long (" + (0xff & userData[0]) + " bytes)");
                return null;
            }
            // TP-Data-Coding-Scheme
            // UCS-2 encoding, uncompressed
            bo.write(0x08);
        }

        if (validityPeriodFormat == VALIDITY_PERIOD_FORMAT_RELATIVE) {
            // ( TP-Validity-Period - relative format)
            bo.write(relativeValidityPeriod);
        }

        bo.write(userData, 0, userData.length);
        ret.encodedMessage = bo.toByteArray();
        return ret;
    }

    /**
     * Packs header and UCS-2 encoded message. Includes TP-UDL & TP-UDHL if necessary
     *
     * @return encoded message as UCS2
     * @throws UnsupportedEncodingException
     */
    private static byte[] encodeUCS2(String message, byte[] header)
        throws UnsupportedEncodingException {
        byte[] userData, textPart;
        textPart = message.getBytes("utf-16be");

        if (header != null) {
            // Need 1 byte for UDHL
            userData = new byte[header.length + textPart.length + 1];

            userData[0] = (byte)header.length;
            System.arraycopy(header, 0, userData, 1, header.length);
            System.arraycopy(textPart, 0, userData, header.length + 1, textPart.length);
        }
        else {
            userData = textPart;
        }
        byte[] ret = new byte[userData.length+1];
        ret[0] = (byte) (userData.length & 0xff );
        System.arraycopy(userData, 0, ret, 1, userData.length);
        return ret;
    }

    /**
     * Get an SMS-SUBMIT PDU for a destination address and a message
     *
     * @param scAddress Service Centre address.  Null means use default.
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     */
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destinationAddress, String message,
            boolean statusReportRequested) {

        return getSubmitPdu(scAddress, destinationAddress, message, statusReportRequested, null);
    }

    /**
     * Get an SMS-SUBMIT PDU for a destination address and a message
     *
     * @param scAddress Service Centre address.  Null means use default.
     * @param destinationAddress the address of the destination for the message
     * @param statusReportRequested staus report of the message Requested
     * @param validityPeriod Validity Period of the message in Minutes.
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     */
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destinationAddress, String message,
            boolean statusReportRequested, int validityPeriod) {
        return getSubmitPdu(scAddress, destinationAddress, message, statusReportRequested,
                null, ENCODING_UNKNOWN, 0, 0, validityPeriod);
    }

    /**
     * Get an SMS-SUBMIT PDU for a data message to a destination address &amp; port
     *
     * @param scAddress Service Centre address. null == use default
     * @param destinationAddress the address of the destination for the message
     * @param destinationPort the port to deliver the message to at the
     *        destination
     * @param data the data for the message
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     */
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destinationAddress, int destinationPort, byte[] data,
            boolean statusReportRequested) {

        SmsHeader.PortAddrs portAddrs = new SmsHeader.PortAddrs();
        portAddrs.destPort = destinationPort;
        portAddrs.origPort = 0;
        portAddrs.areEightBits = false;

        SmsHeader smsHeader = new SmsHeader();
        smsHeader.portAddrs = portAddrs;

        byte[] smsHeaderData = SmsHeader.toByteArray(smsHeader);

        if ((data.length + smsHeaderData.length + 1) > MAX_USER_DATA_BYTES) {
            Rlog.e(LOG_TAG, "SMS data message may only contain "
                    + (MAX_USER_DATA_BYTES - smsHeaderData.length - 1) + " bytes");
            return null;
        }

        SubmitPdu ret = new SubmitPdu();
        ByteArrayOutputStream bo = getSubmitPduHead(
                scAddress, destinationAddress, (byte) 0x41, // MTI = SMS-SUBMIT,
                                                            // TP-UDHI = true
                statusReportRequested, ret);
        // Skip encoding pdu if error occurs when create pdu head and the error will be handled
        // properly later on encodedMessage sanity check.
        if (bo == null) return ret;

        // TP-Data-Coding-Scheme
        // No class, 8 bit data
        bo.write(0x04);

        // (no TP-Validity-Period)

        // Total size
        bo.write(data.length + smsHeaderData.length + 1);

        // User data header
        bo.write(smsHeaderData.length);
        bo.write(smsHeaderData, 0, smsHeaderData.length);

        // User data
        bo.write(data, 0, data.length);

        ret.encodedMessage = bo.toByteArray();
        return ret;
    }

    /**
     * Create the beginning of a SUBMIT PDU.  This is the part of the
     * SUBMIT PDU that is common to the two versions of {@link #getSubmitPdu},
     * one of which takes a byte array and the other of which takes a
     * <code>String</code>.
     *
     * @param scAddress Service Centre address. null == use default
     * @param destinationAddress the address of the destination for the message
     * @param mtiByte
     * @param ret <code>SubmitPdu</code> containing the encoded SC
     *        address, if applicable, and the encoded message. Returns null on encode error.
     */
    private static ByteArrayOutputStream getSubmitPduHead(
            String scAddress, String destinationAddress, byte mtiByte,
            boolean statusReportRequested, SubmitPdu ret) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(
                MAX_USER_DATA_BYTES + 40);

        // SMSC address with length octet, or 0
        if (scAddress == null) {
            ret.encodedScAddress = null;
        } else {
            ret.encodedScAddress = PhoneNumberUtils.networkPortionToCalledPartyBCDWithLength(
                    scAddress);
        }

        // TP-Message-Type-Indicator (and friends)
        if (statusReportRequested) {
            // Set TP-Status-Report-Request bit.
            mtiByte |= 0x20;
            if (VDBG) Rlog.d(LOG_TAG, "SMS status report requested");
        }
        bo.write(mtiByte);

        // space for TP-Message-Reference
        bo.write(0);

        byte[] daBytes;

        daBytes = PhoneNumberUtils.networkPortionToCalledPartyBCD(destinationAddress);

        // return empty pduHead for invalid destination address
        if (daBytes == null) return null;

        // destination address length in BCD digits, ignoring TON byte and pad
        // TODO Should be better.
        bo.write((daBytes.length - 1) * 2
                - ((daBytes[daBytes.length - 1] & 0xf0) == 0xf0 ? 1 : 0));

        // destination address
        bo.write(daBytes, 0, daBytes.length);

        // TP-Protocol-Identifier
        bo.write(0);
        return bo;
    }

    private static class PduParser {
        byte mPdu[];
        int mCur;
        SmsHeader mUserDataHeader;
        byte[] mUserData;
        int mUserDataSeptetPadding;

        PduParser(byte[] pdu) {
            mPdu = pdu;
            mCur = 0;
            mUserDataSeptetPadding = 0;
        }

        /**
         * Parse and return the SC address prepended to SMS messages coming via
         * the TS 27.005 / AT interface.  Returns null on invalid address
         */
        String getSCAddress() {
            int len;
            String ret;

            // length of SC Address
            len = getByte();

            if (len == 0) {
                // no SC address
                ret = null;
            } else {
                // SC address
                try {
                    ret = PhoneNumberUtils.calledPartyBCDToString(
                            mPdu, mCur, len, PhoneNumberUtils.BCD_EXTENDED_TYPE_CALLED_PARTY);
                } catch (RuntimeException tr) {
                    Rlog.d(LOG_TAG, "invalid SC address: ", tr);
                    ret = null;
                }
            }

            mCur += len;

            return ret;
        }

        /**
         * returns non-sign-extended byte value
         */
        int getByte() {
            return mPdu[mCur++] & 0xff;
        }

        /**
         * Any address except the SC address (eg, originating address) See TS
         * 23.040 9.1.2.5
         */
        GsmSmsAddress getAddress() {
            GsmSmsAddress ret;

            // "The Address-Length field is an integer representation of
            // the number field, i.e. excludes any semi-octet containing only
            // fill bits."
            // The TOA field is not included as part of this
            int addressLength = mPdu[mCur] & 0xff;
            int lengthBytes = 2 + (addressLength + 1) / 2;

            try {
                ret = new GsmSmsAddress(mPdu, mCur, lengthBytes);
            } catch (ParseException e) {
                ret = null;
                //This is caught by createFromPdu(byte[] pdu)
                throw new RuntimeException(e.getMessage());
            }

            mCur += lengthBytes;

            return ret;
        }

        /**
         * Parses an SC timestamp and returns a currentTimeMillis()-style
         * timestamp
         */

        long getSCTimestampMillis() {
            // TP-Service-Centre-Time-Stamp
            int year = IccUtils.gsmBcdByteToInt(mPdu[mCur++]);
            int month = IccUtils.gsmBcdByteToInt(mPdu[mCur++]);
            int day = IccUtils.gsmBcdByteToInt(mPdu[mCur++]);
            int hour = IccUtils.gsmBcdByteToInt(mPdu[mCur++]);
            int minute = IccUtils.gsmBcdByteToInt(mPdu[mCur++]);
            int second = IccUtils.gsmBcdByteToInt(mPdu[mCur++]);

            // For the timezone, the most significant bit of the
            // least significant nibble is the sign byte
            // (meaning the max range of this field is 79 quarter-hours,
            // which is more than enough)

            byte tzByte = mPdu[mCur++];

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
         * Pulls the user data out of the PDU, and separates the payload from
         * the header if there is one.
         *
         * @param hasUserDataHeader true if there is a user data header
         * @param dataInSeptets true if the data payload is in septets instead
         *  of octets
         * @return the number of septets or octets in the user data payload
         */
        int constructUserData(boolean hasUserDataHeader, boolean dataInSeptets) {
            int offset = mCur;
            int userDataLength = mPdu[offset++] & 0xff;
            int headerSeptets = 0;
            int userDataHeaderLength = 0;

            if (hasUserDataHeader) {
                userDataHeaderLength = mPdu[offset++] & 0xff;

                byte[] udh = new byte[userDataHeaderLength];
                System.arraycopy(mPdu, offset, udh, 0, userDataHeaderLength);
                mUserDataHeader = SmsHeader.fromByteArray(udh);
                offset += userDataHeaderLength;

                int headerBits = (userDataHeaderLength + 1) * 8;
                headerSeptets = headerBits / 7;
                headerSeptets += (headerBits % 7) > 0 ? 1 : 0;
                mUserDataSeptetPadding = (headerSeptets * 7) - headerBits;
            }

            int bufferLen;
            if (dataInSeptets) {
                /*
                 * Here we just create the user data length to be the remainder of
                 * the pdu minus the user data header, since userDataLength means
                 * the number of uncompressed septets.
                 */
                bufferLen = mPdu.length - offset;
            } else {
                /*
                 * userDataLength is the count of octets, so just subtract the
                 * user data header.
                 */
                bufferLen = userDataLength - (hasUserDataHeader ? (userDataHeaderLength + 1) : 0);
                if (bufferLen < 0) {
                    bufferLen = 0;
                }
            }

            mUserData = new byte[bufferLen];
            System.arraycopy(mPdu, offset, mUserData, 0, mUserData.length);
            mCur = offset;

            if (dataInSeptets) {
                // Return the number of septets
                int count = userDataLength - headerSeptets;
                // If count < 0, return 0 (means UDL was probably incorrect)
                return count < 0 ? 0 : count;
            } else {
                // Return the number of octets
                return mUserData.length;
            }
        }

        /**
         * Returns the user data payload, not including the headers
         *
         * @return the user data payload, not including the headers
         */
        byte[] getUserData() {
            return mUserData;
        }

        /**
         * Returns an object representing the user data headers
         *
         * {@hide}
         */
        SmsHeader getUserDataHeader() {
            return mUserDataHeader;
        }

        /**
         * Interprets the user data payload as packed GSM 7bit characters, and
         * decodes them into a String.
         *
         * @param septetCount the number of septets in the user data payload
         * @return a String with the decoded characters
         */
        String getUserDataGSM7Bit(int septetCount, int languageTable,
                int languageShiftTable) {
            String ret;

            ret = GsmAlphabet.gsm7BitPackedToString(mPdu, mCur, septetCount,
                    mUserDataSeptetPadding, languageTable, languageShiftTable);

            mCur += (septetCount * 7) / 8;

            return ret;
        }

        /**
         * Interprets the user data payload as pack GSM 8-bit (a GSM alphabet string that's
         * stored in 8-bit unpacked format) characters, and decodes them into a String.
         *
         * @param byteCount the number of byest in the user data payload
         * @return a String with the decoded characters
         */
        String getUserDataGSM8bit(int byteCount) {
            String ret;

            ret = GsmAlphabet.gsm8BitUnpackedToString(mPdu, mCur, byteCount);

            mCur += byteCount;

            return ret;
        }

        /**
         * Interprets the user data payload as UCS2 characters, and
         * decodes them into a String.
         *
         * @param byteCount the number of bytes in the user data payload
         * @return a String with the decoded characters
         */
        String getUserDataUCS2(int byteCount) {
            String ret;

            try {
                ret = new String(mPdu, mCur, byteCount, "utf-16");
            } catch (UnsupportedEncodingException ex) {
                ret = "";
                Rlog.e(LOG_TAG, "implausible UnsupportedEncodingException", ex);
            }

            mCur += byteCount;
            return ret;
        }

        /**
         * Interprets the user data payload as KSC-5601 characters, and
         * decodes them into a String.
         *
         * @param byteCount the number of bytes in the user data payload
         * @return a String with the decoded characters
         */
        String getUserDataKSC5601(int byteCount) {
            String ret;

            try {
                ret = new String(mPdu, mCur, byteCount, "KSC5601");
            } catch (UnsupportedEncodingException ex) {
                ret = "";
                Rlog.e(LOG_TAG, "implausible UnsupportedEncodingException", ex);
            }

            mCur += byteCount;
            return ret;
        }

        boolean moreDataPresent() {
            return (mPdu.length > mCur);
        }
    }

    /**
     * Calculates the number of SMS's required to encode the message body and
     * the number of characters remaining until the next message.
     *
     * @param msgBody the message to encode
     * @param use7bitOnly ignore (but still count) illegal characters if true
     * @return TextEncodingDetails
     */
    public static TextEncodingDetails calculateLength(CharSequence msgBody,
            boolean use7bitOnly) {
        CharSequence newMsgBody = null;
        Resources r = Resources.getSystem();
        if (r.getBoolean(com.android.internal.R.bool.config_sms_force_7bit_encoding)) {
            newMsgBody  = Sms7BitEncodingTranslator.translate(msgBody);
        }
        if (TextUtils.isEmpty(newMsgBody)) {
            newMsgBody = msgBody;
        }
        TextEncodingDetails ted = GsmAlphabet.countGsmSeptets(newMsgBody, use7bitOnly);
        if (ted == null) {
            return SmsMessageBase.calcUnicodeEncodingDetails(newMsgBody);
        }
        return ted;
    }

    /** {@inheritDoc} */
    @Override
    public int getProtocolIdentifier() {
        return mProtocolIdentifier;
    }

    /**
     * Returns the TP-Data-Coding-Scheme byte, for acknowledgement of SMS-PP download messages.
     * @return the TP-DCS field of the SMS header
     */
    int getDataCodingScheme() {
        return mDataCodingScheme;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReplace() {
        return (mProtocolIdentifier & 0xc0) == 0x40
                && (mProtocolIdentifier & 0x3f) > 0
                && (mProtocolIdentifier & 0x3f) < 8;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isCphsMwiMessage() {
        return ((GsmSmsAddress) mOriginatingAddress).isCphsVoiceMessageClear()
                || ((GsmSmsAddress) mOriginatingAddress).isCphsVoiceMessageSet();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isMWIClearMessage() {
        if (mIsMwi && !mMwiSense) {
            return true;
        }

        return mOriginatingAddress != null
                && ((GsmSmsAddress) mOriginatingAddress).isCphsVoiceMessageClear();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isMWISetMessage() {
        if (mIsMwi && mMwiSense) {
            return true;
        }

        return mOriginatingAddress != null
                && ((GsmSmsAddress) mOriginatingAddress).isCphsVoiceMessageSet();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isMwiDontStore() {
        if (mIsMwi && mMwiDontStore) {
            return true;
        }

        if (isCphsMwiMessage()) {
            // See CPHS 4.2 Section B.4.2.1
            // If the user data is a single space char, do not store
            // the message. Otherwise, store and display as usual
            if (" ".equals(getMessageBody())) {
                return true;
            }
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override
    public int getStatus() {
        return mStatus;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isStatusReportMessage() {
        return mIsStatusReportMessage;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReplyPathPresent() {
        return mReplyPathPresent;
    }

    /**
     * TS 27.005 3.1, &lt;pdu&gt; definition "In the case of SMS: 3GPP TS 24.011 [6]
     * SC address followed by 3GPP TS 23.040 [3] TPDU in hexadecimal format:
     * ME/TA converts each octet of TP data unit into two IRA character long
     * hex number (e.g. octet with integer value 42 is presented to TE as two
     * characters 2A (IRA 50 and 65))" ...in the case of cell broadcast,
     * something else...
     */
    private void parsePdu(byte[] pdu) {
        mPdu = pdu;
        // Rlog.d(LOG_TAG, "raw sms message:");
        // Rlog.d(LOG_TAG, s);

        PduParser p = new PduParser(pdu);

        mScAddress = p.getSCAddress();

        if (mScAddress != null) {
            if (VDBG) Rlog.d(LOG_TAG, "SMS SC address: " + mScAddress);
        }

        // TODO(mkf) support reply path, user data header indicator

        // TP-Message-Type-Indicator
        // 9.2.3
        int firstByte = p.getByte();

        mMti = firstByte & 0x3;
        switch (mMti) {
        // TP-Message-Type-Indicator
        // 9.2.3
        case 0:
        case 3: //GSM 03.40 9.2.3.1: MTI == 3 is Reserved.
                //This should be processed in the same way as MTI == 0 (Deliver)
            parseSmsDeliver(p, firstByte);
            break;
        case 1:
            parseSmsSubmit(p, firstByte);
            break;
        case 2:
            parseSmsStatusReport(p, firstByte);
            break;
        default:
            // TODO(mkf) the rest of these
            throw new RuntimeException("Unsupported message type");
        }
    }

    /**
     * Parses a SMS-STATUS-REPORT message.
     *
     * @param p A PduParser, cued past the first byte.
     * @param firstByte The first byte of the PDU, which contains MTI, etc.
     */
    private void parseSmsStatusReport(PduParser p, int firstByte) {
        mIsStatusReportMessage = true;

        // TP-Message-Reference
        mMessageRef = p.getByte();
        // TP-Recipient-Address
        mRecipientAddress = p.getAddress();
        // TP-Service-Centre-Time-Stamp
        mScTimeMillis = p.getSCTimestampMillis();
        p.getSCTimestampMillis();
        // TP-Status
        mStatus = p.getByte();

        // The following are optional fields that may or may not be present.
        if (p.moreDataPresent()) {
            // TP-Parameter-Indicator
            int extraParams = p.getByte();
            int moreExtraParams = extraParams;
            while ((moreExtraParams & 0x80) != 0) {
                // We only know how to parse a few extra parameters, all
                // indicated in the first TP-PI octet, so skip over any
                // additional TP-PI octets.
                moreExtraParams = p.getByte();
            }
            // As per 3GPP 23.040 section 9.2.3.27 TP-Parameter-Indicator,
            // only process the byte if the reserved bits (bits3 to 6) are zero.
            if ((extraParams & 0x78) == 0) {
                // TP-Protocol-Identifier
                if ((extraParams & 0x01) != 0) {
                    mProtocolIdentifier = p.getByte();
                }
                // TP-Data-Coding-Scheme
                if ((extraParams & 0x02) != 0) {
                    mDataCodingScheme = p.getByte();
                }
                // TP-User-Data-Length (implies existence of TP-User-Data)
                if ((extraParams & 0x04) != 0) {
                    boolean hasUserDataHeader = (firstByte & 0x40) == 0x40;
                    parseUserData(p, hasUserDataHeader);
                }
            }
        }
    }

    private void parseSmsDeliver(PduParser p, int firstByte) {
        mReplyPathPresent = (firstByte & 0x80) == 0x80;

        mOriginatingAddress = p.getAddress();

        if (mOriginatingAddress != null) {
            if (VDBG) Rlog.v(LOG_TAG, "SMS originating address: "
                    + mOriginatingAddress.address);
        }

        // TP-Protocol-Identifier (TP-PID)
        // TS 23.040 9.2.3.9
        mProtocolIdentifier = p.getByte();

        // TP-Data-Coding-Scheme
        // see TS 23.038
        mDataCodingScheme = p.getByte();

        if (VDBG) {
            Rlog.v(LOG_TAG, "SMS TP-PID:" + mProtocolIdentifier
                    + " data coding scheme: " + mDataCodingScheme);
        }

        mScTimeMillis = p.getSCTimestampMillis();

        if (VDBG) Rlog.d(LOG_TAG, "SMS SC timestamp: " + mScTimeMillis);

        boolean hasUserDataHeader = (firstByte & 0x40) == 0x40;

        parseUserData(p, hasUserDataHeader);
    }

    /**
     * Parses a SMS-SUBMIT message.
     *
     * @param p A PduParser, cued past the first byte.
     * @param firstByte The first byte of the PDU, which contains MTI, etc.
     */
    private void parseSmsSubmit(PduParser p, int firstByte) {
        mReplyPathPresent = (firstByte & 0x80) == 0x80;

        // TP-MR (TP-Message Reference)
        mMessageRef = p.getByte();

        mRecipientAddress = p.getAddress();

        if (mRecipientAddress != null) {
            if (VDBG) Rlog.v(LOG_TAG, "SMS recipient address: " + mRecipientAddress.address);
        }

        // TP-Protocol-Identifier (TP-PID)
        // TS 23.040 9.2.3.9
        mProtocolIdentifier = p.getByte();

        // TP-Data-Coding-Scheme
        // see TS 23.038
        mDataCodingScheme = p.getByte();

        if (VDBG) {
            Rlog.v(LOG_TAG, "SMS TP-PID:" + mProtocolIdentifier
                    + " data coding scheme: " + mDataCodingScheme);
        }

        // TP-Validity-Period-Format
        int validityPeriodLength = 0;
        int validityPeriodFormat = ((firstByte>>3) & 0x3);
        if (0x0 == validityPeriodFormat) /* 00, TP-VP field not present*/
        {
            validityPeriodLength = 0;
        }
        else if (0x2 == validityPeriodFormat) /* 10, TP-VP: relative format*/
        {
            validityPeriodLength = 1;
        }
        else /* other case, 11 or 01, TP-VP: absolute or enhanced format*/
        {
            validityPeriodLength = 7;
        }

        // TP-Validity-Period is not used on phone, so just ignore it for now.
        while (validityPeriodLength-- > 0)
        {
            p.getByte();
        }

        boolean hasUserDataHeader = (firstByte & 0x40) == 0x40;

        parseUserData(p, hasUserDataHeader);
    }

    /**
     * Parses the User Data of an SMS.
     *
     * @param p The current PduParser.
     * @param hasUserDataHeader Indicates whether a header is present in the
     *                          User Data.
     */
    private void parseUserData(PduParser p, boolean hasUserDataHeader) {
        boolean hasMessageClass = false;
        boolean userDataCompressed = false;

        int encodingType = ENCODING_UNKNOWN;

        // Look up the data encoding scheme
        if ((mDataCodingScheme & 0x80) == 0) {
            userDataCompressed = (0 != (mDataCodingScheme & 0x20));
            hasMessageClass = (0 != (mDataCodingScheme & 0x10));

            if (userDataCompressed) {
                Rlog.w(LOG_TAG, "4 - Unsupported SMS data coding scheme "
                        + "(compression) " + (mDataCodingScheme & 0xff));
            } else {
                switch ((mDataCodingScheme >> 2) & 0x3) {
                case 0: // GSM 7 bit default alphabet
                    encodingType = ENCODING_7BIT;
                    break;

                case 2: // UCS 2 (16bit)
                    encodingType = ENCODING_16BIT;
                    break;

                case 1: // 8 bit data
                    //Support decoding the user data payload as pack GSM 8-bit (a GSM alphabet string
                    //that's stored in 8-bit unpacked format) characters.
                    Resources r = Resources.getSystem();
                    if (r.getBoolean(com.android.internal.
                            R.bool.config_sms_decode_gsm_8bit_data)) {
                        encodingType = ENCODING_8BIT;
                        break;
                    }

                case 3: // reserved
                    Rlog.w(LOG_TAG, "1 - Unsupported SMS data coding scheme "
                            + (mDataCodingScheme & 0xff));
                    encodingType = ENCODING_8BIT;
                    break;
                }
            }
        } else if ((mDataCodingScheme & 0xf0) == 0xf0) {
            hasMessageClass = true;
            userDataCompressed = false;

            if (0 == (mDataCodingScheme & 0x04)) {
                // GSM 7 bit default alphabet
                encodingType = ENCODING_7BIT;
            } else {
                // 8 bit data
                encodingType = ENCODING_8BIT;
            }
        } else if ((mDataCodingScheme & 0xF0) == 0xC0
                || (mDataCodingScheme & 0xF0) == 0xD0
                || (mDataCodingScheme & 0xF0) == 0xE0) {
            // 3GPP TS 23.038 V7.0.0 (2006-03) section 4

            // 0xC0 == 7 bit, don't store
            // 0xD0 == 7 bit, store
            // 0xE0 == UCS-2, store

            if ((mDataCodingScheme & 0xF0) == 0xE0) {
                encodingType = ENCODING_16BIT;
            } else {
                encodingType = ENCODING_7BIT;
            }

            userDataCompressed = false;
            boolean active = ((mDataCodingScheme & 0x08) == 0x08);
            // bit 0x04 reserved

            // VM - If TP-UDH is present, these values will be overwritten
            if ((mDataCodingScheme & 0x03) == 0x00) {
                mIsMwi = true; /* Indicates vmail */
                mMwiSense = active;/* Indicates vmail notification set/clear */
                mMwiDontStore = ((mDataCodingScheme & 0xF0) == 0xC0);

                /* Set voice mail count based on notification bit */
                if (active == true) {
                    mVoiceMailCount = -1; // unknown number of messages waiting
                } else {
                    mVoiceMailCount = 0; // no unread messages
                }

                Rlog.w(LOG_TAG, "MWI in DCS for Vmail. DCS = "
                        + (mDataCodingScheme & 0xff) + " Dont store = "
                        + mMwiDontStore + " vmail count = " + mVoiceMailCount);

            } else {
                mIsMwi = false;
                Rlog.w(LOG_TAG, "MWI in DCS for fax/email/other: "
                        + (mDataCodingScheme & 0xff));
            }
        } else if ((mDataCodingScheme & 0xC0) == 0x80) {
            // 3GPP TS 23.038 V7.0.0 (2006-03) section 4
            // 0x80..0xBF == Reserved coding groups
            if (mDataCodingScheme == 0x84) {
                // This value used for KSC5601 by carriers in Korea.
                encodingType = ENCODING_KSC5601;
            } else {
                Rlog.w(LOG_TAG, "5 - Unsupported SMS data coding scheme "
                        + (mDataCodingScheme & 0xff));
            }
        } else {
            Rlog.w(LOG_TAG, "3 - Unsupported SMS data coding scheme "
                    + (mDataCodingScheme & 0xff));
        }

        // set both the user data and the user data header.
        int count = p.constructUserData(hasUserDataHeader,
                encodingType == ENCODING_7BIT);
        this.mUserData = p.getUserData();
        this.mUserDataHeader = p.getUserDataHeader();

        /*
         * Look for voice mail indication in TP_UDH TS23.040 9.2.3.24
         * ieid = 1 (0x1) (SPECIAL_SMS_MSG_IND)
         * ieidl =2 octets
         * ieda msg_ind_type = 0x00 (voice mail; discard sms )or
         *                   = 0x80 (voice mail; store sms)
         * msg_count = 0x00 ..0xFF
         */
        if (hasUserDataHeader && (mUserDataHeader.specialSmsMsgList.size() != 0)) {
            for (SmsHeader.SpecialSmsMsg msg : mUserDataHeader.specialSmsMsgList) {
                int msgInd = msg.msgIndType & 0xff;
                /*
                 * TS 23.040 V6.8.1 Sec 9.2.3.24.2
                 * bits 1 0 : basic message indication type
                 * bits 4 3 2 : extended message indication type
                 * bits 6 5 : Profile id bit 7 storage type
                 */
                if ((msgInd == 0) || (msgInd == 0x80)) {
                    mIsMwi = true;
                    if (msgInd == 0x80) {
                        /* Store message because TP_UDH indicates so*/
                        mMwiDontStore = false;
                    } else if (mMwiDontStore == false) {
                        /* Storage bit is not set by TP_UDH
                         * Check for conflict
                         * between message storage bit in TP_UDH
                         * & DCS. The message shall be stored if either of
                         * the one indicates so.
                         * TS 23.040 V6.8.1 Sec 9.2.3.24.2
                         */
                        if (!((((mDataCodingScheme & 0xF0) == 0xD0)
                               || ((mDataCodingScheme & 0xF0) == 0xE0))
                               && ((mDataCodingScheme & 0x03) == 0x00))) {
                            /* Even DCS did not have voice mail with Storage bit
                             * 3GPP TS 23.038 V7.0.0 section 4
                             * So clear this flag*/
                            mMwiDontStore = true;
                        }
                    }

                    mVoiceMailCount = msg.msgCount & 0xff;

                    /*
                     * In the event of a conflict between message count setting
                     * and DCS then the Message Count in the TP-UDH shall
                     * override the indication in the TP-DCS. Set voice mail
                     * notification based on count in TP-UDH
                     */
                    if (mVoiceMailCount > 0)
                        mMwiSense = true;
                    else
                        mMwiSense = false;

                    Rlog.w(LOG_TAG, "MWI in TP-UDH for Vmail. Msg Ind = " + msgInd
                            + " Dont store = " + mMwiDontStore + " Vmail count = "
                            + mVoiceMailCount);

                    /*
                     * There can be only one IE for each type of message
                     * indication in TP_UDH. In the event they are duplicated
                     * last occurence will be used. Hence the for loop
                     */
                } else {
                    Rlog.w(LOG_TAG, "TP_UDH fax/email/"
                            + "extended msg/multisubscriber profile. Msg Ind = " + msgInd);
                }
            } // end of for
        } // end of if UDH

        switch (encodingType) {
        case ENCODING_UNKNOWN:
            mMessageBody = null;
            break;

        case ENCODING_8BIT:
            //Support decoding the user data payload as pack GSM 8-bit (a GSM alphabet string
            //that's stored in 8-bit unpacked format) characters.
            Resources r = Resources.getSystem();
            if (r.getBoolean(com.android.internal.
                    R.bool.config_sms_decode_gsm_8bit_data)) {
                mMessageBody = p.getUserDataGSM8bit(count);
            } else {
                mMessageBody = null;
            }
            break;

        case ENCODING_7BIT:
            mMessageBody = p.getUserDataGSM7Bit(count,
                    hasUserDataHeader ? mUserDataHeader.languageTable : 0,
                    hasUserDataHeader ? mUserDataHeader.languageShiftTable : 0);
            break;

        case ENCODING_16BIT:
            mMessageBody = p.getUserDataUCS2(count);
            break;

        case ENCODING_KSC5601:
            mMessageBody = p.getUserDataKSC5601(count);
            break;
        }

        if (VDBG) Rlog.v(LOG_TAG, "SMS message body (raw): '" + mMessageBody + "'");

        if (mMessageBody != null) {
            parseMessageBody();
        }

        if (!hasMessageClass) {
            messageClass = MessageClass.UNKNOWN;
        } else {
            switch (mDataCodingScheme & 0x3) {
            case 0:
                messageClass = MessageClass.CLASS_0;
                break;
            case 1:
                messageClass = MessageClass.CLASS_1;
                break;
            case 2:
                messageClass = MessageClass.CLASS_2;
                break;
            case 3:
                messageClass = MessageClass.CLASS_3;
                break;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageClass getMessageClass() {
        return messageClass;
    }

    /**
     * Returns true if this is a (U)SIM data download type SM.
     * See 3GPP TS 31.111 section 9.1 and TS 23.040 section 9.2.3.9.
     *
     * @return true if this is a USIM data download message; false otherwise
     */
    boolean isUsimDataDownload() {
        return messageClass == MessageClass.CLASS_2 &&
                (mProtocolIdentifier == 0x7f || mProtocolIdentifier == 0x7c);
    }

    public int getNumOfVoicemails() {
        /*
         * Order of priority if multiple indications are present is 1.UDH,
         *      2.DCS, 3.CPHS.
         * Voice mail count if voice mail present indication is
         * received
         *  1. UDH (or both UDH & DCS): mVoiceMailCount = 0 to 0xff. Ref[TS 23. 040]
         *  2. DCS only: count is unknown mVoiceMailCount= -1
         *  3. CPHS only: count is unknown mVoiceMailCount = 0xff. Ref[GSM-BTR-1-4700]
         * Voice mail clear, mVoiceMailCount = 0.
         */
        if ((!mIsMwi) && isCphsMwiMessage()) {
            if (mOriginatingAddress != null
                    && ((GsmSmsAddress) mOriginatingAddress).isCphsVoiceMessageSet()) {
                mVoiceMailCount = 0xff;
            } else {
                mVoiceMailCount = 0;
            }
            Rlog.v(LOG_TAG, "CPHS voice mail message");
        }
        return mVoiceMailCount;
    }
}
