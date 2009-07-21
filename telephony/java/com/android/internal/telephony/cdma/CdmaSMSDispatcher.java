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


import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.SQLException;
import android.os.AsyncResult;
import android.os.Message;
import android.provider.Telephony;
import android.provider.Telephony.Sms.Intents;
import android.preference.PreferenceManager;
import android.util.Config;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsMessageBase;
import com.android.internal.telephony.SMSDispatcher;
import com.android.internal.telephony.cdma.SmsMessage;
import com.android.internal.telephony.cdma.sms.SmsEnvelope;
import com.android.internal.telephony.cdma.sms.UserData;
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

    /** {@inheritDoc} */
    protected int dispatchMessage(SmsMessageBase smsb) {

        // If sms is null, means there was a parsing error.
        if (smsb == null) {
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }

        // Decode BD stream and set sms variables.
        SmsMessage sms = (SmsMessage) smsb;
        sms.parseSms();
        int teleService = sms.getTeleService();
        boolean handled = false;

        if (sms.getUserData() == null) {
            if (Config.LOGD) {
                Log.d(TAG, "Received SMS without user data");
            }
            handled = true;
        }

        if (handled) {
            return Intents.RESULT_SMS_HANDLED;
        }

        if (SmsEnvelope.TELESERVICE_WAP == teleService){
            return processCdmaWapPdu(sms.getUserData(), sms.messageRef,
                    sms.getOriginatingAddress());
        } else if (SmsEnvelope.TELESERVICE_VMN == teleService) {
            // handling Voicemail
            int voicemailCount = sms.getNumOfVoicemails();
            Log.d(TAG, "Voicemail count=" + voicemailCount);
            // Store the voicemail count in preferences.
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                    ((CDMAPhone) mPhone).getContext());
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(CDMAPhone.VM_COUNT_CDMA, voicemailCount);
            editor.commit();
            ((CDMAPhone) mPhone).updateMessageWaitingIndicator(voicemailCount);
            return Intents.RESULT_SMS_HANDLED;
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
                    return mWapPush.dispatchWapPdu(sms.getUserData());
                } else {
                    // The message was sent to a port, so concoct a URI for it.
                    dispatchPortAddressedPdus(pdus, smsHeader.portAddrs.destPort);
                }
            } else {
                // Normal short and non-port-addressed message, dispatch it.
                dispatchPdus(pdus);
            }
            return Activity.RESULT_OK;
        } else {
            // Process the message part.
            return processMessagePart(sms, smsHeader.concatRef, smsHeader.portAddrs);
        }
    }

    /**
     * Processes inbound messages that are in the WAP-WDP PDU format. See
     * wap-259-wdp-20010614-a section 6.5 for details on the WAP-WDP PDU format.
     * WDP segments are gathered until a datagram completes and gets dispatched.
     *
     * @param pdu The WAP-WDP PDU segment
     * @return a result code from {@link Telephony.Sms.Intents}, or
     *         {@link Activity#RESULT_OK} if the message has been broadcast
     *         to applications
     */
    protected int processCdmaWapPdu(byte[] pdu, int referenceNumber, String address) {
        int segment;
        int totalSegments;
        int index = 0;
        int msgType;

        int sourcePort = 0;
        int destinationPort = 0;

        msgType = pdu[index++];
        if (msgType != 0){
            Log.w(TAG, "Received a WAP SMS which is not WDP. Discard.");
            return Intents.RESULT_SMS_HANDLED;
        }
        totalSegments = pdu[index++]; // >=1
        segment = pdu[index++]; // >=0

        // Only the first segment contains sourcePort and destination Port
        if (segment == 0) {
            //process WDP segment
            sourcePort = (0xFF & pdu[index++]) << 8;
            sourcePort |= 0xFF & pdu[index++];
            destinationPort = (0xFF & pdu[index++]) << 8;
            destinationPort |= 0xFF & pdu[index++];
        }

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

                return Intents.RESULT_SMS_HANDLED;
            }

            // All the parts are in place, deal with them
            int pduColumn = cursor.getColumnIndex("pdu");
            int sequenceColumn = cursor.getColumnIndex("sequence");

            pdus = new byte[totalSegments][];
            for (int i = 0; i < cursorCount; i++) {
                cursor.moveToNext();
                int cursorSequence = (int)cursor.getLong(sequenceColumn);
                // Read the destination port from the first segment
                if (cursorSequence == 0) {
                    int destinationPortColumn = cursor.getColumnIndex("destination_port");
                    destinationPort = (int)cursor.getLong(destinationPortColumn);
                }
                pdus[cursorSequence] = HexDump.hexStringToByteArray(
                        cursor.getString(pduColumn));
            }
            // The last part will be added later

            // Remove the parts from the database
            mResolver.delete(mRawUri, where.toString(), whereArgs);
        } catch (SQLException e) {
            Log.e(TAG, "Can't access multipart SMS database", e);
            return Intents.RESULT_SMS_GENERIC_ERROR;
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
            return mWapPush.dispatchWapPdu(datagram);

        default:{
            pdus = new byte[1][];
            pdus[0] = datagram;
            // The messages were sent to any other WAP port
            dispatchPortAddressedPdus(pdus, destinationPort);
            return Activity.RESULT_OK;
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

            UserData uData = new UserData();
            uData.payloadStr = parts.get(i);
            uData.userDataHeader = smsHeader;

            SmsMessage.SubmitPdu submitPdu = SmsMessage.getSubmitPdu(destAddr,
                    uData, deliveryIntent != null);

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
    protected void acknowledgeLastIncomingSms(boolean success, int result, Message response){
        // FIXME unit test leaves cm == null. this should change
        if (mCm != null) {
            mCm.acknowledgeLastIncomingCdmaSms(success, resultToCause(result), response);
        }
    }

    /** {@inheritDoc} */
    protected void activateCellBroadcastSms(int activate, Message response) {
        mCm.setCdmaBroadcastActivation((activate == 0), response);
    }

    /** {@inheritDoc} */
    protected void getCellBroadcastSmsConfig(Message response) {
        mCm.getCdmaBroadcastConfig(response);
    }

    /** {@inheritDoc} */
    protected void setCellBroadcastConfig(int[] configValuesArray, Message response) {
        mCm.setCdmaBroadcastConfig(configValuesArray, response);
    }

    private int resultToCause(int rc) {
        switch (rc) {
            case Activity.RESULT_OK:
            case Intents.RESULT_SMS_HANDLED:
                // Cause code is ignored on success.
                return 0;
            case Intents.RESULT_SMS_OUT_OF_MEMORY:
                return CommandsInterface.CDMA_SMS_FAIL_CAUSE_RESOURCE_SHORTAGE;
            case Intents.RESULT_SMS_GENERIC_ERROR:
            default:
                return CommandsInterface.CDMA_SMS_FAIL_CAUSE_OTHER_TERMINAL_PROBLEM;
        }
    }
}
