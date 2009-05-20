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


import android.app.PendingIntent;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.os.AsyncResult;
import android.os.Message;
import android.util.Config;
import android.util.Log;

import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsMessageBase;
import com.android.internal.telephony.SMSDispatcher;
import com.android.internal.telephony.cdma.SmsMessage;
import com.android.internal.telephony.cdma.sms.SmsEnvelope;
import com.android.internal.util.HexDump;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;


final class CdmaSMSDispatcher extends SMSDispatcher {
    private static final String TAG = "CDMA";

    private CDMAPhone mCdmaPhone;

    CdmaSMSDispatcher(CDMAPhone phone) {
        super(phone);
        mCdmaPhone = phone;
    }

    /**
     * Called when a status report is received.  This should correspond to
     * a previously successful SEND.
     * Is a special GSM function, should never be called in CDMA!!
     *
     * @param ar AsyncResult passed into the message handler.  ar.result should
     *           be a String representing the status report PDU, as ASCII hex.
     */
    protected void handleStatusReport(AsyncResult ar) {
        Log.d(TAG, "handleStatusReport is a special GSM function, should never be called in CDMA!");
    }

    /**
     * Dispatches an incoming SMS messages.
     *
     * @param smsb the incoming message from the phone
     */
    protected void dispatchMessage(SmsMessageBase smsb) {

        // If sms is null, means there was a parsing error.
        // TODO: Should NAK this.
        if (smsb == null) {
            return;
        }

        // Decode BD stream and set sms variables.
        SmsMessage sms = (SmsMessage) smsb;
        sms.parseSms();
        int teleService = sms.getTeleService();
        boolean handled = false;

        // Teleservices W(E)MT and VMN are handled together:
        if ((teleService == SmsEnvelope.TELESERVICE_WMT)
                || (teleService == SmsEnvelope.TELESERVICE_WEMT)
                || (teleService == SmsEnvelope.TELESERVICE_VMN)) {
            // From here on we need decoded BD.
            // Special case the message waiting indicator messages
            if (sms.isMWISetMessage()) {
                mCdmaPhone.updateMessageWaitingIndicator(true);
                handled |= sms.isMwiDontStore();
                if (Config.LOGD) {
                    Log.d(TAG, "Received voice mail indicator set SMS shouldStore=" + !handled);
                }
            } else if (sms.isMWIClearMessage()) {
                mCdmaPhone.updateMessageWaitingIndicator(false);
                handled |= sms.isMwiDontStore();
                if (Config.LOGD) {
                    Log.d(TAG, "Received voice mail indicator clear SMS shouldStore=" + !handled);
                }
            }
        }

        if (sms.getUserData() == null) {
            if (Config.LOGD) {
                Log.d(TAG, "Received SMS without user data");
            }
            handled = true;
        }

        if (handled) return;

        if (SmsEnvelope.TELESERVICE_WAP == teleService){
            processCdmaWapPdu(sms.getUserData(), sms.messageRef, sms.getOriginatingAddress());
            return;
        }

        /**
         * TODO(cleanup): Why are we using a getter method for this
         * (and for so many other sms fields)?  Trivial getters and
         * setters like this are direct violations of the style guide.
         * If the purpose is to protect agaist writes (by not
         * providing a setter) then any protection is illusory (and
         * hence bad) for cases where the values are not primitives,
         * such as this call for the header.  Since this is an issue
         * with the public API it cannot be changed easily, but maybe
         * something can be done eventually.
         */
        SmsHeader smsHeader = sms.getUserDataHeader();

        /**
         * TODO(cleanup): Since both CDMA and GSM use the same header
         * format, this dispatch processing is naturally identical,
         * and code should probably not be replicated explicitly.
         */
        // See if message is partial or port addressed.
        if ((smsHeader == null) || (smsHeader.concatRef == null)) {
            // Message is not partial (not part of concatenated sequence).
            byte[][] pdus = new byte[1][];
            pdus[0] = sms.getPdu();

            if (smsHeader != null && smsHeader.portAddrs != null) {
                if (smsHeader.portAddrs.destPort == SmsHeader.PORT_WAP_PUSH) {
                    // GSM-style WAP indication
                    mWapPush.dispatchWapPdu(sms.getUserData());
                }
                // The message was sent to a port, so concoct a URI for it.
                dispatchPortAddressedPdus(pdus, smsHeader.portAddrs.destPort);
            } else {
                // Normal short and non-port-addressed message, dispatch it.
                dispatchPdus(pdus);
            }
        } else {
            // Process the message part.
            processMessagePart(sms, smsHeader.concatRef, smsHeader.portAddrs);
        }
    }

