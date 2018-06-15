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

package com.android.internal.telephony.cdma;

import android.content.res.Resources;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
import android.telephony.SmsCbLocation;
import android.telephony.SmsCbMessage;
import android.telephony.cdma.CdmaSmsCbProgramData;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.GsmAlphabet.TextEncodingDetails;
import com.android.internal.telephony.Sms7BitEncodingTranslator;
import com.android.internal.telephony.SmsAddress;
import com.android.internal.telephony.SmsConstants;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsMessageBase;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.cdma.sms.BearerData;
import com.android.internal.telephony.cdma.sms.CdmaSmsAddress;
import com.android.internal.telephony.cdma.sms.CdmaSmsSubaddress;
import com.android.internal.telephony.cdma.sms.SmsEnvelope;
import com.android.internal.telephony.cdma.sms.UserData;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.util.BitwiseInputStream;
import com.android.internal.util.HexDump;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * TODO(cleanup): these constants are disturbing... are they not just
 * different interpretations on one number?  And if we did not have
 * terrible class name overlap, they would not need to be directly
 * imported like this.  The class in this file could just as well be
 * named CdmaSmsMessage, could it not?
 */

/**
 * TODO(cleanup): internally returning null in many places makes
 * debugging very hard (among many other reasons) and should be made
 * more meaningful (replaced with exceptions for example).  Null
 * returns should only occur at the very outside of the module/class
 * scope.
 */

/**
 * A Short Message Service message.
 *
 */
public class SmsMessage extends SmsMessageBase {
    static final String LOG_TAG = "SmsMessage";
    static private final String LOGGABLE_TAG = "CDMA:SMS";
    private static final boolean VDBG = false;

    private final static byte TELESERVICE_IDENTIFIER                    = 0x00;
    private final static byte SERVICE_CATEGORY                          = 0x01;
    private final static byte ORIGINATING_ADDRESS                       = 0x02;
    private final static byte ORIGINATING_SUB_ADDRESS                   = 0x03;
    private final static byte DESTINATION_ADDRESS                       = 0x04;
    private final static byte DESTINATION_SUB_ADDRESS                   = 0x05;
    private final static byte BEARER_REPLY_OPTION                       = 0x06;
    private final static byte CAUSE_CODES                               = 0x07;
    private final static byte BEARER_DATA                               = 0x08;

    /**
     *  Status of a previously submitted SMS.
     *  This field applies to SMS Delivery Acknowledge messages. 0 indicates success;
     *  Here, the error class is defined by the bits from 9-8, the status code by the bits from 7-0.
     *  See C.S0015-B, v2.0, 4.5.21 for a detailed description of possible values.
     */
    private int status;

    /** Specifies if a return of an acknowledgment is requested for send SMS */
    private static final int RETURN_NO_ACK  = 0;
    private static final int RETURN_ACK     = 1;

    /**
     * Supported priority modes for CDMA SMS messages
     * (See 3GPP2 C.S0015-B, v2.0, table 4.5.9-1)
     */
    private static final int PRIORITY_NORMAL        = 0x0;
    private static final int PRIORITY_INTERACTIVE   = 0x1;
    private static final int PRIORITY_URGENT        = 0x2;
    private static final int PRIORITY_EMERGENCY     = 0x3;

    private SmsEnvelope mEnvelope;
    private BearerData mBearerData;

    /** @hide */
    public SmsMessage(SmsAddress addr, SmsEnvelope env) {
        mOriginatingAddress = addr;
        mEnvelope = env;
        createPdu();
    }

    public SmsMessage() {}

    public static class SubmitPdu extends SubmitPduBase {
    }

    /**
     * Create an SmsMessage from a raw PDU.
     * Note: In CDMA the PDU is just a byte representation of the received Sms.
     */
    public static SmsMessage createFromPdu(byte[] pdu) {
        SmsMessage msg = new SmsMessage();

        try {
            msg.parsePdu(pdu);
            return msg;
        } catch (RuntimeException ex) {
            Rlog.e(LOG_TAG, "SMS PDU parsing failed: ", ex);
            return null;
        } catch (OutOfMemoryError e) {
            Log.e(LOG_TAG, "SMS PDU parsing failed with out of memory: ", e);
            return null;
        }
    }

