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
//import com.android.internal.telephony.SMSDispatcher.SmsTracker;
import com.android.internal.telephony.cdma.SmsMessage;
import com.android.internal.telephony.cdma.sms.SmsEnvelope;
import com.android.internal.util.HexDump;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;


final class CdmaSMSDispatcher extends SMSDispatcher {
    private static final String TAG = "CDMA";

    CdmaSMSDispatcher(CDMAPhone phone) {
        super(phone);
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
        SmsMessage sms = (SmsMessage) smsb;
        int teleService;
        boolean handled = false;

        // Decode BD stream and set sms variables.
        sms.parseSms();
        teleService = sms.getTeleService();

        // Teleservices W(E)MT and VMN are handled together:
        if ((SmsEnvelope.TELESERVICE_WMT == teleService)
                ||(SmsEnvelope.TELESERVICE_WEMT == teleService)
                ||(SmsEnvelope.TELESERVICE_VMN == teleService)){
            // From here on we need decoded BD.
            // Special case the message waiting indicator messages
            if (sms.isMWISetMessage()) {
                ((CDMAPhone) mPhone).updateMessageWaitingIndicator(true);

                if (sms.isMwiDontStore()) {
                    handled = true;
                }

                if (Config.LOGD) {
                    Log.d(TAG,
                            "Received voice mail indicator set SMS shouldStore=" + !handled);
                }
            } else if (sms.isMWIClearMessage()) {
                ((CDMAPhone) mPhone).updateMessageWaitingIndicator(false);

                if (sms.isMwiDontStore()) {
                    handled = true;
                }

                if (Config.LOGD) {
                    Log.d(TAG,
                            "Received voice mail indicator clear SMS shouldStore=" + !handled);
                }
            }
        }

        if (null == sms.getUserData()){
            handled = true;
            if (Config.LOGD) {
                Log.d(TAG, "Received SMS without user data");
            }
        }

        if (handled) return;

        if (SmsEnvelope.TELESERVICE_WAP == teleService){
            processCdmaWapPdu(sms.getUserData(), sms.messageRef, sms.getOriginatingAddress());
            return;
        }

        // Parse the headers to see if this is partial, or port addressed
        int referenceNumber = -1;
        int count = 0;
        int sequence = 0;
        int destPort = -1;
        // From here on we need BD distributed to SMS member variables.

        SmsHeader header = sms.getUserDataHeader();
        if (header != null) {
            for (SmsHeader.Element element : header.getElements()) {
                try {
                    switch (element.getID()) {
                        case SmsHeader.CONCATENATED_8_BIT_REFERENCE: {
                            byte[] data = element.getData();
                            
                            referenceNumber = data[0] & 0xff;
                            count = data[1] & 0xff;
                            sequence = data[2] & 0xff;
                            
                            // Per TS 23.040, 9.2.3.24.1: If the count is zero, sequence
                            // is zero, or sequence > count, ignore the entire element
                            if (count == 0 || sequence == 0 || sequence > count) {
                                referenceNumber = -1;
                            }
                            break;
                        }
                        
                        case SmsHeader.CONCATENATED_16_BIT_REFERENCE: {
                            byte[] data = element.getData();
                            
                            referenceNumber = (data[0] & 0xff) * 256 + (data[1] & 0xff);
                            count = data[2] & 0xff;
                            sequence = data[3] & 0xff;
                            
                            // Per TS 23.040, 9.2.3.24.8: If the count is zero, sequence
                            // is zero, or sequence > count, ignore the entire element
                            if (count == 0 || sequence == 0 || sequence > count) {
                                referenceNumber = -1;
                            }
                            break;
                        }
                        
                        case SmsHeader.APPLICATION_PORT_ADDRESSING_16_BIT: {
                            byte[] data = element.getData();
                            
                            destPort = (data[0] & 0xff) << 8;
                            destPort |= (data[1] & 0xff);
                            
                            break;
                        }
                    }
                } catch (ArrayIndexOutOfBoundsException e) {
                    Log.e(TAG, "Bad element in header", e);
                    return;  // TODO: NACK the message or something, don't just discard.
                }
            }
        }

        if (referenceNumber == -1) {
            // notify everyone of the message if it isn't partial
            byte[][] pdus = new byte[1][];
            pdus[0] = sms.getPdu();

            if (destPort != -1) {// GSM-style WAP indication
                if (destPort == SmsHeader.PORT_WAP_PUSH) {
                    mWapPush.dispatchWapPdu(sms.getUserData());
                }
                // The message was sent to a port, so concoct a URI for it
                dispatchPortAddressedPdus(pdus, destPort);
            } else {
                // It's a normal message, dispatch it
                dispatchPdus(pdus);
            }
        } else {
            // Process the message part
            processMessagePart(sms, referenceNumber, sequence, count, destPort);
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
    protected void sendMultipartText(String destinationAddress, String scAddress,
            ArrayList<String> parts, ArrayList<PendingIntent> sentIntents,
            ArrayList<PendingIntent> deliveryIntents) {

        int ref = ++sConcatenatedRef & 0xff;

        for (int i = 0, count = parts.size(); i < count; i++) {
            // build SmsHeader data
            byte[] data = new byte[5];
            data[0] = (byte) SmsHeader.CONCATENATED_8_BIT_REFERENCE;
            data[1] = (byte) 3;   // 3 bytes follow
            data[2] = (byte) ref;   // reference #, unique per message
            data[3] = (byte) count; // total part count
            data[4] = (byte) (i + 1);  // 1-based sequence

            PendingIntent sentIntent = null;
            PendingIntent deliveryIntent = null;

            if (sentIntents != null && sentIntents.size() > i) {
                sentIntent = sentIntents.get(i);
            }
            if (deliveryIntents != null && deliveryIntents.size() > i) {
                deliveryIntent = deliveryIntents.get(i);
            }

            SmsMessage.SubmitPdu pdus = SmsMessage.getSubmitPdu(scAddress, destinationAddress,
                    parts.get(i), deliveryIntent != null, data);

            sendRawPdu(pdus.encodedScAddress, pdus.encodedMessage, sentIntent, deliveryIntent);
        }
    }

    protected void sendRawPdu(byte[] smsc, byte[] pdu, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        super.sendRawPdu(smsc, pdu, sentIntent, deliveryIntent);
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
