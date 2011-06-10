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
import android.app.PendingIntent.CanceledException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.SQLException;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Telephony;
import android.provider.Telephony.Sms.Intents;
import android.telephony.SmsManager;
import android.telephony.SmsMessage.MessageClass;
import android.util.Config;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.SMSDispatcher;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsMessageBase;
import com.android.internal.telephony.SmsMessageBase.TextEncodingDetails;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.WspTypeDecoder;
import com.android.internal.telephony.cdma.sms.SmsEnvelope;
import com.android.internal.telephony.cdma.sms.UserData;
import com.android.internal.util.HexDump;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import android.content.res.Resources;


final class CdmaSMSDispatcher extends SMSDispatcher {
    private static final String TAG = "CDMA";

    private byte[] mLastDispatchedSmsFingerprint;
    private byte[] mLastAcknowledgedSmsFingerprint;

    private boolean mCheckForDuplicatePortsInOmadmWapPush = Resources.getSystem().getBoolean(
            com.android.internal.R.bool.config_duplicate_port_omadm_wappush);

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

    private void handleCdmaStatusReport(SmsMessage sms) {
        for (int i = 0, count = deliveryPendingList.size(); i < count; i++) {
            SmsTracker tracker = deliveryPendingList.get(i);
            if (tracker.mMessageRef == sms.messageRef) {
                // Found it.  Remove from list and broadcast.
                deliveryPendingList.remove(i);
                PendingIntent intent = tracker.mDeliveryIntent;
                Intent fillIn = new Intent();
                fillIn.putExtra("pdu", sms.getPdu());
                try {
                    intent.send(mContext, Activity.RESULT_OK, fillIn);
                } catch (CanceledException ex) {}
                break;  // Only expect to see one tracker matching this message.
            }
        }
    }

    /** {@inheritDoc} */
    protected int dispatchMessage(SmsMessageBase smsb) {

        // If sms is null, means there was a parsing error.
        if (smsb == null) {
            Log.e(TAG, "dispatchMessage: message is null");
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }

        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        if (inEcm.equals("true")) {
            return Activity.RESULT_OK;
        }

        // See if we have a network duplicate SMS.
        SmsMessage sms = (SmsMessage) smsb;
        mLastDispatchedSmsFingerprint = sms.getIncomingSmsFingerprint();
        if (mLastAcknowledgedSmsFingerprint != null &&
                Arrays.equals(mLastDispatchedSmsFingerprint, mLastAcknowledgedSmsFingerprint)) {
            return Intents.RESULT_SMS_HANDLED;
        }
        // Decode BD stream and set sms variables.
        sms.parseSms();
        int teleService = sms.getTeleService();
        boolean handled = false;

        if ((SmsEnvelope.TELESERVICE_VMN == teleService) ||
                (SmsEnvelope.TELESERVICE_MWI == teleService)) {
            // handling Voicemail
            int voicemailCount = sms.getNumOfVoicemails();
            Log.d(TAG, "Voicemail count=" + voicemailCount);
            // Store the voicemail count in preferences.
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                    mPhone.getContext());
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(CDMAPhone.VM_COUNT_CDMA, voicemailCount);
            editor.apply();
            ((CDMAPhone) mPhone).updateMessageWaitingIndicator(voicemailCount);
            handled = true;
        } else if (((SmsEnvelope.TELESERVICE_WMT == teleService) ||
                (SmsEnvelope.TELESERVICE_WEMT == teleService)) &&
                sms.isStatusReportMessage()) {
            handleCdmaStatusReport(sms);
            handled = true;
        } else if ((sms.getUserData() == null)) {
            if (Config.LOGD) {
                Log.d(TAG, "Received SMS without user data");
            }
            handled = true;
        }

        if (handled) {
            return Intents.RESULT_SMS_HANDLED;
        }

        if (!mStorageAvailable && (sms.getMessageClass() != MessageClass.CLASS_0)) {
            // It's a storable message and there's no storage available.  Bail.
            // (See C.S0015-B v2.0 for a description of "Immediate Display"
            // messages, which we represent as CLASS_0.)
            return Intents.RESULT_SMS_OUT_OF_MEMORY;
        }

        if (SmsEnvelope.TELESERVICE_WAP == teleService) {
            return processCdmaWapPdu(sms.getUserData(), sms.messageRef,
                    sms.getOriginatingAddress());
        }

        // Reject (NAK) any messages with teleservice ids that have
        // not yet been handled and also do not correspond to the two
        // kinds that are processed below.
        if ((SmsEnvelope.TELESERVICE_WMT != teleService) &&
                (SmsEnvelope.TELESERVICE_WEMT != teleService) &&
                (SmsEnvelope.MESSAGE_TYPE_BROADCAST != sms.getMessageType())) {
            return Intents.RESULT_SMS_UNSUPPORTED;
        }

        /*
         * TODO(cleanup): Why are we using a getter method for this
         * (and for so many other sms fields)?  Trivial getters and
         * setters like this are direct violations of the style guide.
         * If the purpose is to protect against writes (by not
         * providing a setter) then any protection is illusory (and
         * hence bad) for cases where the values are not primitives,
         * such as this call for the header.  Since this is an issue
         * with the public API it cannot be changed easily, but maybe
         * something can be done eventually.
         */
        SmsHeader smsHeader = sms.getUserDataHeader();