    /**
     * Create an SmsMessage from an SMS EF record.
     *
     * @param index Index of SMS record. This should be index in ArrayList
     *              returned by RuimSmsInterfaceManager.getAllMessagesFromIcc + 1.
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
            // See 3GPP2 C.S0023 3.4.27
            if ((data[0] & 1) == 0) {
                Rlog.w(LOG_TAG, "SMS parsing failed: Trying to parse a free record");
                return null;
            } else {
                msg.mStatusOnIcc = data[0] & 0x07;
            }

            // Second byte is the MSG_LEN, length of the message
            // See 3GPP2 C.S0023 3.4.27
            int size = data[1] & 0xFF;

            // Note: Data may include trailing FF's.  That's OK; message
            // should still parse correctly.
            byte[] pdu = new byte[size];
            System.arraycopy(data, 2, pdu, 0, size);
            // the message has to be parsed before it can be displayed
            // see gsm.SmsMessage
            msg.parsePduFromEfRecord(pdu);
            return msg;
        } catch (RuntimeException ex) {
            Rlog.e(LOG_TAG, "SMS PDU parsing failed: ", ex);
            return null;
        }

    }

    /**
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    public static int getTPLayerLengthForPDU(String pdu) {
        Rlog.w(LOG_TAG, "getTPLayerLengthForPDU: is not supported in CDMA mode.");
        return 0;
    }

    /**
     * TODO(cleanup): why do getSubmitPdu methods take an scAddr input
     * and do nothing with it?  GSM allows us to specify a SC (eg,
     * when responding to an SMS that explicitly requests the response
     * is sent to a specific SC), or pass null to use the default
     * value.  Is there no similar notion in CDMA? Or do we just not
     * have it hooked up?
     */

    /**
     * Get an SMS-SUBMIT PDU for a destination address and a message
     *
     * @param scAddr                Service Centre address.  Null means use default.
     * @param destAddr              Address of the recipient.
     * @param message               String representation of the message payload.
     * @param statusReportRequested Indicates whether a report is requested for this message.
     * @param smsHeader             Array containing the data for the User Data Header, preceded
     *                              by the Element Identifiers.
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     * @hide
     */
    public static SubmitPdu getSubmitPdu(String scAddr, String destAddr, String message,
            boolean statusReportRequested, SmsHeader smsHeader) {
        return getSubmitPdu(scAddr, destAddr, message, statusReportRequested, smsHeader, -1);
    }

    /**
     * Get an SMS-SUBMIT PDU for a destination address and a message
     *
     * @param scAddr                Service Centre address.  Null means use default.
     * @param destAddr              Address of the recipient.
     * @param message               String representation of the message payload.
     * @param statusReportRequested Indicates whether a report is requested for this message.
     * @param smsHeader             Array containing the data for the User Data Header, preceded
     *                              by the Element Identifiers.
     * @param priority              Priority level of the message
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     * @hide
     */
    public static SubmitPdu getSubmitPdu(String scAddr, String destAddr, String message,
            boolean statusReportRequested, SmsHeader smsHeader, int priority) {

        /**
         * TODO(cleanup): Do we really want silent failure like this?
         * Would it not be much more reasonable to make sure we don't
         * call this function if we really want nothing done?
         */
        if (message == null || destAddr == null) {
            return null;
        }

        UserData uData = new UserData();
        uData.payloadStr = message;
        uData.userDataHeader = smsHeader;
        return privateGetSubmitPdu(destAddr, statusReportRequested, uData, priority);
    }

    /**
     * Get an SMS-SUBMIT PDU for a data message to a destination address and port.
     *
     * @param scAddr Service Centre address. null == use default
     * @param destAddr the address of the destination for the message
     * @param destPort the port to deliver the message to at the
     *        destination
     * @param data the data for the message
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     */
    public static SubmitPdu getSubmitPdu(String scAddr, String destAddr, int destPort,
            byte[] data, boolean statusReportRequested) {

        /**
         * TODO(cleanup): this is not a general-purpose SMS creation
         * method, but rather something specialized to messages
         * containing OCTET encoded (meaning non-human-readable) user
         * data.  The name should reflect that, and not just overload.
         */

        SmsHeader.PortAddrs portAddrs = new SmsHeader.PortAddrs();
        portAddrs.destPort = destPort;
        portAddrs.origPort = 0;
        portAddrs.areEightBits = false;

        SmsHeader smsHeader = new SmsHeader();
        smsHeader.portAddrs = portAddrs;

        UserData uData = new UserData();
        uData.userDataHeader = smsHeader;
        uData.msgEncoding = UserData.ENCODING_OCTET;
        uData.msgEncodingSet = true;
        uData.payload = data;

        return privateGetSubmitPdu(destAddr, statusReportRequested, uData);
    }

    /**
     * Get an SMS-SUBMIT PDU for a data message to a destination address &amp; port
     *
     * @param destAddr the address of the destination for the message
     * @param userData the data for the message
     * @param statusReportRequested Indicates whether a report is requested for this message.
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     */
    public static SubmitPdu getSubmitPdu(String destAddr, UserData userData,
            boolean statusReportRequested) {
        return privateGetSubmitPdu(destAddr, statusReportRequested, userData);
    }