    /**
     * Processes inbound messages that are in the WAP-WDP PDU format. See
     * wap-259-wdp-20010614-a section 6.5 for details on the WAP-WDP PDU format.
     * WDP segments are gathered until a datagram completes and gets dispatched.
     *
     * @param pdu The WAP-WDP PDU segment
     */
    protected void processCdmaWapPdu(byte[] pdu, int referenceNumber, String address) {
        int segment;
        int totalSegments;
        int index = 0;
        int msgType;

        int sourcePort;
        int destinationPort;

        msgType = pdu[index++];
        if (msgType != 0){
            Log.w(TAG, "Received a WAP SMS which is not WDP. Discard.");
            return;
        }
        totalSegments = pdu[index++]; // >=1
        segment = pdu[index++]; // >=0

        //process WDP segment
        sourcePort = (0xFF & pdu[index++]) << 8;
        sourcePort |= 0xFF & pdu[index++];
        destinationPort = (0xFF & pdu[index++]) << 8;
        destinationPort |= 0xFF & pdu[index++];

        // Lookup all other related parts
        StringBuilder where = new StringBuilder("reference_number =");
        where.append(referenceNumber);
        where.append(" AND address = ?");
        String[] whereArgs = new String[] {address};

        Log.i(TAG, "Received WAP PDU. Type = " + msgType + ", originator = " + address
                + ", src-port = " + sourcePort + ", dst-port = " + destinationPort
                + ", ID = " + referenceNumber + ", segment# = " + segment + "/" + totalSegments);

        byte[][] pdus = null;
        Cursor cursor = null;
        try {
            cursor = mResolver.query(mRawUri, RAW_PROJECTION, where.toString(), whereArgs, null);
            int cursorCount = cursor.getCount();
            if (cursorCount != totalSegments - 1) {
                // We don't have all the parts yet, store this one away
                ContentValues values = new ContentValues();
                values.put("date", new Long(0));
                values.put("pdu", HexDump.toHexString(pdu, index, pdu.length - index));
                values.put("address", address);
                values.put("reference_number", referenceNumber);
                values.put("count", totalSegments);
                values.put("sequence", segment);
                values.put("destination_port", destinationPort);

                mResolver.insert(mRawUri, values);

                return;
            }

            // All the parts are in place, deal with them
            int pduColumn = cursor.getColumnIndex("pdu");
            int sequenceColumn = cursor.getColumnIndex("sequence");

            pdus = new byte[totalSegments][];
            for (int i = 0; i < cursorCount; i++) {
                cursor.moveToNext();
                int cursorSequence = (int)cursor.getLong(sequenceColumn);
                pdus[cursorSequence] = HexDump.hexStringToByteArray(
                        cursor.getString(pduColumn));
            }
            // The last part will be added later

            // Remove the parts from the database
            mResolver.delete(mRawUri, where.toString(), whereArgs);
        } catch (SQLException e) {
            Log.e(TAG, "Can't access multipart SMS database", e);
            return;  // TODO: NACK the message or something, don't just discard.
        } finally {
            if (cursor != null) cursor.close();
        }

        // Build up the data stream
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < totalSegments-1; i++) {
            // reassemble the (WSP-)pdu
            output.write(pdus[i], 0, pdus[i].length);
        }

