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

import android.os.Parcel;
import android.text.format.Time;
import android.util.Config;
import android.util.Log;
import com.android.internal.telephony.EncodeException;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsMessageBase;
import com.android.internal.telephony.cdma.sms.BearerData;
import com.android.internal.telephony.cdma.sms.CdmaSmsAddress;
import com.android.internal.telephony.cdma.sms.SmsEnvelope;
import com.android.internal.telephony.cdma.sms.UserData;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * TODO(cleanup): these constants are disturbing... are they not just
 * different interpretations on one number?  And if we did not have
 * terrible class name overlap, they would not need to be directly
 * imported like this.  The class in this file could just as well be
 * named CdmaSmsMessage, could it not?
 */

import static android.telephony.SmsMessage.MAX_USER_DATA_BYTES;
import static android.telephony.SmsMessage.MAX_USER_DATA_BYTES_WITH_HEADER;
import static android.telephony.SmsMessage.MAX_USER_DATA_SEPTETS;
import static android.telephony.SmsMessage.MAX_USER_DATA_SEPTETS_WITH_HEADER;
import static android.telephony.SmsMessage.MessageClass;

/**
 * TODO(cleanup): internally returning null in many places makes
 * debugging very hard (among many other reasons) and should be made
 * more meaningful (replaced with execptions for example).  Null
 * returns should only occur at the very outside of the module/class
 * scope.
 */

/**
 * A Short Message Service message.
 *
 */
public class SmsMessage extends SmsMessageBase {
    static final String LOG_TAG = "CDMA";

    /**
     *  Status of a previously submitted SMS.
     *  This field applies to SMS Delivery Acknowledge messages. 0 indicates success;
     *  Here, the error class is defined by the bits from 9-8, the status code by the bits from 7-0.
     *  See C.S0015-B, v2.0, 4.5.21 for a detailed description of possible values.
     */
    private int status;

    /** The next message ID for the BearerData. Shall be a random value on first use.
     * (See C.S0015-B, v2.0, 4.3.1.5)
     */
    private static int nextMessageId = 0;

    /** Specifies if this is the first SMS message submit */
    private static boolean firstSMS = true;

    /** Specifies if a return of an acknowledgment is requested for send SMS */
    private static final int RETURN_NO_ACK  = 0;
    private static final int RETURN_ACK     = 1;