    /**
     * Get an SMS-SUBMIT PDU for a data message to a destination address &amp; port
     *
     * @param destAddr the address of the destination for the message
     * @param userData the data for the message
     * @param statusReportRequested Indicates whether a report is requested for this message.
     * @param priority Priority level of the message
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     */
    public static SubmitPdu getSubmitPdu(String destAddr, UserData userData,
            boolean statusReportRequested, int priority) {
        return privateGetSubmitPdu(destAddr, statusReportRequested, userData, priority);
    }

    /**
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    @Override
    public int getProtocolIdentifier() {
        Rlog.w(LOG_TAG, "getProtocolIdentifier: is not supported in CDMA mode.");
        // (3GPP TS 23.040): "no interworking, but SME to SME protocol":
        return 0;
    }

    /**
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    @Override
    public boolean isReplace() {
        Rlog.w(LOG_TAG, "isReplace: is not supported in CDMA mode.");
        return false;
    }

    /**
     * {@inheritDoc}
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    @Override
    public boolean isCphsMwiMessage() {
        Rlog.w(LOG_TAG, "isCphsMwiMessage: is not supported in CDMA mode.");
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMWIClearMessage() {
        return ((mBearerData != null) && (mBearerData.numberOfMessages == 0));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMWISetMessage() {
        return ((mBearerData != null) && (mBearerData.numberOfMessages > 0));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMwiDontStore() {
        return ((mBearerData != null) &&
                (mBearerData.numberOfMessages > 0) &&
                (mBearerData.userData == null));
    }

    /**
     * Returns the status for a previously submitted message.
     * For not interfering with status codes from GSM, this status code is
     * shifted to the bits 31-16.
     */
    @Override
    public int getStatus() {
        return (status << 16);
    }

    /** Return true iff the bearer data message type is DELIVERY_ACK. */
    @Override
    public boolean isStatusReportMessage() {
        return (mBearerData.messageType == BearerData.MESSAGE_TYPE_DELIVERY_ACK);
    }

    /**
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    @Override
    public boolean isReplyPathPresent() {
        Rlog.w(LOG_TAG, "isReplyPathPresent: is not supported in CDMA mode.");
        return false;
    }

    /**
     * Calculate the number of septets needed to encode the message.
     *
     * @param messageBody the message to encode
     * @param use7bitOnly ignore (but still count) illegal characters if true
     * @param isEntireMsg indicates if this is entire msg or a segment in multipart msg
     * @return TextEncodingDetails
     */
    public static TextEncodingDetails calculateLength(CharSequence messageBody,
            boolean use7bitOnly, boolean isEntireMsg) {
        CharSequence newMsgBody = null;
        Resources r = Resources.getSystem();
        if (r.getBoolean(com.android.internal.R.bool.config_sms_force_7bit_encoding)) {
            newMsgBody = Sms7BitEncodingTranslator.translate(messageBody, true);
        }
        if (TextUtils.isEmpty(newMsgBody)) {
            newMsgBody = messageBody;
        }
        return BearerData.calcTextEncodingDetails(newMsgBody, use7bitOnly, isEntireMsg);
    }

    /**
     * Returns the teleservice type of the message.
     * @return the teleservice:
     *  {@link com.android.internal.telephony.cdma.sms.SmsEnvelope#TELESERVICE_NOT_SET},
     *  {@link com.android.internal.telephony.cdma.sms.SmsEnvelope#TELESERVICE_WMT},
     *  {@link com.android.internal.telephony.cdma.sms.SmsEnvelope#TELESERVICE_WEMT},
     *  {@link com.android.internal.telephony.cdma.sms.SmsEnvelope#TELESERVICE_VMN},
     *  {@link com.android.internal.telephony.cdma.sms.SmsEnvelope#TELESERVICE_WAP}
    */
    public int getTeleService() {
        return mEnvelope.teleService;
    }

    /**
     * Returns the message type of the message.
     * @return the message type:
     *  {@link com.android.internal.telephony.cdma.sms.SmsEnvelope#MESSAGE_TYPE_POINT_TO_POINT},
     *  {@link com.android.internal.telephony.cdma.sms.SmsEnvelope#MESSAGE_TYPE_BROADCAST},
     *  {@link com.android.internal.telephony.cdma.sms.SmsEnvelope#MESSAGE_TYPE_ACKNOWLEDGE},
    */
    public int getMessageType() {
        // NOTE: mEnvelope.messageType is not set correctly for cell broadcasts with some RILs.
        // Use the service category parameter to detect CMAS and other cell broadcast messages.
        if (mEnvelope.serviceCategory != 0) {
            return SmsEnvelope.MESSAGE_TYPE_BROADCAST;
        } else {
            return SmsEnvelope.MESSAGE_TYPE_POINT_TO_POINT;
        }
    }

