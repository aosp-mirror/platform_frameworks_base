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

package android.telephony.gsm;

import android.telephony.PhoneNumberUtils;
import android.util.Config;
import android.util.Log;
import android.telephony.PhoneNumberUtils;
import android.text.format.Time;

import com.android.internal.telephony.gsm.EncodeException;
import com.android.internal.telephony.gsm.GsmAlphabet;
import com.android.internal.telephony.gsm.SimUtils;
import com.android.internal.telephony.gsm.SmsHeader;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

class SmsAddress {
    // From TS 23.040 9.1.2.5 and TS 24.008 table 10.5.118
    static final int TON_UNKNOWN = 0;

    static final int TON_INTERNATIONAL = 1;

    static final int TON_NATIONAL = 2;

    static final int TON_NETWORK = 3;

    static final int TON_SUBSCRIBER = 4;

    static final int TON_ALPHANUMERIC = 5;

    static final int TON_APPREVIATED = 6;

    static final int OFFSET_ADDRESS_LENGTH = 0;

    static final int OFFSET_TOA = 1;

    static final int OFFSET_ADDRESS_VALUE = 2;

    int ton;

    String address;

    byte[] origBytes;

    /**
     * New SmsAddress from TS 23.040 9.1.2.5 Address Field
     *
     * @param offset the offset of the Address-Length byte
     * @param length the length in bytes rounded up, e.g. "2 +
     *        (addressLength + 1) / 2"
     */

    SmsAddress(byte[] data, int offset, int length) {
        origBytes = new byte[length];
        System.arraycopy(data, offset, origBytes, 0, length);

        // addressLength is the count of semi-octets, not bytes
        int addressLength = origBytes[OFFSET_ADDRESS_LENGTH] & 0xff;

        int toa = origBytes[OFFSET_TOA] & 0xff;
        ton = 0x7 & (toa >> 4);

        // TOA must have its high bit set
        if ((toa & 0x80) != 0x80) {
            throw new RuntimeException("Invalid TOA - high bit must be set");
        }

        if (isAlphanumeric()) {
            // An alphanumeric address
            int countSeptets = addressLength * 4 / 7;

            address = GsmAlphabet.gsm7BitPackedToString(origBytes,
                    OFFSET_ADDRESS_VALUE, countSeptets);
        } else {
            // TS 23.040 9.1.2.5 says
            // that "the MS shall interpret reserved values as 'Unknown'
            // but shall store them exactly as received"

            byte lastByte = origBytes[length - 1];

            if ((addressLength & 1) == 1) {
                // Make sure the final unused BCD digit is 0xf
                origBytes[length - 1] |= 0xf0;
            }
            address = PhoneNumberUtils.calledPartyBCDToString(origBytes,
                    OFFSET_TOA, length - OFFSET_TOA);

            // And restore origBytes
            origBytes[length - 1] = lastByte;
        }
    }

    public String getAddressString() {
        return address;
    }

    /**
     * Returns true if this is an alphanumeric addres
     */
    public boolean isAlphanumeric() {
        return ton == TON_ALPHANUMERIC;
    }

    public boolean isNetworkSpecific() {
        return ton == TON_NETWORK;
    }

    /**
     * Returns true of this is a valid CPHS voice message waiting indicator
     * address
     */
    public boolean isCphsVoiceMessageIndicatorAddress() {
        // CPHS-style MWI message
        // See CPHS 4.7 B.4.2.1
        //
        // Basically:
        //
        // - Originating address should be 4 bytes long and alphanumeric
        // - Decode will result with two chars:
        // - Char 1
        // 76543210
        // ^ set/clear indicator (0 = clear)
        // ^^^ type of indicator (000 = voice)
        // ^^^^ must be equal to 0001
        // - Char 2:
        // 76543210
        // ^ line number (0 = line 1)
        // ^^^^^^^ set to 0
        //
        // Remember, since the alpha address is stored in 7-bit compact form,
        // the "line number" is really the top bit of the first address value
        // byte

        return (origBytes[OFFSET_ADDRESS_LENGTH] & 0xff) == 4
                && isAlphanumeric() && (origBytes[OFFSET_TOA] & 0x0f) == 0;
    }

    /**
     * Returns true if this is a valid CPHS voice message waiting indicator
     * address indicating a "set" of "indicator 1" of type "voice message
     * waiting"
     */
    public boolean isCphsVoiceMessageSet() {
        // 0x11 means "set" "voice message waiting" "indicator 1"
        return isCphsVoiceMessageIndicatorAddress()
                && (origBytes[OFFSET_ADDRESS_VALUE] & 0xff) == 0x11;

    }

    /**
     * Returns true if this is a valid CPHS voice message waiting indicator
     * address indicationg a "clear" of "indicator 1" of type "voice message
     * waiting"
     */
    public boolean isCphsVoiceMessageClear() {
        // 0x10 means "clear" "voice message waiting" "indicator 1"
        return isCphsVoiceMessageIndicatorAddress()
                && (origBytes[OFFSET_ADDRESS_VALUE] & 0xff) == 0x10;

    }

