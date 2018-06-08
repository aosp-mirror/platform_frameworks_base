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

package android.telephony;

import static android.telephony.TelephonyManager.PHONE_TYPE_CDMA;

import android.annotation.Nullable;
import android.annotation.StringDef;
import android.content.res.Resources;
import android.os.Binder;
import android.text.TextUtils;

import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.GsmAlphabet.TextEncodingDetails;
import com.android.internal.telephony.Sms7BitEncodingTranslator;
import com.android.internal.telephony.SmsConstants;
import com.android.internal.telephony.SmsMessageBase;
import com.android.internal.telephony.SmsMessageBase.SubmitPduBase;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;


/**
 * A Short Message Service message.
 * @see android.provider.Telephony.Sms.Intents#getMessagesFromIntent
 */
public class SmsMessage {
    private static final String LOG_TAG = "SmsMessage";

    /**
     * SMS Class enumeration.
     * See TS 23.038.
     *
     */
    public enum MessageClass{
        UNKNOWN, CLASS_0, CLASS_1, CLASS_2, CLASS_3;
    }

    /** User data text encoding code unit size */
    public static final int ENCODING_UNKNOWN = 0;
    public static final int ENCODING_7BIT = 1;
    public static final int ENCODING_8BIT = 2;
    public static final int ENCODING_16BIT = 3;
    /**
     * @hide This value is not defined in global standard. Only in Korea, this is used.
     */
    public static final int ENCODING_KSC5601 = 4;

    /** The maximum number of payload bytes per message */
    public static final int MAX_USER_DATA_BYTES = 140;

    /**
     * The maximum number of payload bytes per message if a user data header
     * is present.  This assumes the header only contains the
     * CONCATENATED_8_BIT_REFERENCE element.
     */
    public static final int MAX_USER_DATA_BYTES_WITH_HEADER = 134;

    /** The maximum number of payload septets per message */
    public static final int MAX_USER_DATA_SEPTETS = 160;

    /**
     * The maximum number of payload septets per message if a user data header
     * is present.  This assumes the header only contains the
     * CONCATENATED_8_BIT_REFERENCE element.
     */
    public static final int MAX_USER_DATA_SEPTETS_WITH_HEADER = 153;