    /**
     * Decodes pdu to an empty SMS object.
     * In the CDMA case the pdu is just an internal byte stream representation
     * of the SMS Java-object.
     * @see #createPdu()
     */
    private void parsePdu(byte[] pdu) {
        ByteArrayInputStream bais = new ByteArrayInputStream(pdu);
        DataInputStream dis = new DataInputStream(bais);
        int length;
        int bearerDataLength;
        SmsEnvelope env = new SmsEnvelope();
        CdmaSmsAddress addr = new CdmaSmsAddress();
        // We currently do not parse subaddress in PDU, but it is required when determining
        // fingerprint (see getIncomingSmsFingerprint()).
        CdmaSmsSubaddress subaddr = new CdmaSmsSubaddress();

        try {
            env.messageType = dis.readInt();
            env.teleService = dis.readInt();
            env.serviceCategory = dis.readInt();

            addr.digitMode = dis.readByte();
            addr.numberMode = dis.readByte();
            addr.ton = dis.readByte();
            addr.numberPlan = dis.readByte();

            length = dis.readUnsignedByte();
            addr.numberOfDigits = length;

            // sanity check on the length
            if (length > pdu.length) {
                throw new RuntimeException(
                        "createFromPdu: Invalid pdu, addr.numberOfDigits " + length
                        + " > pdu len " + pdu.length);
            }
            addr.origBytes = new byte[length];
            dis.read(addr.origBytes, 0, length); // digits

            env.bearerReply = dis.readInt();
            // CauseCode values:
            env.replySeqNo = dis.readByte();
            env.errorClass = dis.readByte();
            env.causeCode = dis.readByte();

            //encoded BearerData:
            bearerDataLength = dis.readInt();
            // sanity check on the length
            if (bearerDataLength > pdu.length) {
                throw new RuntimeException(
                        "createFromPdu: Invalid pdu, bearerDataLength " + bearerDataLength
                        + " > pdu len " + pdu.length);
            }
            env.bearerData = new byte[bearerDataLength];
            dis.read(env.bearerData, 0, bearerDataLength);
            dis.close();
        } catch (IOException ex) {
            throw new RuntimeException(
                    "createFromPdu: conversion from byte array to object failed: " + ex, ex);
        } catch (Exception ex) {
            Rlog.e(LOG_TAG, "createFromPdu: conversion from byte array to object failed: " + ex);
        }

        // link the filled objects to this SMS
        mOriginatingAddress = addr;
        env.origAddress = addr;
        env.origSubaddress = subaddr;
        mEnvelope = env;
        mPdu = pdu;

        parseSms();
    }