    public boolean couldBeEmailGateway() {
        // Some carriers seems to send email gateway messages in this form:
        // from: an UNKNOWN TON, 3 or 4 digits long, beginning with a 5
        // PID: 0x00, Data coding scheme 0x03
        // So we just attempt to treat any message from an address length <= 4
        // as an email gateway

        return address.length() <= 4;
    }

}

/**
 * A Short Message Service message.
 *
 */
public class SmsMessage {
    static final String LOG_TAG = "GSM";

    /**
     * SMS Class enumeration.
     * See TS 23.038.
     *
     */
    public enum MessageClass {
        UNKNOWN, CLASS_0, CLASS_1, CLASS_2, CLASS_3;
    }

    /** Unknown encoding scheme (see TS 23.038) */
    public static final int ENCODING_UNKNOWN = 0;
    /** 7-bit encoding scheme (see TS 23.038) */
    public static final int ENCODING_7BIT = 1;
    /** 8-bit encoding scheme (see TS 23.038) */
    public static final int ENCODING_8BIT = 2;
    /** 16-bit encoding scheme (see TS 23.038) */
    public static final int ENCODING_16BIT = 3;

    /** The maximum number of payload bytes per message */
    public static final int MAX_USER_DATA_BYTES = 140;

    /**
     * The maximum number of payload bytes per message if a user data header
     * is present.  This assumes the header only contains the
     * CONCATENATED_8_BIT_REFERENCE element.
     * 
     * @hide pending API Council approval to extend the public API
     */
    static final int MAX_USER_DATA_BYTES_WITH_HEADER = 134;

    /** The maximum number of payload septets per message */
    public static final int MAX_USER_DATA_SEPTETS = 160;

    /**
     * The maximum number of payload septets per message if a user data header
     * is present.  This assumes the header only contains the
     * CONCATENATED_8_BIT_REFERENCE element.
     */
    public static final int MAX_USER_DATA_SEPTETS_WITH_HEADER = 153;

    /** The address of the SMSC. May be null */
    String scAddress;

    /** The address of the sender */
    SmsAddress originatingAddress;

    /** The message body as a string. May be null if the message isn't text */
    String messageBody;

    String pseudoSubject;

    /** Non-null  this is an email gateway message */
    String emailFrom;

    /** Non-null if this is an email gateway message */
    String emailBody;

    boolean isEmail;

    long scTimeMillis;

    /** The raw PDU of the message */
    byte[] mPdu;

    /** The raw bytes for the user data section of the message */
    byte[] userData;

    SmsHeader userDataHeader;

    /**
     * TP-Message-Type-Indicator
     * 9.2.3
     */
    int mti;

    /** TP-Protocol-Identifier (TP-PID) */
    int protocolIdentifier;

    // TP-Data-Coding-Scheme
    // see TS 23.038
    int dataCodingScheme;

    // TP-Reply-Path
    // e.g. 23.040 9.2.2.1
    boolean replyPathPresent = false;

    // "Message Marked for Automatic Deletion Group"
    // 23.038 Section 4
    boolean automaticDeletion;

    // "Message Waiting Indication Group"
    // 23.038 Section 4
    private boolean isMwi;

    private boolean mwiSense;

    private boolean mwiDontStore;

    MessageClass messageClass;

    /**
     * Indicates status for messages stored on the SIM.
     */
    int statusOnSim = -1;

    /**
     * Record index of message in the EF.
     */
    int indexOnSim = -1;

    /** TP-Message-Reference - Message Reference of sent message. @hide */
    public int messageRef;

    /** True if Status Report is for SMS-SUBMIT; false for SMS-COMMAND. */
    boolean forSubmit;

    /** The address of the receiver. */
    SmsAddress recipientAddress;

    /** Time when SMS-SUBMIT was delivered from SC to MSE. */
    long dischargeTimeMillis;

    /**
     *  TP-Status - status of a previously submitted SMS.
     *  This field applies to SMS-STATUS-REPORT messages.  0 indicates success;
     *  see TS 23.040, 9.2.3.15 for description of other possible values.
     */
    int status;

    /**
     *  TP-Status - status of a previously submitted SMS.
     *  This field is true iff the message is a SMS-STATUS-REPORT message.
     */
    boolean isStatusReportMessage = false;

    /**
     * This class represents the encoded form of an outgoing SMS.
     */
    public static class SubmitPdu {
        public byte[] encodedScAddress; // Null if not applicable.
        public byte[] encodedMessage;