    /** @hide */
    @StringDef(prefix = { "FORMAT_" }, value = {
            FORMAT_3GPP,
            FORMAT_3GPP2
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Format {}

    /**
     * Indicates a 3GPP format SMS message.
     * @see SmsManager#injectSmsPdu(byte[], String, PendingIntent)
     */
    public static final String FORMAT_3GPP = "3gpp";

    /**
     * Indicates a 3GPP2 format SMS message.
     * @see SmsManager#injectSmsPdu(byte[], String, PendingIntent)
     */
    public static final String FORMAT_3GPP2 = "3gpp2";

    /** Contains actual SmsMessage. Only public for debugging and for framework layer.
     *
     * @hide
     */
    public SmsMessageBase mWrappedSmsMessage;

    /** Indicates the subId
     *
     * @hide
     */
    private int mSubId = 0;

    /** set Subscription information
     *
     * @hide
     */
    public void setSubId(int subId) {
        mSubId = subId;
    }

    /** get Subscription information
     *
     * @hide
     */
    public int getSubId() {
        return mSubId;
    }

    public static class SubmitPdu {

        public byte[] encodedScAddress; // Null if not applicable.
        public byte[] encodedMessage;

        @Override
        public String toString() {
            return "SubmitPdu: encodedScAddress = "
                    + Arrays.toString(encodedScAddress)
                    + ", encodedMessage = "
                    + Arrays.toString(encodedMessage);
        }

        /**
         * @hide
         */
        protected SubmitPdu(SubmitPduBase spb) {
            this.encodedMessage = spb.encodedMessage;
            this.encodedScAddress = spb.encodedScAddress;
        }

    }

    /**
     * @hide
     */
    public SmsMessage(SmsMessageBase smb) {
        mWrappedSmsMessage = smb;
    }

    /**
     * Create an SmsMessage from a raw PDU. Guess format based on Voice
     * technology first, if it fails use other format.
     * All applications which handle
     * incoming SMS messages by processing the {@code SMS_RECEIVED_ACTION} broadcast
     * intent <b>must</b> now pass the new {@code format} String extra from the intent
     * into the new method {@code createFromPdu(byte[], String)} which takes an
     * extra format parameter. This is required in order to correctly decode the PDU on
     * devices that require support for both 3GPP and 3GPP2 formats at the same time,
     * such as dual-mode GSM/CDMA and CDMA/LTE phones.
     * @deprecated Use {@link #createFromPdu(byte[], String)} instead.
     */
    @Deprecated
    public static SmsMessage createFromPdu(byte[] pdu) {
         SmsMessage message = null;

        // cdma(3gpp2) vs gsm(3gpp) format info was not given,
        // guess from active voice phone type
        int activePhone = TelephonyManager.getDefault().getCurrentPhoneType();
        String format = (PHONE_TYPE_CDMA == activePhone) ?
                SmsConstants.FORMAT_3GPP2 : SmsConstants.FORMAT_3GPP;
        message = createFromPdu(pdu, format);

        if (null == message || null == message.mWrappedSmsMessage) {
            // decoding pdu failed based on activePhone type, must be other format
            format = (PHONE_TYPE_CDMA == activePhone) ?
                    SmsConstants.FORMAT_3GPP : SmsConstants.FORMAT_3GPP2;
            message = createFromPdu(pdu, format);
        }
        return message;
    }

    /**
     * Create an SmsMessage from a raw PDU with the specified message format. The
     * message format is passed in the
     * {@link android.provider.Telephony.Sms.Intents#SMS_RECEIVED_ACTION} as the {@code format}
     * String extra, and will be either "3gpp" for GSM/UMTS/LTE messages in 3GPP format
     * or "3gpp2" for CDMA/LTE messages in 3GPP2 format.
     *
     * @param pdu the message PDU from the
     * {@link android.provider.Telephony.Sms.Intents#SMS_RECEIVED_ACTION} intent
     * @param format the format extra from the
     * {@link android.provider.Telephony.Sms.Intents#SMS_RECEIVED_ACTION} intent
     */
    public static SmsMessage createFromPdu(byte[] pdu, String format) {
        SmsMessageBase wrappedMessage;
        if (pdu == null) {
            Rlog.i(LOG_TAG, "createFromPdu(): pdu is null");
            return null;
        }
        if (SmsConstants.FORMAT_3GPP2.equals(format)) {
            wrappedMessage = com.android.internal.telephony.cdma.SmsMessage.createFromPdu(pdu);
        } else if (SmsConstants.FORMAT_3GPP.equals(format)) {
            wrappedMessage = com.android.internal.telephony.gsm.SmsMessage.createFromPdu(pdu);
        } else {
            Rlog.e(LOG_TAG, "createFromPdu(): unsupported message format " + format);
            return null;
        }

        if (wrappedMessage != null) {
            return new SmsMessage(wrappedMessage);
        } else {
            Rlog.e(LOG_TAG, "createFromPdu(): wrappedMessage is null");
            return null;
        }
    }

    /**
     * TS 27.005 3.4.1 lines[0] and lines[1] are the two lines read from the
     * +CMT unsolicited response (PDU mode, of course)
     *  +CMT: [&lt;alpha>],<length><CR><LF><pdu>
     *
     * Only public for debugging and for RIL
     *
     * {@hide}
     */
    public static SmsMessage newFromCMT(byte[] pdu) {
        // received SMS in 3GPP format
        SmsMessageBase wrappedMessage =
                com.android.internal.telephony.gsm.SmsMessage.newFromCMT(pdu);

        if (wrappedMessage != null) {
            return new SmsMessage(wrappedMessage);
        } else {
            Rlog.e(LOG_TAG, "newFromCMT(): wrappedMessage is null");
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
        SmsMessageBase wrappedMessage;

        if (isCdmaVoice()) {
            wrappedMessage = com.android.internal.telephony.cdma.SmsMessage.createFromEfRecord(
                    index, data);
        } else {
            wrappedMessage = com.android.internal.telephony.gsm.SmsMessage.createFromEfRecord(
                    index, data);
        }

        if (wrappedMessage != null) {
            return new SmsMessage(wrappedMessage);
        } else {
            Rlog.e(LOG_TAG, "createFromEfRecord(): wrappedMessage is null");
            return null;
        }
    }

    /**
     * Create an SmsMessage from an SMS EF record.
     *
     * @param index Index of SMS record. This should be index in ArrayList
     *              returned by SmsManager.getAllMessagesFromSim + 1.
     * @param data Record data.
     * @param subId Subscription Id of the SMS
     * @return An SmsMessage representing the record.
     *
     * @hide
     */
    public static SmsMessage createFromEfRecord(int index, byte[] data, int subId) {
        SmsMessageBase wrappedMessage;

        if (isCdmaVoice(subId)) {
            wrappedMessage = com.android.internal.telephony.cdma.SmsMessage.createFromEfRecord(
                    index, data);
        } else {
            wrappedMessage = com.android.internal.telephony.gsm.SmsMessage.createFromEfRecord(
                    index, data);
        }

        return wrappedMessage != null ? new SmsMessage(wrappedMessage) : null;
    }

    /**
     * Get the TP-Layer-Length for the given SMS-SUBMIT PDU Basically, the
     * length in bytes (not hex chars) less the SMSC header
     *
     * FIXME: This method is only used by a CTS test case that isn't run on CDMA devices.
     * We should probably deprecate it and remove the obsolete test case.
     */
    public static int getTPLayerLengthForPDU(String pdu) {
        if (isCdmaVoice()) {
            return com.android.internal.telephony.cdma.SmsMessage.getTPLayerLengthForPDU(pdu);
        } else {
            return com.android.internal.telephony.gsm.SmsMessage.getTPLayerLengthForPDU(pdu);
        }
    }

    /*
     * TODO(cleanup): It would make some sense if the result of
     * preprocessing a message to determine the proper encoding (i.e.
     * the resulting data structure from calculateLength) could be
     * passed as an argument to the actual final encoding function.
     * This would better ensure that the logic behind size calculation
     * actually matched the encoding.
     */

    /**
     * Calculates the number of SMS's required to encode the message body and
     * the number of characters remaining until the next message.
     *
     * @param msgBody the message to encode
     * @param use7bitOnly if true, characters that are not part of the
     *         radio-specific 7-bit encoding are counted as single
     *         space chars.  If false, and if the messageBody contains
     *         non-7-bit encodable characters, length is calculated
     *         using a 16-bit encoding.
     * @return an int[4] with int[0] being the number of SMS's
     *         required, int[1] the number of code units used, and
     *         int[2] is the number of code units remaining until the
     *         next message. int[3] is an indicator of the encoding
     *         code unit size (see the ENCODING_* definitions in SmsConstants)
     */
    public static int[] calculateLength(CharSequence msgBody, boolean use7bitOnly) {
        // this function is for MO SMS
        TextEncodingDetails ted = (useCdmaFormatForMoSms()) ?
            com.android.internal.telephony.cdma.SmsMessage.calculateLength(msgBody, use7bitOnly,
                    true) :
            com.android.internal.telephony.gsm.SmsMessage.calculateLength(msgBody, use7bitOnly);
        int ret[] = new int[4];
        ret[0] = ted.msgCount;
        ret[1] = ted.codeUnitCount;
        ret[2] = ted.codeUnitsRemaining;
        ret[3] = ted.codeUnitSize;
        return ret;
    }

    /**
     * Divide a message text into several fragments, none bigger than
     * the maximum SMS message text size.
     *
     * @param text text, must not be null.
     * @return an <code>ArrayList</code> of strings that, in order,
     *   comprise the original msg text
     *
     * @hide
     */
    public static ArrayList<String> fragmentText(String text) {
        // This function is for MO SMS
        TextEncodingDetails ted = (useCdmaFormatForMoSms()) ?
            com.android.internal.telephony.cdma.SmsMessage.calculateLength(text, false, true) :
            com.android.internal.telephony.gsm.SmsMessage.calculateLength(text, false);

        // TODO(cleanup): The code here could be rolled into the logic
        // below cleanly if these MAX_* constants were defined more
        // flexibly...

        int limit;
        if (ted.codeUnitSize == SmsConstants.ENCODING_7BIT) {
            int udhLength;
            if (ted.languageTable != 0 && ted.languageShiftTable != 0) {
                udhLength = GsmAlphabet.UDH_SEPTET_COST_TWO_SHIFT_TABLES;
            } else if (ted.languageTable != 0 || ted.languageShiftTable != 0) {
                udhLength = GsmAlphabet.UDH_SEPTET_COST_ONE_SHIFT_TABLE;
            } else {
                udhLength = 0;
            }

            if (ted.msgCount > 1) {
                udhLength += GsmAlphabet.UDH_SEPTET_COST_CONCATENATED_MESSAGE;
            }

            if (udhLength != 0) {
                udhLength += GsmAlphabet.UDH_SEPTET_COST_LENGTH;
            }

            limit = SmsConstants.MAX_USER_DATA_SEPTETS - udhLength;
        } else {
            if (ted.msgCount > 1) {
                limit = SmsConstants.MAX_USER_DATA_BYTES_WITH_HEADER;
                // If EMS is not supported, break down EMS into single segment SMS
                // and add page info " x/y".
                // In the case of UCS2 encoding, we need 8 bytes for this,
                // but we only have 6 bytes from UDH, so truncate the limit for
                // each segment by 2 bytes (1 char).
                // Make sure total number of segments is less than 10.
                if (!hasEmsSupport() && ted.msgCount < 10) {
                    limit -= 2;
                }
            } else {
                limit = SmsConstants.MAX_USER_DATA_BYTES;
            }
        }

        String newMsgBody = null;
        Resources r = Resources.getSystem();
        if (r.getBoolean(com.android.internal.R.bool.config_sms_force_7bit_encoding)) {
            newMsgBody  = Sms7BitEncodingTranslator.translate(text);
        }
        if (TextUtils.isEmpty(newMsgBody)) {
            newMsgBody = text;
        }
        int pos = 0;  // Index in code units.
        int textLen = newMsgBody.length();
        ArrayList<String> result = new ArrayList<String>(ted.msgCount);
        while (pos < textLen) {
            int nextPos = 0;  // Counts code units.
            if (ted.codeUnitSize == SmsConstants.ENCODING_7BIT) {
                if (useCdmaFormatForMoSms() && ted.msgCount == 1) {
                    // For a singleton CDMA message, the encoding must be ASCII...
                    nextPos = pos + Math.min(limit, textLen - pos);
                } else {
                    // For multi-segment messages, CDMA 7bit equals GSM 7bit encoding (EMS mode).
                    nextPos = GsmAlphabet.findGsmSeptetLimitIndex(newMsgBody, pos, limit,
                            ted.languageTable, ted.languageShiftTable);
                }
            } else {  // Assume unicode.
                nextPos = SmsMessageBase.findNextUnicodePosition(pos, limit, newMsgBody);
            }
            if ((nextPos <= pos) || (nextPos > textLen)) {
                Rlog.e(LOG_TAG, "fragmentText failed (" + pos + " >= " + nextPos + " or " +
                          nextPos + " >= " + textLen + ")");
                break;
            }
            result.add(newMsgBody.substring(pos, nextPos));
            pos = nextPos;
        }
        return result;
    }

    /**
     * Calculates the number of SMS's required to encode the message body and
     * the number of characters remaining until the next message, given the
     * current encoding.
     *
     * @param messageBody the message to encode
     * @param use7bitOnly if true, characters that are not part of the radio
     *         specific (GSM / CDMA) alphabet encoding are converted to as a
     *         single space characters. If false, a messageBody containing
     *         non-GSM or non-CDMA alphabet characters are encoded using
     *         16-bit encoding.
     * @return an int[4] with int[0] being the number of SMS's required, int[1]
     *         the number of code units used, and int[2] is the number of code
     *         units remaining until the next message. int[3] is the encoding
     *         type that should be used for the message.
     */
    public static int[] calculateLength(String messageBody, boolean use7bitOnly) {
        return calculateLength((CharSequence)messageBody, use7bitOnly);
    }

    /*
     * TODO(cleanup): It looks like there is now no useful reason why
     * apps should generate pdus themselves using these routines,
     * instead of handing the raw data to SMSDispatcher (and thereby
     * have the phone process do the encoding).  Moreover, CDMA now
     * has shared state (in the form of the msgId system property)
     * which can only be modified by the phone process, and hence
     * makes the output of these routines incorrect.  Since they now
     * serve no purpose, they should probably just return null
     * directly, and be deprecated.  Going further in that direction,
     * the above parsers of serialized pdu data should probably also
     * be gotten rid of, hiding all but the necessarily visible
     * structured data from client apps.  A possible concern with
     * doing this is that apps may be using these routines to generate
     * pdus that are then sent elsewhere, some network server, for
     * example, and that always returning null would thereby break
     * otherwise useful apps.
     */

    /**
     * Get an SMS-SUBMIT PDU for a destination address and a message.
     * This method will not attempt to use any GSM national language 7 bit encodings.
     *
     * @param scAddress Service Centre address.  Null means use default.
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     */
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destinationAddress, String message, boolean statusReportRequested) {
        return getSubmitPdu(scAddress, destinationAddress, message, statusReportRequested,
                SubscriptionManager.getDefaultSmsSubscriptionId());
    }

    /**
     * Get an SMS-SUBMIT PDU for a destination address and a message.
     * This method will not attempt to use any GSM national language 7 bit encodings.
     *
     * @param scAddress Service Centre address.  Null means use default.
     * @param destinationAddress the address of the destination for the message.
     * @param message String representation of the message payload.
     * @param statusReportRequested Indicates whether a report is requested for this message.
     * @param subId Subscription of the message
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     * @hide
     */
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destinationAddress, String message, boolean statusReportRequested, int subId) {
        SubmitPduBase spb;
        if (useCdmaFormatForMoSms(subId)) {
            spb = com.android.internal.telephony.cdma.SmsMessage.getSubmitPdu(scAddress,
                    destinationAddress, message, statusReportRequested, null);
        } else {
            spb = com.android.internal.telephony.gsm.SmsMessage.getSubmitPdu(scAddress,
                    destinationAddress, message, statusReportRequested);
        }

        return new SubmitPdu(spb);
    }

    /**
     * Get an SMS-SUBMIT PDU for a data message to a destination address &amp; port.
     * This method will not attempt to use any GSM national language 7 bit encodings.
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
            String destinationAddress, short destinationPort, byte[] data,
            boolean statusReportRequested) {
        SubmitPduBase spb;

        if (useCdmaFormatForMoSms()) {
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
     */
    public String getServiceCenterAddress() {
        return mWrappedSmsMessage.getServiceCenterAddress();
    }

    /**
     * Returns the originating address (sender) of this SMS message in String
     * form or null if unavailable.
     *
     * <p>If the address is a GSM-formatted address, it will be in a format specified by 3GPP
     * 23.040 Sec 9.1.2.5. If it is a CDMA address, it will be a format specified by 3GPP2
     * C.S005-D Table 2.7.1.3.2.4-2. The choice of format is carrier-specific, so callers of the
     * should be careful to avoid assumptions about the returned content.
     *
     * @return a String representation of the address; null if unavailable.
     */
    @Nullable
    public String getOriginatingAddress() {
        return mWrappedSmsMessage.getOriginatingAddress();
    }

    /**
     * Returns the originating address, or email from address if this message
     * was from an email gateway. Returns null if originating address
     * unavailable.
     */
    public String getDisplayOriginatingAddress() {
        return mWrappedSmsMessage.getDisplayOriginatingAddress();
    }

    /**
     * Returns the message body as a String, if it exists and is text based.
     * @return message body is there is one, otherwise null
     */
    public String getMessageBody() {
        return mWrappedSmsMessage.getMessageBody();
    }

    /**
     * Returns the class of this message.
     */
    public MessageClass getMessageClass() {
        switch(mWrappedSmsMessage.getMessageClass()) {
            case CLASS_0: return MessageClass.CLASS_0;
            case CLASS_1: return MessageClass.CLASS_1;
            case CLASS_2: return MessageClass.CLASS_2;
            case CLASS_3: return MessageClass.CLASS_3;
            default: return MessageClass.UNKNOWN;

        }
    }

    /**
     * Returns the message body, or email message body if this message was from
     * an email gateway. Returns null if message body unavailable.
     */
    public String getDisplayMessageBody() {
        return mWrappedSmsMessage.getDisplayMessageBody();
    }

    /**
     * Unofficial convention of a subject line enclosed in parens empty string
     * if not present
     */
    public String getPseudoSubject() {
        return mWrappedSmsMessage.getPseudoSubject();
    }

    /**
     * Returns the service centre timestamp in currentTimeMillis() format
     */
    public long getTimestampMillis() {
        return mWrappedSmsMessage.getTimestampMillis();
    }

    /**
     * Returns true if message is an email.
     *
     * @return true if this message came through an email gateway and email
     *         sender / subject / parsed body are available
     */
    public boolean isEmail() {
        return mWrappedSmsMessage.isEmail();
    }

     /**
     * @return if isEmail() is true, body of the email sent through the gateway.
     *         null otherwise
     */
    public String getEmailBody() {
        return mWrappedSmsMessage.getEmailBody();
    }

    /**
     * @return if isEmail() is true, email from address of email sent through
     *         the gateway. null otherwise
     */
    public String getEmailFrom() {
        return mWrappedSmsMessage.getEmailFrom();
    }

    /**
     * Get protocol identifier.
     */
    public int getProtocolIdentifier() {
        return mWrappedSmsMessage.getProtocolIdentifier();
    }

    /**
     * See TS 23.040 9.2.3.9 returns true if this is a "replace short message"
     * SMS
     */
    public boolean isReplace() {
        return mWrappedSmsMessage.isReplace();
    }

    /**
     * Returns true for CPHS MWI toggle message.
     *
     * @return true if this is a CPHS MWI toggle message See CPHS 4.2 section
     *         B.4.2
     */
    public boolean isCphsMwiMessage() {
        return mWrappedSmsMessage.isCphsMwiMessage();
    }

    /**
     * returns true if this message is a CPHS voicemail / message waiting
     * indicator (MWI) clear message
     */
    public boolean isMWIClearMessage() {
        return mWrappedSmsMessage.isMWIClearMessage();
    }

    /**
     * returns true if this message is a CPHS voicemail / message waiting
     * indicator (MWI) set message
     */
    public boolean isMWISetMessage() {
        return mWrappedSmsMessage.isMWISetMessage();
    }

    /**
     * returns true if this message is a "Message Waiting Indication Group:
     * Discard Message" notification and should not be stored.
     */
    public boolean isMwiDontStore() {
        return mWrappedSmsMessage.isMwiDontStore();
    }

    /**
     * returns the user data section minus the user data header if one was
     * present.
     */
    public byte[] getUserData() {
        return mWrappedSmsMessage.getUserData();
    }

    /**
     * Returns the raw PDU for the message.
     *
     * @return the raw PDU for the message.
     */
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
     * @deprecated Use getStatusOnIcc instead.
     */
    @Deprecated public int getStatusOnSim() {
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
     */
    public int getStatusOnIcc() {
        return mWrappedSmsMessage.getStatusOnIcc();
    }

    /**
     * Returns the record index of the message on the SIM (1-based index).
     * @return the record index of the message on the SIM, or -1 if this
     *         SmsMessage was not created from a SIM SMS EF record.
     * @deprecated Use getIndexOnIcc instead.
     */
    @Deprecated public int getIndexOnSim() {
        return mWrappedSmsMessage.getIndexOnIcc();
    }

    /**
     * Returns the record index of the message on the ICC (1-based index).
     * @return the record index of the message on the ICC, or -1 if this
     *         SmsMessage was not created from a ICC SMS EF record.
     */
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
     */
    public int getStatus() {
        return mWrappedSmsMessage.getStatus();
    }

    /**
     * Return true iff the message is a SMS-STATUS-REPORT message.
     */
    public boolean isStatusReportMessage() {
        return mWrappedSmsMessage.isStatusReportMessage();
    }

    /**
     * Returns true iff the <code>TP-Reply-Path</code> bit is set in
     * this message.
     */
    public boolean isReplyPathPresent() {
        return mWrappedSmsMessage.isReplyPathPresent();
    }

    /**
     * Determines whether or not to use CDMA format for MO SMS.
     * If SMS over IMS is supported, then format is based on IMS SMS format,
     * otherwise format is based on current phone type.
     *
     * @return true if Cdma format should be used for MO SMS, false otherwise.
     */
    private static boolean useCdmaFormatForMoSms() {
        // IMS is registered with SMS support, check the SMS format supported
        return useCdmaFormatForMoSms(SubscriptionManager.getDefaultSmsSubscriptionId());
    }

    /**
     * Determines whether or not to use CDMA format for MO SMS.
     * If SMS over IMS is supported, then format is based on IMS SMS format,
     * otherwise format is based on current phone type.
     *
     * @param subId Subscription for which phone type is returned.
     *
     * @return true if Cdma format should be used for MO SMS, false otherwise.
     */
    private static boolean useCdmaFormatForMoSms(int subId) {
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
        if (!smsManager.isImsSmsSupported()) {
            // use Voice technology to determine SMS format.
            return isCdmaVoice(subId);
        }
        // IMS is registered with SMS support, check the SMS format supported
        return (SmsConstants.FORMAT_3GPP2.equals(smsManager.getImsSmsFormat()));
    }

    /**
     * Determines whether or not to current phone type is cdma.
     *
     * @return true if current phone type is cdma, false otherwise.
     */
    private static boolean isCdmaVoice() {
        return isCdmaVoice(SubscriptionManager.getDefaultSmsSubscriptionId());
    }

     /**
      * Determines whether or not to current phone type is cdma
      *
      * @return true if current phone type is cdma, false otherwise.
      */
     private static boolean isCdmaVoice(int subId) {
         int activePhone = TelephonyManager.getDefault().getCurrentPhoneType(subId);
         return (PHONE_TYPE_CDMA == activePhone);
   }

    /**
     * Decide if the carrier supports long SMS.
     * {@hide}
     */
    public static boolean hasEmsSupport() {
        if (!isNoEmsSupportConfigListExisted()) {
            return true;
        }

        String simOperator;
        String gid;
        final long identity = Binder.clearCallingIdentity();
        try {
            simOperator = TelephonyManager.getDefault().getSimOperatorNumeric();
            gid = TelephonyManager.getDefault().getGroupIdLevel1();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        if (!TextUtils.isEmpty(simOperator)) {
            for (NoEmsSupportConfig currentConfig : mNoEmsSupportConfigList) {
                if (simOperator.startsWith(currentConfig.mOperatorNumber) &&
                        (TextUtils.isEmpty(currentConfig.mGid1) ||
                                (!TextUtils.isEmpty(currentConfig.mGid1) &&
                                        currentConfig.mGid1.equalsIgnoreCase(gid)))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Check where to add " x/y" in each SMS segment, begin or end.
     * {@hide}
     */
    public static boolean shouldAppendPageNumberAsPrefix() {
        if (!isNoEmsSupportConfigListExisted()) {
            return false;
        }

        String simOperator;
        String gid;
        final long identity = Binder.clearCallingIdentity();
        try {
            simOperator = TelephonyManager.getDefault().getSimOperatorNumeric();
            gid = TelephonyManager.getDefault().getGroupIdLevel1();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        for (NoEmsSupportConfig currentConfig : mNoEmsSupportConfigList) {
            if (simOperator.startsWith(currentConfig.mOperatorNumber) &&
                (TextUtils.isEmpty(currentConfig.mGid1) ||
                (!TextUtils.isEmpty(currentConfig.mGid1)
                && currentConfig.mGid1.equalsIgnoreCase(gid)))) {
                return currentConfig.mIsPrefix;
            }
        }
        return false;
    }

    private static class NoEmsSupportConfig {
        String mOperatorNumber;
        String mGid1;
        boolean mIsPrefix;

        public NoEmsSupportConfig(String[] config) {
            mOperatorNumber = config[0];
            mIsPrefix = "prefix".equals(config[1]);
            mGid1 = config.length > 2 ? config[2] : null;
        }

        @Override
        public String toString() {
            return "NoEmsSupportConfig { mOperatorNumber = " + mOperatorNumber
                    + ", mIsPrefix = " + mIsPrefix + ", mGid1 = " + mGid1 + " }";
        }
    }

    private static NoEmsSupportConfig[] mNoEmsSupportConfigList = null;
    private static boolean mIsNoEmsSupportConfigListLoaded = false;

    private static boolean isNoEmsSupportConfigListExisted() {
        if (!mIsNoEmsSupportConfigListLoaded) {
            Resources r = Resources.getSystem();
            if (r != null) {
                String[] listArray = r.getStringArray(
                        com.android.internal.R.array.no_ems_support_sim_operators);
                if ((listArray != null) && (listArray.length > 0)) {
                    mNoEmsSupportConfigList = new NoEmsSupportConfig[listArray.length];
                    for (int i=0; i<listArray.length; i++) {
                        mNoEmsSupportConfigList[i] = new NoEmsSupportConfig(listArray[i].split(";"));
                    }
                }
                mIsNoEmsSupportConfigListLoaded = true;
            }
        }

        if (mNoEmsSupportConfigList != null && mNoEmsSupportConfigList.length != 0) {
            return true;
        }

        return false;
    }
}