    /**
     * Decodes 3GPP2 sms stored in CSIM/RUIM cards As per 3GPP2 C.S0015-0
     */
    private void parsePduFromEfRecord(byte[] pdu) {
        ByteArrayInputStream bais = new ByteArrayInputStream(pdu);
        DataInputStream dis = new DataInputStream(bais);
        SmsEnvelope env = new SmsEnvelope();
        CdmaSmsAddress addr = new CdmaSmsAddress();
        CdmaSmsSubaddress subAddr = new CdmaSmsSubaddress();

        try {
            env.messageType = dis.readByte();

            while (dis.available() > 0) {
                int parameterId = dis.readByte();
                int parameterLen = dis.readUnsignedByte();
                byte[] parameterData = new byte[parameterLen];

                switch (parameterId) {
                    case TELESERVICE_IDENTIFIER:
                        /*
                         * 16 bit parameter that identifies which upper layer
                         * service access point is sending or should receive
                         * this message
                         */
                        env.teleService = dis.readUnsignedShort();
                        Rlog.i(LOG_TAG, "teleservice = " + env.teleService);
                        break;
                    case SERVICE_CATEGORY:
                        /*
                         * 16 bit parameter that identifies type of service as
                         * in 3GPP2 C.S0015-0 Table 3.4.3.2-1
                         */
                        env.serviceCategory = dis.readUnsignedShort();
                        break;
                    case ORIGINATING_ADDRESS:
                    case DESTINATION_ADDRESS:
                        dis.read(parameterData, 0, parameterLen);
                        BitwiseInputStream addrBis = new BitwiseInputStream(parameterData);
                        addr.digitMode = addrBis.read(1);
                        addr.numberMode = addrBis.read(1);
                        int numberType = 0;
                        if (addr.digitMode == CdmaSmsAddress.DIGIT_MODE_8BIT_CHAR) {
                            numberType = addrBis.read(3);
                            addr.ton = numberType;

                            if (addr.numberMode == CdmaSmsAddress.NUMBER_MODE_NOT_DATA_NETWORK)
                                addr.numberPlan = addrBis.read(4);
                        }

                        addr.numberOfDigits = addrBis.read(8);

                        byte[] data = new byte[addr.numberOfDigits];
                        byte b = 0x00;

                        if (addr.digitMode == CdmaSmsAddress.DIGIT_MODE_4BIT_DTMF) {
                            /* As per 3GPP2 C.S0005-0 Table 2.7.1.3.2.4-4 */
                            for (int index = 0; index < addr.numberOfDigits; index++) {
                                b = (byte) (0xF & addrBis.read(4));
                                // convert the value if it is 4-bit DTMF to 8
                                // bit
                                data[index] = convertDtmfToAscii(b);
                            }
                        } else if (addr.digitMode == CdmaSmsAddress.DIGIT_MODE_8BIT_CHAR) {
                            if (addr.numberMode == CdmaSmsAddress.NUMBER_MODE_NOT_DATA_NETWORK) {
                                for (int index = 0; index < addr.numberOfDigits; index++) {
                                    b = (byte) (0xFF & addrBis.read(8));
                                    data[index] = b;
                                }

                            } else if (addr.numberMode == CdmaSmsAddress.NUMBER_MODE_DATA_NETWORK) {
                                if (numberType == 2)
                                    Rlog.e(LOG_TAG, "TODO: Addr is email id");
                                else
                                    Rlog.e(LOG_TAG,
                                          "TODO: Addr is data network address");
                            } else {
                                Rlog.e(LOG_TAG, "Addr is of incorrect type");
                            }
                        } else {
                            Rlog.e(LOG_TAG, "Incorrect Digit mode");
                        }
                        addr.origBytes = data;
                        Rlog.pii(LOG_TAG, "Addr=" + addr.toString());
                        mOriginatingAddress = addr;
                        if (parameterId == DESTINATION_ADDRESS) {
                            // Original address awlays indicates one sender's address for 3GPP2
                            // Here add recipient address support along with 3GPP
                            mRecipientAddress = addr;
                        }
                        break;
                    case ORIGINATING_SUB_ADDRESS:
                    case DESTINATION_SUB_ADDRESS:
                        dis.read(parameterData, 0, parameterLen);
                        BitwiseInputStream subAddrBis = new BitwiseInputStream(parameterData);
                        subAddr.type = subAddrBis.read(3);
                        subAddr.odd = subAddrBis.readByteArray(1)[0];
                        int subAddrLen = subAddrBis.read(8);
                        byte[] subdata = new byte[subAddrLen];
                        for (int index = 0; index < subAddrLen; index++) {
                            b = (byte) (0xFF & subAddrBis.read(4));
                            // convert the value if it is 4-bit DTMF to 8 bit
                            subdata[index] = convertDtmfToAscii(b);
                        }
                        subAddr.origBytes = subdata;
                        break;
                    case BEARER_REPLY_OPTION:
                        dis.read(parameterData, 0, parameterLen);
                        BitwiseInputStream replyOptBis = new BitwiseInputStream(parameterData);
                        env.bearerReply = replyOptBis.read(6);
                        break;
                    case CAUSE_CODES:
                        dis.read(parameterData, 0, parameterLen);
                        BitwiseInputStream ccBis = new BitwiseInputStream(parameterData);
                        env.replySeqNo = ccBis.readByteArray(6)[0];
                        env.errorClass = ccBis.readByteArray(2)[0];
                        if (env.errorClass != 0x00)
                            env.causeCode = ccBis.readByteArray(8)[0];
                        break;
                    case BEARER_DATA:
                        dis.read(parameterData, 0, parameterLen);
                        env.bearerData = parameterData;
                        break;
                    default:
                        throw new Exception("unsupported parameterId (" + parameterId + ")");
                }
            }
            bais.close();
            dis.close();
        } catch (Exception ex) {
            Rlog.e(LOG_TAG, "parsePduFromEfRecord: conversion from pdu to SmsMessage failed" + ex);
        }

        // link the filled objects to this SMS
        mOriginatingAddress = addr;
        env.origAddress = addr;
        env.origSubaddress = subAddr;
        mEnvelope = env;
        mPdu = pdu;

        parseSms();
    }