        // This one isn't in the DB, so add it
        output.write(pdu, index, pdu.length - index);

        byte[] datagram = output.toByteArray();
        // Dispatch the PDU to applications
        switch (destinationPort) {
        case SmsHeader.PORT_WAP_PUSH:
            // Handle the PUSH
            mWapPush.dispatchWapPdu(datagram);
            break;

        default:{
            pdus = new byte[1][];
            pdus[0] = datagram;
            // The messages were sent to any other WAP port
            dispatchPortAddressedPdus(pdus, destinationPort);
            break;
        }
        }
    }

    /** {@inheritDoc} */
    protected void sendMultipartText(String destAddr, String scAddr,
            ArrayList<String> parts, ArrayList<PendingIntent> sentIntents,
            ArrayList<PendingIntent> deliveryIntents) {

        /**
         * TODO(cleanup): There is no real code difference between
         * this and the GSM version, and hence it should be moved to
         * the base class or consolidated somehow, provided calling
         * the proper submitpdu stuff can be arranged.
         */

        int refNumber = getNextConcatenatedRef() & 0x00FF;

        for (int i = 0, msgCount = parts.size(); i < msgCount; i++) {
            SmsHeader.ConcatRef concatRef = new SmsHeader.ConcatRef();
            concatRef.refNumber = refNumber;
            concatRef.seqNumber = i + 1;  // 1-based sequence
            concatRef.msgCount = msgCount;
            concatRef.isEightBits = true;
            SmsHeader smsHeader = new SmsHeader();
            smsHeader.concatRef = concatRef;

            PendingIntent sentIntent = null;
            if (sentIntents != null && sentIntents.size() > i) {
                sentIntent = sentIntents.get(i);
            }

            PendingIntent deliveryIntent = null;
            if (deliveryIntents != null && deliveryIntents.size() > i) {
                deliveryIntent = deliveryIntents.get(i);
            }

            SmsMessage.SubmitPdu submitPdu = SmsMessage.getSubmitPdu(scAddr, destAddr,
                    parts.get(i), deliveryIntent != null, smsHeader);

            sendSubmitPdu(submitPdu, sentIntent, deliveryIntent);
        }
    }

    protected void sendSubmitPdu(SmsMessage.SubmitPdu submitPdu, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        sendRawPdu(submitPdu.encodedScAddress, submitPdu.encodedMessage,
                sentIntent, deliveryIntent);
    }

    /** {@inheritDoc} */
    protected void sendSms(SmsTracker tracker) {
        HashMap map = tracker.mData;

        byte smsc[] = (byte[]) map.get("smsc");
        byte pdu[] = (byte[]) map.get("pdu");

        Message reply = obtainMessage(EVENT_SEND_SMS_COMPLETE, tracker);

        mCm.sendCdmaSms(pdu, reply);
    }

     /** {@inheritDoc} */
    protected void sendMultipartSms (SmsTracker tracker) {
        Log.d(TAG, "TODO: CdmaSMSDispatcher.sendMultipartSms not implemented");
    }

    /** {@inheritDoc} */
    protected void acknowledgeLastIncomingSms(boolean success, Message response){
        // FIXME unit test leaves cm == null. this should change
        if (mCm != null) {
            mCm.acknowledgeLastIncomingCdmaSms(success, response);
        }
    }

    /** {@inheritDoc} */
    protected void activateCellBroadcastSms(int activate, Message response) {
        mCm.activateCdmaBroadcastSms(activate, response);
    }

    /** {@inheritDoc} */
    protected void getCellBroadcastSmsConfig(Message response) {
        mCm.getCdmaBroadcastConfig(response);
    }

    /** {@inheritDoc} */
    protected void setCellBroadcastConfig(int[] configValuesArray, Message response) {
        mCm.setCdmaBroadcastConfig(configValuesArray, response);
    }

}
