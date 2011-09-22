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

package android.telephony.gsm;

import android.os.Parcel;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.EncodeException;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsMessageBase;
import com.android.internal.telephony.SmsMessageBase.SubmitPduBase;

import java.util.Arrays;

import static android.telephony.TelephonyManager.PHONE_TYPE_CDMA;


/**
 * A Short Message Service message.
 * @deprecated Replaced by android.telephony.SmsMessage that supports both GSM and CDMA.
 */
@Deprecated
public class SmsMessage {
    private static final boolean LOCAL_DEBUG = true;
    private static final String LOG_TAG = "SMS";

    /**
     * SMS Class enumeration.
     * See TS 23.038.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public enum MessageClass{
        UNKNOWN, CLASS_0, CLASS_1, CLASS_2, CLASS_3;
    }

    /** Unknown encoding scheme (see TS 23.038)
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated public static final int ENCODING_UNKNOWN = 0;

    /** 7-bit encoding scheme (see TS 23.038)
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated public static final int ENCODING_7BIT = 1;

    /** 8-bit encoding scheme (see TS 23.038)
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated public static final int ENCODING_8BIT = 2;

    /** 16-bit encoding scheme (see TS 23.038)
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated public static final int ENCODING_16BIT = 3;

    /** The maximum number of payload bytes per message
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated public static final int MAX_USER_DATA_BYTES = 140;

    /**
     * The maximum number of payload bytes per message if a user data header
     * is present.  This assumes the header only contains the
     * CONCATENATED_8_BIT_REFERENCE element.
     *
     * @deprecated Use android.telephony.SmsMessage.
     * @hide pending API Council approval to extend the public API
     */
    @Deprecated public static final int MAX_USER_DATA_BYTES_WITH_HEADER = 134;

    /** The maximum number of payload septets per message
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated public static final int MAX_USER_DATA_SEPTETS = 160;

    /**
     * The maximum number of payload septets per message if a user data header
     * is present.  This assumes the header only contains the
     * CONCATENATED_8_BIT_REFERENCE element.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated public static final int MAX_USER_DATA_SEPTETS_WITH_HEADER = 153;

    /** Contains actual SmsMessage. Only public for debugging and for framework layer.
     * @deprecated Use android.telephony.SmsMessage.
     * {@hide}
     */
    @Deprecated public SmsMessageBase mWrappedSmsMessage;

    /** @deprecated Use android.telephony.SmsMessage. */
    @Deprecated
    public static class SubmitPdu {
        /** @deprecated Use android.telephony.SmsMessage. */
        @Deprecated public byte[] encodedScAddress; // Null if not applicable.
        /** @deprecated Use android.telephony.SmsMessage. */
        @Deprecated public byte[] encodedMessage;

        //Constructor
        /** @deprecated Use android.telephony.SmsMessage. */
        @Deprecated
        public SubmitPdu() {
        }

        /** @deprecated Use android.telephony.SmsMessage.
         * {@hide}
         */
        @Deprecated
        protected SubmitPdu(SubmitPduBase spb) {
            this.encodedMessage = spb.encodedMessage;
            this.encodedScAddress = spb.encodedScAddress;
        }

        /** @deprecated Use android.telephony.SmsMessage. */
        @Deprecated
        public String toString() {
            return "SubmitPdu: encodedScAddress = "
                    + Arrays.toString(encodedScAddress)
                    + ", encodedMessage = "
                    + Arrays.toString(encodedMessage);
        }
    }

    // Constructor
    /** @deprecated Use android.telephony.SmsMessage. */
    @Deprecated
    public SmsMessage() {
        this(getSmsFacility());
    }

    private SmsMessage(SmsMessageBase smb) {
        mWrappedSmsMessage = smb;
    }