    /**
     * Parses a SMS message from its BearerData stream.
     */
    public void parseSms() {
        // Message Waiting Info Record defined in 3GPP2 C.S-0005, 3.7.5.6
        // It contains only an 8-bit number with the number of messages waiting
        if (mEnvelope.teleService == SmsEnvelope.TELESERVICE_MWI) {
            mBearerData = new BearerData();
            if (mEnvelope.bearerData != null) {
                mBearerData.numberOfMessages = 0x000000FF & mEnvelope.bearerData[0];
            }
            if (VDBG) {
                Rlog.d(LOG_TAG, "parseSms: get MWI " +
                      Integer.toString(mBearerData.numberOfMessages));
            }
            return;
        }
        mBearerData = BearerData.decode(mEnvelope.bearerData);
        if (Rlog.isLoggable(LOGGABLE_TAG, Log.VERBOSE)) {
            Rlog.d(LOG_TAG, "MT raw BearerData = '" +
                      HexDump.toHexString(mEnvelope.bearerData) + "'");
            Rlog.d(LOG_TAG, "MT (decoded) BearerData = " + mBearerData);
        }
        mMessageRef = mBearerData.messageId;
        if (mBearerData.userData != null) {
            mUserData = mBearerData.userData.payload;
            mUserDataHeader = mBearerData.userData.userDataHeader;
            mMessageBody = mBearerData.userData.payloadStr;
        }

        if (mOriginatingAddress != null) {
            decodeSmsDisplayAddress(mOriginatingAddress);
            if (VDBG) Rlog.v(LOG_TAG, "SMS originating address: "
                    + mOriginatingAddress.address);
        }

        if (mRecipientAddress != null) {
            decodeSmsDisplayAddress(mRecipientAddress);
        }

        if (mBearerData.msgCenterTimeStamp != null) {
            mScTimeMillis = mBearerData.msgCenterTimeStamp.toMillis(true);
        }

        if (VDBG) Rlog.d(LOG_TAG, "SMS SC timestamp: " + mScTimeMillis);

        // Message Type (See 3GPP2 C.S0015-B, v2, 4.5.1)
        if (mBearerData.messageType == BearerData.MESSAGE_TYPE_DELIVERY_ACK) {
            // The BearerData MsgStatus subparameter should only be
            // included for DELIVERY_ACK messages.  If it occurred for
            // other messages, it would be unclear what the status
            // being reported refers to.  The MsgStatus subparameter
            // is primarily useful to indicate error conditions -- a
            // message without this subparameter is assumed to
            // indicate successful delivery (status == 0).
            if (! mBearerData.messageStatusSet) {
                Rlog.d(LOG_TAG, "DELIVERY_ACK message without msgStatus (" +
                        (mUserData == null ? "also missing" : "does have") +
                        " userData).");
                status = 0;
            } else {
                status = mBearerData.errorClass << 8;
                status |= mBearerData.messageStatus;
            }
        } else if (mBearerData.messageType != BearerData.MESSAGE_TYPE_DELIVER
                && mBearerData.messageType != BearerData.MESSAGE_TYPE_SUBMIT) {
            throw new RuntimeException("Unsupported message type: " + mBearerData.messageType);
        }

        if (mMessageBody != null) {
            if (VDBG) Rlog.v(LOG_TAG, "SMS message body: '" + mMessageBody + "'");
            parseMessageBody();
        } else if ((mUserData != null) && VDBG) {
            Rlog.v(LOG_TAG, "SMS payload: '" + IccUtils.bytesToHexString(mUserData) + "'");
        }
    }

    private void decodeSmsDisplayAddress(SmsAddress addr) {
        addr.address = new String(addr.origBytes);
        if (addr.ton == CdmaSmsAddress.TON_INTERNATIONAL_OR_IP) {
            if (addr.address.charAt(0) != '+') {
                addr.address = "+" + addr.address;
            }
        }
        Rlog.pii(LOG_TAG, " decodeSmsDisplayAddress = " + addr.address);
    }