    private SmsEnvelope mEnvelope;
    private BearerData mBearerData;

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
            Log.e(LOG_TAG, "SMS PDU parsing failed: ", ex);
            return null;
        }
    }

    /**
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    public static SmsMessage newFromCMT(String[] lines) {
        Log.w(LOG_TAG, "newFromCMT: is not supported in CDMA mode.");
        return null;
    }

    /**
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    public static SmsMessage newFromCMTI(String line) {
        Log.w(LOG_TAG, "newFromCMTI: is not supported in CDMA mode.");
        return null;
    }

    /**
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    public static SmsMessage newFromCDS(String line) {
        Log.w(LOG_TAG, "newFromCDS: is not supported in CDMA mode.");
        return null;
    }

    /**
     *  Create a "raw" CDMA SmsMessage from a Parcel that was forged in ril.cpp.
     *  Note: Only primitive fields are set.
     */
    public static SmsMessage newFromParcel(Parcel p) {
        // Note: Parcel.readByte actually reads one Int and masks to byte
        SmsMessage msg = new SmsMessage();
        SmsEnvelope env = new SmsEnvelope();
        CdmaSmsAddress addr = new CdmaSmsAddress();
        byte[] data;
        byte count;
        int countInt;
        int addressDigitMode;

        //currently not supported by the modem-lib: env.mMessageType
        env.teleService = p.readInt(); //p_cur->uTeleserviceID

        if (0 != p.readByte()) { //p_cur->bIsServicePresent
            env.messageType = SmsEnvelope.MESSAGE_TYPE_BROADCAST;
        }
        else {
            if (SmsEnvelope.TELESERVICE_NOT_SET == env.teleService) {
                // assume type ACK
                env.messageType = SmsEnvelope.MESSAGE_TYPE_ACKNOWLEDGE;
            } else {
                env.messageType = SmsEnvelope.MESSAGE_TYPE_POINT_TO_POINT;
            }
        }
        env.serviceCategory = p.readInt(); //p_cur->uServicecategory

        // address
        addressDigitMode = p.readInt();
        addr.digitMode = (byte) (0xFF & addressDigitMode); //p_cur->sAddress.digit_mode
        addr.numberMode = (byte) (0xFF & p.readInt()); //p_cur->sAddress.number_mode
        addr.ton = p.readInt(); //p_cur->sAddress.number_type
        addr.numberPlan = (byte) (0xFF & p.readInt()); //p_cur->sAddress.number_plan
        count = p.readByte(); //p_cur->sAddress.number_of_digits
        addr.numberOfDigits = count;
        data = new byte[count];
        //p_cur->sAddress.digits[digitCount]
        for (int index=0; index < count; index++) {
            data[index] = p.readByte();

            // convert the value if it is 4-bit DTMF to 8 bit
            if (addressDigitMode == CdmaSmsAddress.DIGIT_MODE_4BIT_DTMF) {
                data[index] = msg.convertDtmfToAscii(data[index]);
            }
        }

        addr.origBytes = data;

        // ignore subaddress
        p.readInt(); //p_cur->sSubAddress.subaddressType
        p.readInt(); //p_cur->sSubAddress.odd
        count = p.readByte(); //p_cur->sSubAddress.number_of_digits
        //p_cur->sSubAddress.digits[digitCount] :
        for (int index=0; index < count; index++) {
            p.readByte();
        }

        /* currently not supported by the modem-lib:
            env.bearerReply
            env.replySeqNo
            env.errorClass
            env.causeCode
        */

        // bearer data
        countInt = p.readInt(); //p_cur->uBearerDataLen
        if (countInt >0) {
            data = new byte[countInt];
             //p_cur->aBearerData[digitCount] :
            for (int index=0; index < countInt; index++) {
                data[index] = p.readByte();
            }
            env.bearerData = data;
            // BD gets further decoded when accessed in SMSDispatcher
        }

        // link the the filled objects to the SMS
        env.origAddress = addr;
        msg.originatingAddress = addr;
        msg.mEnvelope = env;

        // create byte stream representation for transportation through the layers.
        msg.createPdu();

        return msg;
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

            msg.indexOnIcc = index;

            // First byte is status: RECEIVED_READ, RECEIVED_UNREAD, STORED_SENT,
            // or STORED_UNSENT
            // See 3GPP2 C.S0023 3.4.27
            if ((data[0] & 1) == 0) {
                Log.w(LOG_TAG, "SMS parsing failed: Trying to parse a free record");
                return null;
            } else {
                msg.statusOnIcc = data[0] & 0x07;
            }

            // Second byte is the MSG_LEN, length of the message
            // See 3GPP2 C.S0023 3.4.27
            int size = data[1];

            // Note: Data may include trailing FF's.  That's OK; message
            // should still parse correctly.
            byte[] pdu = new byte[size];
            System.arraycopy(data, 2, pdu, 0, size);
            // the message has to be parsed before it can be displayed
            // see gsm.SmsMessage
            return msg;
        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "SMS PDU parsing failed: ", ex);
            return null;
        }

    }

    /**
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    public static int getTPLayerLengthForPDU(String pdu) {
        Log.w(LOG_TAG, "getTPLayerLengthForPDU: is not supported in CDMA mode.");
        return 0;
    }

    /**
     * Get an SMS-SUBMIT PDU for a destination address and a message
     *
     * @param scAddr                Service Centre address.  Null means use default.
     * @param destAddr              Address of the recipient.
     * @param message               String representation of the message payload.
     * @param statusReportRequested Indicates whether a report is requested for this message.
     * @param headerData            Array containing the data for the User Data Header, preceded
     *                              by the Element Identifiers.
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     * @hide
     */
    public static SubmitPdu getSubmitPdu(String scAddr,
            String destAddr, String message,
            boolean statusReportRequested, byte[] headerData) {
        /**
         * TODO(cleanup): why does this method take an scAddr input
         * and do nothing with it?  GSM allows us to specify a SC (eg,
         * when responding to an SMS that explicitly requests the
         * response is sent to a specific SC), or pass null to use the
         * default value.  Is there no similar notion in CDMA? Or do
         * we just not have it hooked up?
         */

        if (message == null || destAddr == null) {
            return null;
        }

        UserData uData = new UserData();
        uData.payloadStr = message;
        if(headerData != null) {
            /**
             * TODO(cleanup): we force the outside to deal with _all_
             * of the raw details of properly constructing serialized
             * headers, unserialze here, and then promptly reserialze
             * during encoding -- rather undesirable.
             */
            uData.userDataHeader = SmsHeader.parse(headerData);
        }

        return privateGetSubmitPdu(destAddr, statusReportRequested, uData, (headerData == null));
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
     * @param data the data for the message
     * @return a <code>SubmitPdu</code> containing the encoded SC
     *         address, if applicable, and the encoded message.
     *         Returns null on encode error.
     */
    public static SubmitPdu getSubmitPdu(String scAddress,
            String destAddr, short destinationPort, byte[] data,
            boolean statusReportRequested) {

        /**
         * TODO(cleanup): if we had properly exposed SmsHeader
         * information, this mess of many getSubmitPdu public
         * interface methods that currently pollute the api could have
         * been much more cleanly collapsed into one.
         */

        /**
         * TODO(cleanup): header serialization should be put somewhere
         * canonical to allow proper debugging and reuse.
         */
        byte[] destPort = new byte[4];
        destPort[0] = (byte) ((destinationPort >> 8) & 0xFF); // MSB of destination port
        destPort[1] = (byte) (destinationPort & 0xFF); // LSB of destination port
        destPort[2] = 0x00; // MSB of originating port
        destPort[3] = 0x00; // LSB of originating port
        SmsHeader smsHeader = new SmsHeader();
        smsHeader.add(
                new SmsHeader.Element(SmsHeader.APPLICATION_PORT_ADDRESSING_16_BIT, destPort));
        smsHeader.nbrOfHeaders = smsHeader.getElements().size();

        UserData uData = new UserData();
        uData.userDataHeader = smsHeader;
        uData.msgEncoding = UserData.ENCODING_OCTET;
        uData.msgEncodingSet = true;
        uData.payload = data;

        return privateGetSubmitPdu(destAddr, statusReportRequested, uData, true);
    }

    static class PduParser {

        PduParser() {
        }

        /**
         * Parses an SC timestamp and returns a currentTimeMillis()-style
         * timestamp
         */
        static long getSCTimestampMillis(byte[] timestamp) {
            // TP-Service-Centre-Time-Stamp
            int year = IccUtils.beBcdByteToInt(timestamp[0]);
            int month = IccUtils.beBcdByteToInt(timestamp[1]);
            int day = IccUtils.beBcdByteToInt(timestamp[2]);
            int hour = IccUtils.beBcdByteToInt(timestamp[3]);
            int minute = IccUtils.beBcdByteToInt(timestamp[4]);
            int second = IccUtils.beBcdByteToInt(timestamp[5]);

            Time time = new Time(Time.TIMEZONE_UTC);

            // C.S0015-B v2.0, 4.5.4: range is 1996-2095
            time.year = year >= 96 ? year + 1900 : year + 2000;
            time.month = month - 1;
            time.monthDay = day;
            time.hour = hour;
            time.minute = minute;
            time.second = second;

            return time.toMillis(true);
        }

    }

    /**
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    public int getProtocolIdentifier() {
        Log.w(LOG_TAG, "getProtocolIdentifier: is not supported in CDMA mode.");
        // (3GPP TS 23.040): "no interworking, but SME to SME protocol":
        return 0;
    }

    /**
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    public boolean isReplace() {
        Log.w(LOG_TAG, "isReplace: is not supported in CDMA mode.");
        return false;
    }

    /**
     * {@inheritDoc}
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    public boolean isCphsMwiMessage() {
        Log.w(LOG_TAG, "isCphsMwiMessage: is not supported in CDMA mode.");
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isMWIClearMessage() {
        if ((mBearerData != null) && (0 == mBearerData.numberOfMessages)) {
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isMWISetMessage() {
        if ((mBearerData != null) && (mBearerData.numberOfMessages >0)) {
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isMwiDontStore() {
        if ((mBearerData != null) && (mBearerData.numberOfMessages >0)
                && (null == mBearerData.userData)) {
            return true;
        }
        return false;
    }

    /**
     * Returns the status for a previously submitted message.
     * For not interfering with status codes from GSM, this status code is
     * shifted to the bits 31-16.
     */
    public int getStatus() {
        return(status<<16);
    }

    /**
     *  Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    public boolean isStatusReportMessage() {
        Log.w(LOG_TAG, "isStatusReportMessage: is not supported in CDMA mode.");
        return false;
    }

    /**
     * Note: This function is a GSM specific functionality which is not supported in CDMA mode.
     */
    public boolean isReplyPathPresent() {
        Log.w(LOG_TAG, "isReplyPathPresent: is not supported in CDMA mode.");
        return false;
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
     * Decodes pdu to an empty SMS object.
     * In the CDMA case the pdu is just an internal byte stream representation
     * of the SMS Java-object.
     * @see #createPdu()
     */
    private void parsePdu(byte[] pdu) {
        ByteArrayInputStream bais = new ByteArrayInputStream(pdu);
        DataInputStream dis = new DataInputStream(new BufferedInputStream(bais));
        byte length;
        int bearerDataLength;
        SmsEnvelope env = new SmsEnvelope();
        CdmaSmsAddress addr = new CdmaSmsAddress();

        try {
            env.messageType = dis.readInt();
            env.teleService = dis.readInt();
            env.serviceCategory = dis.readInt();

            addr.digitMode = dis.readByte();
            addr.numberMode = dis.readByte();
            addr.ton = dis.readByte();
            addr.numberPlan = dis.readByte();

            length = dis.readByte();
            addr.numberOfDigits = length;
            addr.origBytes = new byte[length];
            dis.read(addr.origBytes, 0, length); // digits

            env.bearerReply = dis.readInt();
            // CauseCode values:
            env.replySeqNo = dis.readByte();
            env.errorClass = dis.readByte();
            env.causeCode = dis.readByte();

            //encoded BearerData:
            bearerDataLength = dis.readInt();
            env.bearerData = new byte[bearerDataLength];
            dis.read(env.bearerData, 0, bearerDataLength);
            dis.close();
        } catch (Exception ex) {
            Log.e(LOG_TAG, "createFromPdu: conversion from byte array to object failed: " + ex);
        }

        // link the filled objects to this SMS
        originatingAddress = addr;
        env.origAddress = addr;
        mEnvelope = env;

        parseSms();
    }

    /**
     * Parses a SMS message from its BearerData stream. (mobile-terminated only)
     */
    protected void parseSms() {
        mBearerData = BearerData.decode(mEnvelope.bearerData);
        messageRef = mBearerData.messageId;

        // TP-Message-Type-Indicator
        // (See 3GPP2 C.S0015-B, v2, 4.5.1)
        int messageType = mBearerData.messageType;

        switch (messageType) {
        case BearerData.MESSAGE_TYPE_USER_ACK:
        case BearerData.MESSAGE_TYPE_READ_ACK:
        case BearerData.MESSAGE_TYPE_DELIVER:
            // Deliver (mobile-terminated only)
            parseSmsDeliver();
            break;
        case BearerData.MESSAGE_TYPE_DELIVERY_ACK:
            parseSmsDeliveryAck();
            break;

        default:
            // the rest of these
            throw new RuntimeException("Unsupported message type: " + messageType);
        }
    }

    /**
     * TODO(cleanup): why are there two nearly identical functions
     * below?  More rubbish...
     */

    /**
     * Parses a SMS-DELIVER message. (mobile-terminated only)
     * See 3GPP2 C.S0015-B, v2, 4.4.1
     */
    private void parseSmsDeliver() {
        if (originatingAddress != null) {
            originatingAddress.address = new String(originatingAddress.origBytes);
            if (Config.LOGV) Log.v(LOG_TAG, "SMS originating address: "
                    + originatingAddress.address);
        }

        if (mBearerData.timeStamp != null) {
                scTimeMillis = PduParser.getSCTimestampMillis(mBearerData.timeStamp);
        }

        if (Config.LOGD) Log.d(LOG_TAG, "SMS SC timestamp: " + scTimeMillis);

        parseUserData(mBearerData.userData);
    }

    /**
     * Parses a SMS-DELIVER message. (mobile-terminated only)
     * See 3GPP2 C.S0015-B, v2, 4.4.1
     */
    private void parseSmsDeliveryAck() {
        if (originatingAddress != null) {
            originatingAddress.address = new String(originatingAddress.origBytes);
            if (Config.LOGV) Log.v(LOG_TAG, "SMS originating address: "
                    + originatingAddress.address);
        }

        if (mBearerData.timeStamp != null) {
                scTimeMillis = PduParser.getSCTimestampMillis(mBearerData.timeStamp);
        }

        if (Config.LOGD) Log.d(LOG_TAG, "SMS SC timestamp: " + scTimeMillis);

        if (mBearerData.errorClass != BearerData.ERROR_UNDEFINED) {
            status = mBearerData.errorClass << 8;
            status |= mBearerData.messageStatus;
        }

        parseUserData(mBearerData.userData);
    }

    /**
     * Copy parsed user data out from internal datastructures.
     */
    private void parseUserData(UserData uData) {
        if (uData == null) {
            return;
        }

        userData = uData.payload;
        userDataHeader = uData.userDataHeader;
        messageBody = uData.payloadStr;

        if (messageBody != null) {
            if (Config.LOGV) Log.v(LOG_TAG, "SMS message body: '" + messageBody + "'");
            parseMessageBody();
        } else if ((userData != null) && (Config.LOGV)) {
            Log.v(LOG_TAG, "SMS payload: '" + IccUtils.bytesToHexString(userData) + "'");
        }
    }

    /**
     * {@inheritDoc}
     */
    public MessageClass getMessageClass() {
        if (BearerData.DISPLAY_MODE_IMMEDIATE == mBearerData.displayMode ) {
            return MessageClass.CLASS_0;
        } else {
            return MessageClass.UNKNOWN;
        }
    }

    private static CdmaSmsAddress parseCdmaSmsAddr(String addrStr) {
        // see C.S0015-B, v2.0, 3.4.3.3
        CdmaSmsAddress addr = new CdmaSmsAddress();
        addr.digitMode = CdmaSmsAddress.DIGIT_MODE_8BIT_CHAR;
        try {
            addr.origBytes = addrStr.getBytes("UTF-8");
        } catch  (java.io.UnsupportedEncodingException ex) {
            Log.e(LOG_TAG, "CDMA address parsing failed: " + ex);
            return null;
        }
        addr.numberOfDigits = (byte)addr.origBytes.length;
        addr.numberMode = CdmaSmsAddress.NUMBER_MODE_NOT_DATA_NETWORK;
        addr.numberPlan = CdmaSmsAddress.NUMBERING_PLAN_ISDN_TELEPHONY;
        addr.ton = CdmaSmsAddress.TON_INTERNATIONAL_OR_IP;
        return addr;
    }

    /**
     * Set the nextMessageId to a random value between 0 and 65536
     * See C.S0015-B, v2.0, 4.3.1.5
     */
    private static void setNextMessageId() {
        // Message ID, modulo 65536
        if(firstSMS) {
            Random generator = new Random();
            nextMessageId = generator.nextInt(65536);
            firstSMS = false;
        } else {
            nextMessageId = ++nextMessageId & 0xFFFF;
        }
    }

    /**
     * Creates BearerData and Envelope from parameters for a Submit SMS.
     * @return byte stream for SubmitPdu.
     */
    private static SubmitPdu privateGetSubmitPdu(String destAddrStr, boolean statusReportRequested,
            UserData userData, boolean useNewId) {

        /**
         * TODO(cleanup): give this function a more meaningful name.
         */

        CdmaSmsAddress destAddr = parseCdmaSmsAddr(destAddrStr);
        if (destAddr == null) return null;

        BearerData bearerData = new BearerData();
        bearerData.messageType = BearerData.MESSAGE_TYPE_SUBMIT;

        if (useNewId) setNextMessageId();
        bearerData.messageId = nextMessageId;

        bearerData.deliveryAckReq = statusReportRequested;
        bearerData.userAckReq = false;
        bearerData.readAckReq = false;
        bearerData.reportReq = false;

        bearerData.userData = userData;
        bearerData.hasUserDataHeader = (userData.userDataHeader != null);

        byte[] encodedBearerData = BearerData.encode(bearerData);
        if (encodedBearerData == null) return null;

        SmsEnvelope envelope = new SmsEnvelope();
        envelope.messageType = SmsEnvelope.MESSAGE_TYPE_POINT_TO_POINT;
        envelope.teleService = SmsEnvelope.TELESERVICE_WMT;
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
            Log.e(LOG_TAG, "creating SubmitPdu failed: " + ex);
        }
        return null;
    }

    /**
     * Creates byte array (pseudo pdu) from SMS object.
     * Note: Do not call this method more than once per object!
     */
    private void createPdu() {
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

            mPdu = baos.toByteArray();
        } catch (IOException ex) {
            Log.e(LOG_TAG, "createPdu: conversion from object to byte array failed: " + ex);
        }
    }

    /**
     * Converts a 4-Bit DTMF encoded symbol from the calling address number to ASCII character
     */
    private byte convertDtmfToAscii(byte dtmfDigit) {
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



}