    /**
     * Create an SmsMessage from a raw PDU.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public static SmsMessage createFromPdu(byte[] pdu) {
        SmsMessageBase wrappedMessage;
        int activePhone = TelephonyManager.getDefault().getCurrentPhoneType();

        if (PHONE_TYPE_CDMA == activePhone) {
            wrappedMessage = com.android.internal.telephony.cdma.SmsMessage.createFromPdu(pdu);
        } else {
            wrappedMessage = com.android.internal.telephony.gsm.SmsMessage.createFromPdu(pdu);
        }

        return new SmsMessage(wrappedMessage);
    }

    /**
     * Get the TP-Layer-Length for the given SMS-SUBMIT PDU Basically, the
     * length in bytes (not hex chars) less the SMSC header
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public static int getTPLayerLengthForPDU(String pdu) {
        int activePhone = TelephonyManager.getDefault().getCurrentPhoneType();

        if (PHONE_TYPE_CDMA == activePhone) {
            return com.android.internal.telephony.cdma.SmsMessage.getTPLayerLengthForPDU(pdu);
        } else {
            return com.android.internal.telephony.gsm.SmsMessage.getTPLayerLengthForPDU(pdu);
        }
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
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public static int[] calculateLength(CharSequence messageBody, boolean use7bitOnly) {
        SmsMessageBase.TextEncodingDetails ted =
                com.android.internal.telephony.gsm.SmsMessage
                        .calculateLength(messageBody, use7bitOnly);
        int ret[] = new int[4];
        ret[0] = ted.msgCount;
        ret[1] = ted.codeUnitCount;
        ret[2] = ted.codeUnitsRemaining;
        ret[3] = ted.codeUnitSize;
        return ret;
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
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public static int[] calculateLength(String messageBody, boolean use7bitOnly) {
        return calculateLength((CharSequence)messageBody, use7bitOnly);
    }

    /**
     * Get an SMS-SUBMIT PDU for a destination address and a message
     *
     * @param scAddress Service Centre address.  Null means use default.
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     * @deprecated Use android.telephony.SmsMessage.
     * @hide
     */
    @Deprecated
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destinationAddress, String message,
            boolean statusReportRequested, byte[] header) {
        SubmitPduBase spb;
        int activePhone = TelephonyManager.getDefault().getCurrentPhoneType();

        if (PHONE_TYPE_CDMA == activePhone) {
            spb = com.android.internal.telephony.cdma.SmsMessage.getSubmitPdu(scAddress,
                    destinationAddress, message, statusReportRequested,
                    SmsHeader.fromByteArray(header));
        } else {
            spb = com.android.internal.telephony.gsm.SmsMessage.getSubmitPdu(scAddress,
                    destinationAddress, message, statusReportRequested, header);
        }

        return new SubmitPdu(spb);
    }

    /**
     * Get an SMS-SUBMIT PDU for a destination address and a message
     *
     * @param scAddress Service Centre address.  Null means use default.
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destinationAddress, String message, boolean statusReportRequested) {
        SubmitPduBase spb;
        int activePhone = TelephonyManager.getDefault().getCurrentPhoneType();

        if (PHONE_TYPE_CDMA == activePhone) {
            spb = com.android.internal.telephony.cdma.SmsMessage.getSubmitPdu(scAddress,
                    destinationAddress, message, statusReportRequested, null);
        } else {
            spb = com.android.internal.telephony.gsm.SmsMessage.getSubmitPdu(scAddress,
                    destinationAddress, message, statusReportRequested);
        }

        return new SubmitPdu(spb);
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
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destinationAddress, short destinationPort, byte[] data,
            boolean statusReportRequested) {
        SubmitPduBase spb;
        int activePhone = TelephonyManager.getDefault().getCurrentPhoneType();

        if (PHONE_TYPE_CDMA == activePhone) {
            spb = com.android.internal.telephony.cdma.SmsMessage.getSubmitPdu(scAddress,
                    destinationAddress, destinationPort, data, statusReportRequested);
        } else {
            spb = com.android.internal.telephony.gsm.SmsMessage.getSubmitPdu(scAddress,
                    destinationAddress, destinationPort, data, statusReportRequested);
        }

        return new SubmitPdu(spb);
    }

    /**
     * Returns the address of the SMS service center that relayed this message
     * or null if there is none.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public String getServiceCenterAddress() {
        return mWrappedSmsMessage.getServiceCenterAddress();
    }

    /**
     * Returns the originating address (sender) of this SMS message in String
     * form or null if unavailable
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public String getOriginatingAddress() {
        return mWrappedSmsMessage.getOriginatingAddress();
    }

    /**
     * Returns the originating address, or email from address if this message
     * was from an email gateway. Returns null if originating address
     * unavailable.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public String getDisplayOriginatingAddress() {
        return mWrappedSmsMessage.getDisplayOriginatingAddress();
    }

    /**
     * Returns the message body as a String, if it exists and is text based.
     * @return message body is there is one, otherwise null
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public String getMessageBody() {
        return mWrappedSmsMessage.getMessageBody();
    }

    /**
     * Returns the class of this message.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public MessageClass getMessageClass() {
        int index = mWrappedSmsMessage.getMessageClass().ordinal();

        return MessageClass.values()[index];
    }

    /**
     * Returns the message body, or email message body if this message was from
     * an email gateway. Returns null if message body unavailable.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public String getDisplayMessageBody() {
        return mWrappedSmsMessage.getDisplayMessageBody();
    }

    /**
     * Unofficial convention of a subject line enclosed in parens empty string
     * if not present
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public String getPseudoSubject() {
        return mWrappedSmsMessage.getPseudoSubject();
    }

    /**
     * Returns the service centre timestamp in currentTimeMillis() format
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public long getTimestampMillis() {
        return mWrappedSmsMessage.getTimestampMillis();
    }

    /**
     * Returns true if message is an email.
     *
     * @return true if this message came through an email gateway and email
     *         sender / subject / parsed body are available
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public boolean isEmail() {
        return mWrappedSmsMessage.isEmail();
    }

     /**
     * @return if isEmail() is true, body of the email sent through the gateway.
     *         null otherwise
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public String getEmailBody() {
        return mWrappedSmsMessage.getEmailBody();
    }

    /**
     * @return if isEmail() is true, email from address of email sent through
     *         the gateway. null otherwise
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public String getEmailFrom() {
        return mWrappedSmsMessage.getEmailFrom();
    }

    /**
     * Get protocol identifier.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public int getProtocolIdentifier() {
        return mWrappedSmsMessage.getProtocolIdentifier();
    }

    /**
     * See TS 23.040 9.2.3.9 returns true if this is a "replace short message" SMS
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public boolean isReplace() {
        return mWrappedSmsMessage.isReplace();
    }

    /**
     * Returns true for CPHS MWI toggle message.
     *
     * @return true if this is a CPHS MWI toggle message See CPHS 4.2 section B.4.2
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public boolean isCphsMwiMessage() {
        return mWrappedSmsMessage.isCphsMwiMessage();
    }

    /**
     * returns true if this message is a CPHS voicemail / message waiting
     * indicator (MWI) clear message
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public boolean isMWIClearMessage() {
        return mWrappedSmsMessage.isMWIClearMessage();
    }

    /**
     * returns true if this message is a CPHS voicemail / message waiting
     * indicator (MWI) set message
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public boolean isMWISetMessage() {
        return mWrappedSmsMessage.isMWISetMessage();
    }

    /**
     * returns true if this message is a "Message Waiting Indication Group:
     * Discard Message" notification and should not be stored.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public boolean isMwiDontStore() {
        return mWrappedSmsMessage.isMwiDontStore();
    }

    /**
     * returns the user data section minus the user data header if one was present.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public byte[] getUserData() {
        return mWrappedSmsMessage.getUserData();
    }

    /* Not part of the SDK interface and only needed by specific classes:
       protected SmsHeader getUserDataHeader()
    */

    /**
     * Returns the raw PDU for the message.
     *
     * @return the raw PDU for the message.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public byte[] getPdu() {
        return mWrappedSmsMessage.getPdu();
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
     * @deprecated Use android.telephony.SmsMessage and getStatusOnIcc instead.
     */
    @Deprecated
    public int getStatusOnSim() {
        return mWrappedSmsMessage.getStatusOnIcc();
    }

    /**
     * Returns the status of the message on the ICC (read, unread, sent, unsent).
     *
     * @return the status of the message on the ICC.  These are:
     *         SmsManager.STATUS_ON_ICC_FREE
     *         SmsManager.STATUS_ON_ICC_READ
     *         SmsManager.STATUS_ON_ICC_UNREAD
     *         SmsManager.STATUS_ON_ICC_SEND
     *         SmsManager.STATUS_ON_ICC_UNSENT
     * @deprecated Use android.telephony.SmsMessage.
     * @hide
     */
    @Deprecated
    public int getStatusOnIcc() {

        return mWrappedSmsMessage.getStatusOnIcc();
    }

    /**
     * Returns the record index of the message on the SIM (1-based index).
     * @return the record index of the message on the SIM, or -1 if this
     *         SmsMessage was not created from a SIM SMS EF record.
     * @deprecated Use android.telephony.SmsMessage and getIndexOnIcc instead.
     */
    @Deprecated
    public int getIndexOnSim() {
        return mWrappedSmsMessage.getIndexOnIcc();
    }

    /**
     * Returns the record index of the message on the ICC (1-based index).
     * @return the record index of the message on the ICC, or -1 if this
     *         SmsMessage was not created from a ICC SMS EF record.
     * @deprecated Use android.telephony.SmsMessage.
     * @hide
     */
    @Deprecated
    public int getIndexOnIcc() {

        return mWrappedSmsMessage.getIndexOnIcc();
    }

    /**
     * GSM:
     * For an SMS-STATUS-REPORT message, this returns the status field from
     * the status report.  This field indicates the status of a previously
     * submitted SMS, if requested.  See TS 23.040, 9.2.3.15 TP-Status for a
     * description of values.
     * CDMA:
     * For not interfering with status codes from GSM, the value is
     * shifted to the bits 31-16.
     * The value is composed of an error class (bits 25-24) and a status code (bits 23-16).
     * Possible codes are described in C.S0015-B, v2.0, 4.5.21.
     *
     * @return 0 indicates the previously sent message was received.
     *         See TS 23.040, 9.9.2.3.15 and C.S0015-B, v2.0, 4.5.21
     *         for a description of other possible values.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public int getStatus() {
        return mWrappedSmsMessage.getStatus();
    }

    /**
     * Return true iff the message is a SMS-STATUS-REPORT message.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public boolean isStatusReportMessage() {
        return mWrappedSmsMessage.isStatusReportMessage();
    }

    /**
     * Returns true iff the <code>TP-Reply-Path</code> bit is set in
     * this message.
     * @deprecated Use android.telephony.SmsMessage.
     */
    @Deprecated
    public boolean isReplyPathPresent() {
        return mWrappedSmsMessage.isReplyPathPresent();
    }

    /** This method returns the reference to a specific
     *  SmsMessage object, which is used for accessing its static methods.
     * @return Specific SmsMessage.
     * @deprecated Use android.telephony.SmsMessage.
     */
    private static final SmsMessageBase getSmsFacility(){
        int activePhone = TelephonyManager.getDefault().getCurrentPhoneType();
        if (PHONE_TYPE_CDMA == activePhone) {
            return new com.android.internal.telephony.cdma.SmsMessage();
        } else {
            return new com.android.internal.telephony.gsm.SmsMessage();
        }
    }
}