    /**
     * Parses a broadcast SMS, possibly containing a CMAS alert.
     *
     * @param plmn the PLMN for a broadcast SMS
     */
    public SmsCbMessage parseBroadcastSms(String plmn) {
        BearerData bData = BearerData.decode(mEnvelope.bearerData, mEnvelope.serviceCategory);
        if (bData == null) {
            Rlog.w(LOG_TAG, "BearerData.decode() returned null");
            return null;
        }

        if (Rlog.isLoggable(LOGGABLE_TAG, Log.VERBOSE)) {
            Rlog.d(LOG_TAG, "MT raw BearerData = " + HexDump.toHexString(mEnvelope.bearerData));
        }

        SmsCbLocation location = new SmsCbLocation(plmn);

        return new SmsCbMessage(SmsCbMessage.MESSAGE_FORMAT_3GPP2,
                SmsCbMessage.GEOGRAPHICAL_SCOPE_PLMN_WIDE, bData.messageId, location,
                mEnvelope.serviceCategory, bData.getLanguage(), bData.userData.payloadStr,
                bData.priority, null, bData.cmasWarningInfo);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmsConstants.MessageClass getMessageClass() {
        if (BearerData.DISPLAY_MODE_IMMEDIATE == mBearerData.displayMode ) {
            return SmsConstants.MessageClass.CLASS_0;
        } else {
            return SmsConstants.MessageClass.UNKNOWN;
        }
    }

    /**
     * Calculate the next message id, starting at 1 and iteratively
     * incrementing within the range 1..65535 remembering the state
     * via a persistent system property.  (See C.S0015-B, v2.0,
     * 4.3.1.5) Since this routine is expected to be accessed via via
     * binder-call, and hence should be thread-safe, it has been
     * synchronized.
     */
    public synchronized static int getNextMessageId() {
        // Testing and dialog with partners has indicated that
        // msgId==0 is (sometimes?) treated specially by lower levels.
        // Specifically, the ID is not preserved for delivery ACKs.
        // Hence, avoid 0 -- constraining the range to 1..65535.
        int msgId = SystemProperties.getInt(TelephonyProperties.PROPERTY_CDMA_MSG_ID, 1);
        String nextMsgId = Integer.toString((msgId % 0xFFFF) + 1);
        try{
            SystemProperties.set(TelephonyProperties.PROPERTY_CDMA_MSG_ID, nextMsgId);
            if (Rlog.isLoggable(LOGGABLE_TAG, Log.VERBOSE)) {
                Rlog.d(LOG_TAG, "next " + TelephonyProperties.PROPERTY_CDMA_MSG_ID + " = " + nextMsgId);
                Rlog.d(LOG_TAG, "readback gets " +
                        SystemProperties.get(TelephonyProperties.PROPERTY_CDMA_MSG_ID));
            }
        } catch(RuntimeException ex) {
            Rlog.e(LOG_TAG, "set nextMessage ID failed: " + ex);
        }
        return msgId;
    }

    /**
     * Creates BearerData and Envelope from parameters for a Submit SMS.
     * @return byte stream for SubmitPdu.
     */
    private static SubmitPdu privateGetSubmitPdu(String destAddrStr, boolean statusReportRequested,
            UserData userData) {
        return privateGetSubmitPdu(destAddrStr, statusReportRequested, userData, -1);
    }

    /**
     * Creates BearerData and Envelope from parameters for a Submit SMS.
     * @return byte stream for SubmitPdu.
     */
    private static SubmitPdu privateGetSubmitPdu(String destAddrStr, boolean statusReportRequested,
            UserData userData, int priority) {

        /**
         * TODO(cleanup): give this function a more meaningful name.
         */

        /**
         * TODO(cleanup): Make returning null from the getSubmitPdu
         * variations meaningful -- clean up the error feedback
         * mechanism, and avoid null pointer exceptions.
         */

        /**
         * North America Plus Code :
         * Convert + code to 011 and dial out for international SMS
         */
        CdmaSmsAddress destAddr = CdmaSmsAddress.parse(
                PhoneNumberUtils.cdmaCheckAndProcessPlusCodeForSms(destAddrStr));
        if (destAddr == null) return null;

        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_SUBMIT;

        bearerData.messageId = getNextMessageId();

        bearerData.deliveryAckReq = statusReportRequested;
        bearerData.userAckReq = false;
        bearerData.readAckReq = false;
        bearerData.reportReq = false;
        if (priority >= PRIORITY_NORMAL && priority <= PRIORITY_EMERGENCY) {
            bearerData.priorityIndicatorSet = true;
            bearerData.priority = priority;
        }

        bearerData.userData = userData;

        byte[] encodedBearerData = BearerData.encode(bearerData);
        if (encodedBearerData == null) return null;
        if (Rlog.isLoggable(LOGGABLE_TAG, Log.VERBOSE)) {
            Rlog.d(LOG_TAG, "MO (encoded) BearerData = " + bearerData);
            Rlog.d(LOG_TAG, "MO raw BearerData = '" + HexDump.toHexString(encodedBearerData) + "'");
        }

        int teleservice = (bearerData.hasUserDataHeader
                && userData.msgEncoding != UserData.ENCODING_7BIT_ASCII)
                ? SmsEnvelope.TELESERVICE_WEMT : SmsEnvelope.TELESERVICE_WMT;

        SmsEnvelope envelope = new SmsEnvelope();
        envelope.messageType = SmsEnvelope.MESSAGE_TYPE_POINT_TO_POINT;
        envelope.teleService = teleservice;
        envelope.destAddress = destAddr;
        envelope.bearerReply = RETURN_ACK;
        envelope.bearerData = encodedBearerData;

        /**
         * TODO(cleanup): envelope looks to be a pointless class, get
         * rid of it.  Also -- most of the envelope fields set here
         * are ignored, why?
         */

        try {
            /**
             * TODO(cleanup): reference a spec and get rid of the ugly comments
             */
            ByteArrayOutputStream baos = new ByteArrayOutputStream(100);
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(envelope.teleService);
            dos.writeInt(0); //servicePresent
            dos.writeInt(0); //serviceCategory
            dos.write(destAddr.digitMode);
            dos.write(destAddr.numberMode);
            dos.write(destAddr.ton); // number_type
            dos.write(destAddr.numberPlan);
            dos.write(destAddr.numberOfDigits);
            dos.write(destAddr.origBytes, 0, destAddr.origBytes.length); // digits
            // Subaddress is not supported.
            dos.write(0); //subaddressType
            dos.write(0); //subaddr_odd
            dos.write(0); //subaddr_nbr_of_digits
            dos.write(encodedBearerData.length);
            dos.write(encodedBearerData, 0, encodedBearerData.length);
            dos.close();

            SubmitPdu pdu = new SubmitPdu();
            pdu.encodedMessage = baos.toByteArray();
            pdu.encodedScAddress = null;
            return pdu;
        } catch(IOException ex) {
            Rlog.e(LOG_TAG, "creating SubmitPdu failed: " + ex);
        }
        return null;
    }

    /**
     * Creates byte array (pseudo pdu) from SMS object.
     * Note: Do not call this method more than once per object!
     * @hide
     */
    public void createPdu() {
        SmsEnvelope env = mEnvelope;
        CdmaSmsAddress addr = env.origAddress;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(100);
        DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(baos));

        try {
            dos.writeInt(env.messageType);
            dos.writeInt(env.teleService);
            dos.writeInt(env.serviceCategory);

            dos.writeByte(addr.digitMode);
            dos.writeByte(addr.numberMode);
            dos.writeByte(addr.ton);
            dos.writeByte(addr.numberPlan);
            dos.writeByte(addr.numberOfDigits);
            dos.write(addr.origBytes, 0, addr.origBytes.length); // digits

            dos.writeInt(env.bearerReply);
            // CauseCode values:
            dos.writeByte(env.replySeqNo);
            dos.writeByte(env.errorClass);
            dos.writeByte(env.causeCode);
            //encoded BearerData:
            dos.writeInt(env.bearerData.length);
            dos.write(env.bearerData, 0, env.bearerData.length);
            dos.close();

            /**
             * TODO(cleanup) -- The mPdu field is managed in
             * a fragile manner, and it would be much nicer if
             * accessing the serialized representation used a less
             * fragile mechanism.  Maybe the getPdu method could
             * generate a representation if there was not yet one?
             */

            mPdu = baos.toByteArray();
        } catch (IOException ex) {
            Rlog.e(LOG_TAG, "createPdu: conversion from object to byte array failed: " + ex);
        }
    }