        public String toString() {
            return "SubmitPdu: encodedScAddress = "
                    + Arrays.toString(encodedScAddress)
                    + ", encodedMessage = "
                    + Arrays.toString(encodedMessage);
        }
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
            Log.e(LOG_TAG, "SMS PDU parsing failed: ", ex);
            return null;
        }
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
    /* package */ public static SmsMessage newFromCMT(String[] lines) {
        try {
            SmsMessage msg = new SmsMessage();
            msg.parsePdu(SimUtils.hexStringToBytes(lines[1]));
            return msg;
        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "SMS PDU parsing failed: ", ex);
            return null;
        }
    }

    /* pacakge */ static SmsMessage newFromCMTI(String line) {
        // the thinking here is not to read the message immediately
        // FTA test case
        Log.e(LOG_TAG, "newFromCMTI: not yet supported");
        return null;
    }

    /** @hide */
    /* package */ public static SmsMessage newFromCDS(String line) {
        try {
            SmsMessage msg = new SmsMessage();
            msg.parsePdu(SimUtils.hexStringToBytes(line));
            return msg;
        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "CDS SMS PDU parsing failed: ", ex);
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

            msg.indexOnSim = index;

            // First byte is status: RECEIVED_READ, RECEIVED_UNREAD, STORED_SENT,
            // or STORED_UNSENT
            // See TS 51.011 10.5.3
            if ((data[0] & 1) == 0) {
                Log.w(LOG_TAG,
                        "SMS parsing failed: Trying to parse a free record");
                return null;
            } else {
                msg.statusOnSim = data[0] & 0x07;
            }

            int size = data.length - 1;

            // Note: Data may include trailing FF's.  That's OK; message
            // should still parse correctly.
            byte[] pdu = new byte[size];
            System.arraycopy(data, 1, pdu, 0, size);
            msg.parsePdu(pdu);
            return msg;
        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "SMS PDU parsing failed: ", ex);
            return null;
        }
    }

    /**
     * Get the TP-Layer-Length for the given SMS-SUBMIT PDU Basically, the
     * length in bytes (not hex chars) less the SMSC header
     */
    public static int getTPLayerLengthForPDU(String pdu) {
        int len = pdu.length() / 2;
        int smscLen = 0;

        smscLen = Integer.parseInt(pdu.substring(0, 2), 16);

        return len - smscLen - 1;
    }

    /**
     * Calculates the number of SMS's required to encode the message body and
     * the number of characters remaining until the next message, given the
     * current encoding.
     *
     * @param messageBody the message to encode
     * @param use7bitOnly if true, characters that are not part of the GSM
     *         alphabet are counted as a single space char.  If false, a
     *         messageBody containing non-GSM alphabet characters is calculated
     *         for 16-bit encoding.
     * @return an int[4] with int[0] being the number of SMS's required, int[1]
     *         the number of code units used, and int[2] is the number of code
     *         units remaining until the next message. int[3] is the encoding
     *         type that should be used for the message.
     */
    public static int[] calculateLength(String messageBody, boolean use7bitOnly) {
        int ret[] = new int[4];

        try {
            // Try GSM alphabet
            int septets = GsmAlphabet.countGsmSeptets(messageBody, !use7bitOnly);
            ret[1] = septets;
            if (septets > MAX_USER_DATA_SEPTETS) {
                ret[0] = (septets / MAX_USER_DATA_SEPTETS_WITH_HEADER) + 1;
                ret[2] = MAX_USER_DATA_SEPTETS_WITH_HEADER
                            - (septets % MAX_USER_DATA_SEPTETS_WITH_HEADER);
            } else {
                ret[0] = 1;
                ret[2] = MAX_USER_DATA_SEPTETS - septets;
            }
            ret[3] = ENCODING_7BIT;
        } catch (EncodeException ex) {
            // fall back to UCS-2
            int octets = messageBody.length() * 2;
            ret[1] = messageBody.length();
            if (octets > MAX_USER_DATA_BYTES) {
                // 6 is the size of the user data header
                ret[0] = (octets / MAX_USER_DATA_BYTES_WITH_HEADER) + 1;
                ret[2] = (MAX_USER_DATA_BYTES_WITH_HEADER
                            - (octets % MAX_USER_DATA_BYTES_WITH_HEADER))/2;
            } else {
                ret[0] = 1;
                ret[2] = (MAX_USER_DATA_BYTES - octets)/2;
            }
            ret[3] = ENCODING_16BIT;
        }

        return ret;
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

        // Perform null parameter checks.
        if (message == null || destinationAddress == null) {
            return null;
        }

        SubmitPdu ret = new SubmitPdu();
        // MTI = SMS-SUBMIT, UDHI = header != null
        byte mtiByte = (byte)(0x01 | (header != null ? 0x40 : 0x00));
        ByteArrayOutputStream bo = getSubmitPduHead(
                scAddress, destinationAddress, mtiByte,
                statusReportRequested, ret);

        try {
            // First, try encoding it with the GSM alphabet

            // User Data (and length)
            byte[] userData = GsmAlphabet.stringToGsm7BitPackedWithHeader(message, header);

            if ((0xff & userData[0]) > MAX_USER_DATA_SEPTETS) {
                // Message too long
                return null;
            }

            // TP-Data-Coding-Scheme
            // Default encoding, uncompressed
            bo.write(0x00);

            // (no TP-Validity-Period)

            bo.write(userData, 0, userData.length);
        } catch (EncodeException ex) {
            byte[] userData, textPart;
            // Encoding to the 7-bit alphabet failed. Let's see if we can
            // send it as a UCS-2 encoded message

            try {
                textPart = message.getBytes("utf-16be");
            } catch (UnsupportedEncodingException uex) {
                Log.e(LOG_TAG,
                      "Implausible UnsupportedEncodingException ",
                      uex);
                return null;
            }
            
            if (header != null) {
                userData = new byte[header.length + textPart.length];
                
                System.arraycopy(header, 0, userData, 0, header.length);
                System.arraycopy(textPart, 0, userData, header.length, textPart.length);
            } else {
                userData = textPart;
            }

            if (userData.length > MAX_USER_DATA_BYTES) {
                // Message too long
                return null;
            }

            // TP-Data-Coding-Scheme
            // Class 3, UCS-2 encoding, uncompressed
            bo.write(0x0b);

            // (no TP-Validity-Period)
            
            // TP-UDL
            bo.write(userData.length);

            bo.write(userData, 0, userData.length);
        }

        ret.encodedMessage = bo.toByteArray();
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
     * Get an SMS-SUBMIT PDU for a data message to a destination address &amp; port
     *
     * @param scAddress Service Centre address. null == use default
     * @param destinationAddress the address of the destination for the message
     * @param destinationPort the port to deliver the message to at the
     *        destination
     * @param data the dat for the message
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     */
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destinationAddress, short destinationPort, byte[] data,
            boolean statusReportRequested) {
        if (data.length > (MAX_USER_DATA_BYTES - 7 /* UDH size */)) {
            Log.e(LOG_TAG, "SMS data message may only contain "
                    + (MAX_USER_DATA_BYTES - 7) + " bytes");
            return null;
        }

        SubmitPdu ret = new SubmitPdu();
        ByteArrayOutputStream bo = getSubmitPduHead(
                scAddress, destinationAddress, (byte) 0x41, // MTI = SMS-SUBMIT,
                                                            // TP-UDHI = true
                statusReportRequested, ret);

        // TP-Data-Coding-Scheme
        // No class, 8 bit data
        bo.write(0x04);

        // (no TP-Validity-Period)

        // User data size
        bo.write(data.length + 7);

        // User data header size
        bo.write(0x06); // header is 6 octets

        // User data header, indicating the destination port
        bo.write(SmsHeader.APPLICATION_PORT_ADDRESSING_16_BIT); // port
                                                                // addressing
                                                                // header
        bo.write(0x04); // each port is 2 octets
        bo.write((destinationPort >> 8) & 0xFF); // MSB of destination port
        bo.write(destinationPort & 0xFF); // LSB of destination port
        bo.write(0x00); // MSB of originating port
        bo.write(0x00); // LSB of originating port

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
     *        address, if applicable, and the encoded message
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
            if (Config.LOGD) Log.d(LOG_TAG, "SMS status report requested");
        }
        bo.write(mtiByte);

        // space for TP-Message-Reference
        bo.write(0);

        byte[] daBytes;

        daBytes = PhoneNumberUtils.networkPortionToCalledPartyBCD(destinationAddress);

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

    static class PduParser {
        byte pdu[];

        int cur;

        SmsHeader userDataHeader;

        byte[] userData;

        int mUserDataSeptetPadding;

        int mUserDataSize;

        PduParser(String s) {
            this(SimUtils.hexStringToBytes(s));
        }

        PduParser(byte[] pdu) {
            this.pdu = pdu;
            cur = 0;
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
                    ret = PhoneNumberUtils
                            .calledPartyBCDToString(pdu, cur, len);
                } catch (RuntimeException tr) {
                    Log.d(LOG_TAG, "invalid SC address: ", tr);
                    ret = null;
                }
            }

            cur += len;

            return ret;
        }

        /**
         * returns non-sign-extended byte value
         */
        int getByte() {
            return pdu[cur++] & 0xff;
        }

        /**
         * Any address except the SC address (eg, originating address) See TS
         * 23.040 9.1.2.5
         */
        SmsAddress getAddress() {
            SmsAddress ret;

            // "The Address-Length field is an integer representation of
            // the number field, i.e. excludes any semi octet containing only
            // fill bits."
            // The TOA field is not included as part of this
            int addressLength = pdu[cur] & 0xff;
            int lengthBytes = 2 + (addressLength + 1) / 2;

            ret = new SmsAddress(pdu, cur, lengthBytes);

            cur += lengthBytes;

            return ret;
        }

        /**
         * Parses an SC timestamp and returns a currentTimeMillis()-style
         * timestamp
         */

        long getSCTimestampMillis() {
            // TP-Service-Centre-Time-Stamp
            int year = SimUtils.bcdByteToInt(pdu[cur++]);
            int month = SimUtils.bcdByteToInt(pdu[cur++]);
            int day = SimUtils.bcdByteToInt(pdu[cur++]);
            int hour = SimUtils.bcdByteToInt(pdu[cur++]);
            int minute = SimUtils.bcdByteToInt(pdu[cur++]);
            int second = SimUtils.bcdByteToInt(pdu[cur++]);

            // For the timezone, the most significant bit of the
            // least signficant nibble is the sign byte
            // (meaning the max range of this field is 79 quarter-hours,
            // which is more than enough)

            byte tzByte = pdu[cur++];

            // Mask out sign bit.
            int timezoneOffset = SimUtils
                    .bcdByteToInt((byte) (tzByte & (~0x08)));

            timezoneOffset = ((tzByte & 0x08) == 0) ? timezoneOffset
                    : -timezoneOffset;

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
        int constructUserData(boolean hasUserDataHeader, boolean dataInSeptets)
        {
            int offset = cur;
            int userDataLength = pdu[offset++] & 0xff;
            int headerSeptets = 0;

            if (hasUserDataHeader) {
                int userDataHeaderLength = pdu[offset++] & 0xff;

                byte[] udh = new byte[userDataHeaderLength];
                System.arraycopy(pdu, offset, udh, 0, userDataHeaderLength);
                userDataHeader = SmsHeader.parse(udh);
                offset += userDataHeaderLength;

                int headerBits = (userDataHeaderLength + 1) * 8;
                headerSeptets = headerBits / 7;
                headerSeptets += (headerBits % 7) > 0 ? 1 : 0;
                mUserDataSeptetPadding = (headerSeptets * 7) - headerBits;
            }

            /*
             * Here we just create the user data length to be the remainder of
             * the pdu minus the user data hearder. This is because the count
             * could mean the number of uncompressed sepets if the userdata is
             * encoded in 7-bit.
             */
            userData = new byte[pdu.length - offset];
            System.arraycopy(pdu, offset, userData, 0, userData.length);
            cur = offset;

            if (dataInSeptets) {
                // Return the number of septets
                int count = userDataLength - headerSeptets;
                // If count < 0, return 0 (means UDL was probably incorrect)
                return count < 0 ? 0 : count;
            } else {
                // Return the number of octets
                return userData.length;
            }
        }

        /**
         * Returns the user data payload, not including the headers
         *
         * @return the user data payload, not including the headers
         */
        byte[] getUserData() {
            return userData;
        }

        /**
         * Returns the number of padding bits at the begining of the user data
         * array before the start of the septets.
         *
         * @return the number of padding bits at the begining of the user data
         * array before the start of the septets
         */
        int getUserDataSeptetPadding() {
            return mUserDataSeptetPadding;
        }

        /**
         * Returns an object representing the user data headers
         *
         * @return an object representing the user data headers
         * 
         * {@hide}
         */
        SmsHeader getUserDataHeader() {
            return userDataHeader;
        }

/*
        XXX Not sure what this one is supposed to be doing, and no one is using
        it.
        String getUserDataGSM8bit() {
            // System.out.println("remainder of pud:" +
            // HexDump.dumpHexString(pdu, cur, pdu.length - cur));
            int count = pdu[cur++] & 0xff;
            int size = pdu[cur++];

            // skip over header for now
            cur += size;

            if (pdu[cur - 1] == 0x01) {
                int tid = pdu[cur++] & 0xff;
                int type = pdu[cur++] & 0xff;

                size = pdu[cur++] & 0xff;

                int i = cur;

                while (pdu[i++] != '\0') {
                }

                int length = i - cur;
                String mimeType = new String(pdu, cur, length);

                cur += length;

                if (false) {
                    System.out.println("tid = 0x" + HexDump.toHexString(tid));
                    System.out.println("type = 0x" + HexDump.toHexString(type));
                    System.out.println("header size = " + size);
                    System.out.println("mimeType = " + mimeType);
                    System.out.println("remainder of header:" +
                     HexDump.dumpHexString(pdu, cur, (size - mimeType.length())));
                }

                cur += size - mimeType.length();

                // System.out.println("data count = " + count + " cur = " + cur
                // + " :" + HexDump.dumpHexString(pdu, cur, pdu.length - cur));

                MMSMessage msg = MMSMessage.parseEncoding(mContext, pdu, cur,
                        pdu.length - cur);
            } else {
                System.out.println(new String(pdu, cur, pdu.length - cur - 1));
            }

            return SimUtils.bytesToHexString(pdu);
        }
*/

        /**
         * Interprets the user data payload as pack GSM 7bit characters, and
         * decodes them into a String.
         *
         * @param septetCount the number of septets in the user data payload
         * @return a String with the decoded characters
         */
        String getUserDataGSM7Bit(int septetCount) {
            String ret;

            ret = GsmAlphabet.gsm7BitPackedToString(pdu, cur, septetCount,
                    mUserDataSeptetPadding);

            cur += (septetCount * 7) / 8;

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
                ret = new String(pdu, cur, byteCount, "utf-16");
            } catch (UnsupportedEncodingException ex) {
                ret = "";
                Log.e(LOG_TAG, "implausible UnsupportedEncodingException", ex);
            }

            cur += byteCount;
            return ret;
        }

        boolean moreDataPresent() {
            return (pdu.length > cur);
        }
    }

    /**
     * Returns the address of the SMS service center that relayed this message
     * or null if there is none.
     */
    public String getServiceCenterAddress() {
        return scAddress;
    }

    /**
     * Returns the originating address (sender) of this SMS message in String
     * form or null if unavailable
     */
    public String getOriginatingAddress() {
        if (originatingAddress == null) {
            return null;
        }

        return originatingAddress.getAddressString();
    }

    /**
     * Returns the originating address, or email from address if this message
     * was from an email gateway. Returns null if originating address
     * unavailable.
     */
    public String getDisplayOriginatingAddress() {
        if (isEmail) {
            return emailFrom;
        } else {
            return getOriginatingAddress();
        }
    }

    /**
     * Returns the message body as a String, if it exists and is text based.
     * @return message body is there is one, otherwise null
     */
    public String getMessageBody() {
        return messageBody;
    }

    /**
     * Returns the class of this message.
     */
    public MessageClass getMessageClass() {
        return messageClass;
    }

    /**
     * Returns the message body, or email message body if this message was from
     * an email gateway. Returns null if message body unavailable.
     */
    public String getDisplayMessageBody() {
        if (isEmail) {
            return emailBody;
        } else {
            return getMessageBody();
        }
    }

    /**
     * Unofficial convention of a subject line enclosed in parens empty string
     * if not present
     */
    public String getPseudoSubject() {
        return pseudoSubject == null ? "" : pseudoSubject;
    }

    /**
     * Returns the service centre timestamp in currentTimeMillis() format
     */
    public long getTimestampMillis() {
        return scTimeMillis;
    }

    /**
     * Returns true if message is an email.
     *
     * @return true if this message came through an email gateway and email
     *         sender / subject / parsed body are available
     */
    public boolean isEmail() {
        return isEmail;
    }

    /**
     * @return if isEmail() is true, body of the email sent through the gateway.
     *         null otherwise
     */
    public String getEmailBody() {
        return emailBody;
    }

    /**
     * @return if isEmail() is true, email from address of email sent through
     *         the gateway. null otherwise
     */
    public String getEmailFrom() {
        return emailFrom;
    }

    /**
     * Get protocol identifier.
     */
    public int getProtocolIdentifier() {
        return protocolIdentifier;
    }

    /**
     * See TS 23.040 9.2.3.9 returns true if this is a "replace short message"
     * SMS
     */
    public boolean isReplace() {
        return (protocolIdentifier & 0xc0) == 0x40
                && (protocolIdentifier & 0x3f) > 0
                && (protocolIdentifier & 0x3f) < 8;
    }

    /**
     * Returns true for CPHS MWI toggle message.
     *
     * @return true if this is a CPHS MWI toggle message See CPHS 4.2 section
     *         B.4.2
     */
    public boolean isCphsMwiMessage() {
        return originatingAddress.isCphsVoiceMessageClear()
                || originatingAddress.isCphsVoiceMessageSet();
    }

    /**
     * returns true if this message is a CPHS voicemail / message waiting
     * indicator (MWI) clear message
     */
    public boolean isMWIClearMessage() {
        if (isMwi && (mwiSense == false)) {
            return true;
        }

        return originatingAddress != null
                && originatingAddress.isCphsVoiceMessageClear();
    }

    /**
     * returns true if this message is a CPHS voicemail / message waiting
     * indicator (MWI) set message
     */
    public boolean isMWISetMessage() {
        if (isMwi && (mwiSense == true)) {
            return true;
        }

        return originatingAddress != null
                && originatingAddress.isCphsVoiceMessageSet();
    }

    /**
     * returns true if this message is a "Message Waiting Indication Group:
     * Discard Message" notification and should not be stored.
     */
    public boolean isMwiDontStore() {
        if (isMwi && mwiDontStore) {
            return true;
        }

        if (isCphsMwiMessage()) {
            // See CPHS 4.2 Section B.4.2.1
            // If the user data is a single space char, do not store
            // the message. Otherwise, store and display as usual
            if (" ".equals(getMessageBody())) {
                ;
            }
            return true;
        }

        return false;
    }

    /**
     * returns the user data section minus the user data header if one was
     * present.
     */
    public byte[] getUserData() {
        return userData;
    }

    /**
     * Returns an object representing the user data header
     *
     * @return an object representing the user data header
     * 
     * {@hide}
     */
    public SmsHeader getUserDataHeader() {
        return userDataHeader;
    }

    /**
     * Returns the raw PDU for the message.
     *
     * @return the raw PDU for the message.
     */
    public byte[] getPdu() {
        return mPdu;
    }

    /**
     * Returns the status of the message on the SIM (read, unread, sent, unsent).
     *
     * @return the status of the message on the SIM.  These are:
     *         SmsManager.STATUS_ON_SIM_FREE
     *         SmsManager.STATUS_ON_SIM_READ
     *         SmsManager.STATUS_ON_SIM_UNREAD
     *         SmsManager.STATUS_ON_SIM_SEND
     *         SmsManager.STATUS_ON_SIM_UNSENT
     */
    public int getStatusOnSim() {
        return statusOnSim;
    }

    /**
     * Returns the record index of the message on the SIM (1-based index).
     * @return the record index of the message on the SIM, or -1 if this
     *         SmsMessage was not created from a SIM SMS EF record.
     */
    public int getIndexOnSim() {
        return indexOnSim;
    }

    /**
     * For an SMS-STATUS-REPORT message, this returns the status field from
     * the status report.  This field indicates the status of a previousely
     * submitted SMS, if requested.  See TS 23.040, 9.2.3.15 TP-Status for a
     * description of values.
     *
     * @return 0 indicates the previously sent message was received.
     *         See TS 23.040, 9.9.2.3.15 for a description of other possible
     *         values.
     */
    public int getStatus() {
        return status;
    }

    /**
     * Return true iff the message is a SMS-STATUS-REPORT message.
     */
    public boolean isStatusReportMessage() {
        return isStatusReportMessage;
    }

    /**
     * Returns true iff the <code>TP-Reply-Path</code> bit is set in
     * this message.
     */
    public boolean isReplyPathPresent() {
        return replyPathPresent;
    }

    /**
     * TS 27.005 3.1, <pdu> definition "In the case of SMS: 3GPP TS 24.011 [6]
     * SC address followed by 3GPP TS 23.040 [3] TPDU in hexadecimal format:
     * ME/TA converts each octet of TP data unit into two IRA character long
     * hexad number (e.g. octet with integer value 42 is presented to TE as two
     * characters 2A (IRA 50 and 65))" ...in the case of cell broadcast,
     * something else...
     */
    private void parsePdu(byte[] pdu) {
        mPdu = pdu;
        // Log.d(LOG_TAG, "raw sms mesage:");
        // Log.d(LOG_TAG, s);

        PduParser p = new PduParser(pdu);

        scAddress = p.getSCAddress();

        if (scAddress != null) {
            if (Config.LOGD) Log.d(LOG_TAG, "SMS SC address: " + scAddress);
        }

        // TODO(mkf) support reply path, user data header indicator

        // TP-Message-Type-Indicator
        // 9.2.3
        int firstByte = p.getByte();

        mti = firstByte & 0x3;
        switch (mti) {
        // TP-Message-Type-Indicator
        // 9.2.3
        case 0:
            parseSmsDeliver(p, firstByte);
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
        isStatusReportMessage = true;

        // TP-Status-Report-Qualifier bit == 0 for SUBMIT
        forSubmit = (firstByte & 0x20) == 0x00;
        // TP-Message-Reference
        messageRef = p.getByte();
        // TP-Recipient-Address
        recipientAddress = p.getAddress();
        // TP-Service-Centre-Time-Stamp
        scTimeMillis = p.getSCTimestampMillis();
        // TP-Discharge-Time
        dischargeTimeMillis = p.getSCTimestampMillis();
        // TP-Status
        status = p.getByte();

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
            // TP-Protocol-Identifier
            if ((extraParams & 0x01) != 0) {
                protocolIdentifier = p.getByte();
            }
            // TP-Data-Coding-Scheme
            if ((extraParams & 0x02) != 0) {
                dataCodingScheme = p.getByte();
            }
            // TP-User-Data-Length (implies existence of TP-User-Data)
            if ((extraParams & 0x04) != 0) {
                boolean hasUserDataHeader = (firstByte & 0x40) == 0x40;
                parseUserData(p, hasUserDataHeader);
            }
        }
    }

    private void parseSmsDeliver(PduParser p, int firstByte) {
        replyPathPresent = (firstByte & 0x80) == 0x80;

        originatingAddress = p.getAddress();

        if (originatingAddress != null) {
            if (Config.LOGV) Log.v(LOG_TAG, "SMS originating address: "
                    + originatingAddress.address);
        }

        // TP-Protocol-Identifier (TP-PID)
        // TS 23.040 9.2.3.9
        protocolIdentifier = p.getByte();

        // TP-Data-Coding-Scheme
        // see TS 23.038
        dataCodingScheme = p.getByte();

        if (Config.LOGV) {
            Log.v(LOG_TAG, "SMS TP-PID:" + protocolIdentifier
                    + " data coding scheme: " + dataCodingScheme);
        }

        scTimeMillis = p.getSCTimestampMillis();

        if (Config.LOGD) Log.d(LOG_TAG, "SMS SC timestamp: " + scTimeMillis);

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
        if ((dataCodingScheme & 0x80) == 0) {
            // Bits 7..4 == 0xxx
            automaticDeletion = (0 != (dataCodingScheme & 0x40));
            userDataCompressed = (0 != (dataCodingScheme & 0x20));
            hasMessageClass = (0 != (dataCodingScheme & 0x10));

            if (userDataCompressed) {
                Log.w(LOG_TAG, "4 - Unsupported SMS data coding scheme "
                        + "(compression) " + (dataCodingScheme & 0xff));
            } else {
                switch ((dataCodingScheme >> 2) & 0x3) {
                case 0: // GSM 7 bit default alphabet
                    encodingType = ENCODING_7BIT;
                    break;

                case 2: // UCS 2 (16bit)
                    encodingType = ENCODING_16BIT;
                    break;

                case 1: // 8 bit data
                case 3: // reserved
                    Log.w(LOG_TAG, "1 - Unsupported SMS data coding scheme "
                            + (dataCodingScheme & 0xff));
                    encodingType = ENCODING_8BIT;
                    break;
                }
            }
        } else if ((dataCodingScheme & 0xf0) == 0xf0) {
            automaticDeletion = false;
            hasMessageClass = true;
            userDataCompressed = false;

            if (0 == (dataCodingScheme & 0x04)) {
                // GSM 7 bit default alphabet
                encodingType = ENCODING_7BIT;
            } else {
                // 8 bit data
                encodingType = ENCODING_8BIT;
            }
        } else if ((dataCodingScheme & 0xF0) == 0xC0
                || (dataCodingScheme & 0xF0) == 0xD0
                || (dataCodingScheme & 0xF0) == 0xE0) {
            // 3GPP TS 23.038 V7.0.0 (2006-03) section 4

            // 0xC0 == 7 bit, don't store
            // 0xD0 == 7 bit, store
            // 0xE0 == UCS-2, store

            if ((dataCodingScheme & 0xF0) == 0xE0) {
                encodingType = ENCODING_16BIT;
            } else {
                encodingType = ENCODING_7BIT;
            }

            userDataCompressed = false;
            boolean active = ((dataCodingScheme & 0x08) == 0x08);

            // bit 0x04 reserved

            if ((dataCodingScheme & 0x03) == 0x00) {
                isMwi = true;
                mwiSense = active;
                mwiDontStore = ((dataCodingScheme & 0xF0) == 0xC0);
            } else {
                isMwi = false;

                Log.w(LOG_TAG, "MWI for fax, email, or other "
                        + (dataCodingScheme & 0xff));
            }
        } else {
            Log.w(LOG_TAG, "3 - Unsupported SMS data coding scheme "
                    + (dataCodingScheme & 0xff));
        }

        // set both the user data and the user data header.
        int count = p.constructUserData(hasUserDataHeader,
                encodingType == ENCODING_7BIT);
        this.userData = p.getUserData();
        this.userDataHeader = p.getUserDataHeader();

        switch (encodingType) {
        case ENCODING_UNKNOWN:
        case ENCODING_8BIT:
            messageBody = null;
            break;

        case ENCODING_7BIT:
            messageBody = p.getUserDataGSM7Bit(count);
            break;

        case ENCODING_16BIT:
            messageBody = p.getUserDataUCS2(count);
            break;
        }

        if (Config.LOGV) Log.v(LOG_TAG, "SMS message body (raw): '" + messageBody + "'");

        if (messageBody != null) {
            parseMessageBody();
        }

        if (!hasMessageClass) {
            messageClass = MessageClass.UNKNOWN;
        } else {
            switch (dataCodingScheme & 0x3) {
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

    private void parseMessageBody() {
        if (originatingAddress.couldBeEmailGateway()) {
            extractEmailAddressFromMessageBody();
        }
    }

    /**
     * Try to parse this message as an email gateway message -> Neither
     * of the standard ways are currently supported: There are two ways
     * specified in TS 23.040 Section 3.8 (not supported via this mechanism) -
     * SMS message "may have its TP-PID set for internet electronic mail - MT
     * SMS format: [<from-address><space>]<message> - "Depending on the
     * nature of the gateway, the destination/origination address is either
     * derived from the content of the SMS TP-OA or TP-DA field, or the
     * TP-OA/TP-DA field contains a generic gateway address and the to/from
     * address is added at the beginning as shown above." - multiple addreses
     * separated by commas, no spaces - subject field delimited by '()' or '##'
     * and '#' Section 9.2.3.24.11
     */
    private void extractEmailAddressFromMessageBody() {

        /*
         * a little guesswork here. I haven't found doc for this.
         * the format could be either
         *
         * 1. [x@y][ ]/[subject][ ]/[body]
         * -or-
         * 2. [x@y][ ]/[body]
         */
        int slash = 0, slash2 = 0, atSymbol = 0;

        try {
            slash = messageBody.indexOf(" /");
            if (slash == -1) {
                return;
            }

            atSymbol = messageBody.indexOf('@');
            if (atSymbol == -1 || atSymbol > slash) {
                return;
            }

            emailFrom = messageBody.substring(0, slash);

            slash2 = messageBody.indexOf(" /", slash + 2);

            if (slash2 == -1) {
                pseudoSubject = null;
                emailBody = messageBody.substring(slash + 2);
            } else {
                pseudoSubject = messageBody.substring(slash + 2, slash2);
                emailBody = messageBody.substring(slash2 + 2);
            }

            isEmail = true;
        } catch (Exception ex) {
            Log.w(LOG_TAG,
                    "extractEmailAddressFromMessageBody: exception slash="
                    + slash + ", atSymbol=" + atSymbol + ", slash2="
                    + slash2, ex);
        }
    }

}