        /*
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
            // Some carriers incorrectly send duplicate port fields in omadm wap pushes.
            // If configured, check for that here
            if (mCheckForDuplicatePortsInOmadmWapPush) {
                if (checkDuplicatePortOmadmWappush(pdu,index)) {
                    index = index + 4; // skip duplicate port fields
                }
            }
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
                values.put("date", (long) 0);
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
        for (int i = 0; i < totalSegments; i++) {
            // reassemble the (WSP-)pdu
            if (i == segment) {
                // This one isn't in the DB, so add it
                output.write(pdu, index, pdu.length - index);
            } else {
                output.write(pdus[i], 0, pdus[i].length);
            }
        }

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
    protected void sendData(String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, destPort, data, (deliveryIntent != null));
        sendSubmitPdu(pdu, sentIntent, deliveryIntent);
    }

    /** {@inheritDoc} */
    protected void sendText(String destAddr, String scAddr, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, text, (deliveryIntent != null), null);
        sendSubmitPdu(pdu, sentIntent, deliveryIntent);
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
        int msgCount = parts.size();
        int encoding = android.telephony.SmsMessage.ENCODING_UNKNOWN;

        for (int i = 0; i < msgCount; i++) {
            TextEncodingDetails details = SmsMessage.calculateLength(parts.get(i), false);
            if (encoding != details.codeUnitSize
                    && (encoding == android.telephony.SmsMessage.ENCODING_UNKNOWN
                            || encoding == android.telephony.SmsMessage.ENCODING_7BIT)) {
                encoding = details.codeUnitSize;
            }
        }

        for (int i = 0; i < msgCount; i++) {
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
            if (encoding == android.telephony.SmsMessage.ENCODING_7BIT) {
                uData.msgEncoding = UserData.ENCODING_GSM_7BIT_ALPHABET;
            } else { // assume UTF-16
                uData.msgEncoding = UserData.ENCODING_UNICODE_16;
            }
            uData.msgEncodingSet = true;

            /* By setting the statusReportRequested bit only for the
             * last message fragment, this will result in only one
             * callback to the sender when that last fragment delivery
             * has been acknowledged. */
            SmsMessage.SubmitPdu submitPdu = SmsMessage.getSubmitPdu(destAddr,
                    uData, (deliveryIntent != null) && (i == (msgCount - 1)));

            sendSubmitPdu(submitPdu, sentIntent, deliveryIntent);
        }
    }

    protected void sendSubmitPdu(SmsMessage.SubmitPdu pdu,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        if (SystemProperties.getBoolean(TelephonyProperties.PROPERTY_INECM_MODE, false)) {
            if (sentIntent != null) {
                try {
                    sentIntent.send(SmsManager.RESULT_ERROR_NO_SERVICE);
                } catch (CanceledException ex) {}
            }
            if (Config.LOGD) {
                Log.d(TAG, "Block SMS in Emergency Callback mode");
            }
            return;
        }
        sendRawPdu(pdu.encodedScAddress, pdu.encodedMessage, sentIntent, deliveryIntent);
    }

    /** {@inheritDoc} */
    protected void sendSms(SmsTracker tracker) {
        HashMap map = tracker.mData;

        // byte smsc[] = (byte[]) map.get("smsc");  // unused for CDMA
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

        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        if (inEcm.equals("true")) {
            return;
        }

        if (mCm != null) {
            int causeCode = resultToCause(result);
            mCm.acknowledgeLastIncomingCdmaSms(success, causeCode, response);

            if (causeCode == 0) {
                mLastAcknowledgedSmsFingerprint = mLastDispatchedSmsFingerprint;
            }
            mLastDispatchedSmsFingerprint = null;
        }
    }

    protected void handleBroadcastSms(AsyncResult ar) {
        // Not supported
        Log.e(TAG, "Error! Not implemented for CDMA.");
    }

    private int resultToCause(int rc) {
        switch (rc) {
        case Activity.RESULT_OK:
        case Intents.RESULT_SMS_HANDLED:
            // Cause code is ignored on success.
            return 0;
        case Intents.RESULT_SMS_OUT_OF_MEMORY:
            return CommandsInterface.CDMA_SMS_FAIL_CAUSE_RESOURCE_SHORTAGE;
        case Intents.RESULT_SMS_UNSUPPORTED:
            return CommandsInterface.CDMA_SMS_FAIL_CAUSE_INVALID_TELESERVICE_ID;
        case Intents.RESULT_SMS_GENERIC_ERROR:
        default:
            return CommandsInterface.CDMA_SMS_FAIL_CAUSE_ENCODING_PROBLEM;
        }
    }

    /**
     * Optional check to see if the received WapPush is an OMADM notification with erroneous
     * extra port fields.
     * - Some carriers make this mistake.
     * ex: MSGTYPE-TotalSegments-CurrentSegment
     *       -SourcePortDestPort-SourcePortDestPort-OMADM PDU
     * @param origPdu The WAP-WDP PDU segment
     * @param index Current Index while parsing the PDU.
     * @return True if OrigPdu is OmaDM Push Message which has duplicate ports.
     *         False if OrigPdu is NOT OmaDM Push Message which has duplicate ports.
     */
    private boolean checkDuplicatePortOmadmWappush(byte[] origPdu, int index) {
        index += 4;
        byte[] omaPdu = new byte[origPdu.length - index];
        System.arraycopy(origPdu, index, omaPdu, 0, omaPdu.length);

        WspTypeDecoder pduDecoder = new WspTypeDecoder(omaPdu);
        int wspIndex = 2;

        // Process header length field
        if (pduDecoder.decodeUintvarInteger(wspIndex) == false) {
            return false;
        }

        wspIndex += pduDecoder.getDecodedDataLength(); // advance to next field

        // Process content type field
        if (pduDecoder.decodeContentType(wspIndex) == false) {
            return false;
        }

        String mimeType = pduDecoder.getValueString();
        if (mimeType == null) {
            int binaryContentType = (int)pduDecoder.getValue32();
            if (binaryContentType == WspTypeDecoder.CONTENT_TYPE_B_PUSH_SYNCML_NOTI) {
                return true;
            }
        }
        return false;
    }
}