    /**
     * Converts a 4-Bit DTMF encoded symbol from the calling address number to ASCII character
     * @hide
     */
    public static byte convertDtmfToAscii(byte dtmfDigit) {
        byte asciiDigit;

        switch (dtmfDigit) {
        case  0: asciiDigit = 68; break; // 'D'
        case  1: asciiDigit = 49; break; // '1'
        case  2: asciiDigit = 50; break; // '2'
        case  3: asciiDigit = 51; break; // '3'
        case  4: asciiDigit = 52; break; // '4'
        case  5: asciiDigit = 53; break; // '5'
        case  6: asciiDigit = 54; break; // '6'
        case  7: asciiDigit = 55; break; // '7'
        case  8: asciiDigit = 56; break; // '8'
        case  9: asciiDigit = 57; break; // '9'
        case 10: asciiDigit = 48; break; // '0'
        case 11: asciiDigit = 42; break; // '*'
        case 12: asciiDigit = 35; break; // '#'
        case 13: asciiDigit = 65; break; // 'A'
        case 14: asciiDigit = 66; break; // 'B'
        case 15: asciiDigit = 67; break; // 'C'
        default:
            asciiDigit = 32; // Invalid DTMF code
            break;
        }

        return asciiDigit;
    }

    /** This function  shall be called to get the number of voicemails.
     * @hide
     */
    public int getNumOfVoicemails() {
        return mBearerData.numberOfMessages;
    }

    /**
     * Returns a byte array that can be use to uniquely identify a received SMS message.
     * C.S0015-B  4.3.1.6 Unique Message Identification.
     *
     * @return byte array uniquely identifying the message.
     * @hide
     */
    public byte[] getIncomingSmsFingerprint() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        output.write(mEnvelope.serviceCategory);
        output.write(mEnvelope.teleService);
        output.write(mEnvelope.origAddress.origBytes, 0, mEnvelope.origAddress.origBytes.length);
        output.write(mEnvelope.bearerData, 0, mEnvelope.bearerData.length);
        // subaddress is not set when parsing some MT SMS.
        if (mEnvelope.origSubaddress != null && mEnvelope.origSubaddress.origBytes != null) {
            output.write(mEnvelope.origSubaddress.origBytes, 0,
                    mEnvelope.origSubaddress.origBytes.length);
        }

        return output.toByteArray();
    }

    /**
     * Returns the list of service category program data, if present.
     * @return a list of CdmaSmsCbProgramData objects, or null if not present
     * @hide
     */
    public ArrayList<CdmaSmsCbProgramData> getSmsCbProgramData() {
        return mBearerData.serviceCategoryProgramData;
    }
}
