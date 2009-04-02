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

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Message;
import android.telephony.ServiceState;
import android.util.Config;
import android.util.Log;

import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.gsm.SmsMessage;
import com.android.internal.telephony.SMSDispatcher;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.SmsMessageBase;

import java.util.ArrayList;
import java.util.HashMap;


final class GsmSMSDispatcher extends SMSDispatcher {
    private static final String TAG = "GSM";

    GsmSMSDispatcher(GSMPhone phone) {
        super(phone);
    }

    /**
     * Called when a status report is received.  This should correspond to
     * a previously successful SEND.
     *
     * @param ar AsyncResult passed into the message handler.  ar.result should
     *           be a String representing the status report PDU, as ASCII hex.
     */
    protected void handleStatusReport(AsyncResult ar) {
        String pduString = (String) ar.result;
        SmsMessage sms = SmsMessage.newFromCDS(pduString);

        if (sms != null) {
            int messageRef = sms.messageRef;
            for (int i = 0, count = deliveryPendingList.size(); i < count; i++) {
                SmsTracker tracker = deliveryPendingList.get(i);
                if (tracker.mMessageRef == messageRef) {
                    // Found it.  Remove from list and broadcast.
                    deliveryPendingList.remove(i);
                    PendingIntent intent = tracker.mDeliveryIntent;
                    Intent fillIn = new Intent();
                    fillIn.putExtra("pdu", IccUtils.hexStringToBytes(pduString));
                    try {
                        intent.send(mContext, Activity.RESULT_OK, fillIn);
                    } catch (CanceledException ex) {}

                    // Only expect to see one tracker matching this messageref
                    break;
                }
            }
        }

        if (mCm != null) {
            mCm.acknowledgeLastIncomingSMS(true, null);
        }
    }


    /**
     * Dispatches an incoming SMS messages.
     *
     * @param sms the incoming message from the phone
     */
    protected void dispatchMessage(SmsMessageBase smsb) {

        // If sms is null, means there was a parsing error.
        // TODO: Should NAK this.
        if (smsb == null) {
            return;
        }
        SmsMessage sms = (SmsMessage) smsb;
        boolean handled = false;

        // Special case the message waiting indicator messages
        if (sms.isMWISetMessage()) {
            ((GSMPhone) mPhone).updateMessageWaitingIndicator(true);

            if (sms.isMwiDontStore()) {
                handled = true;
            }

            if (Config.LOGD) {
                Log.d(TAG,
                        "Received voice mail indicator set SMS shouldStore="
                         + !handled);
            }
        } else if (sms.isMWIClearMessage()) {
            ((GSMPhone) mPhone).updateMessageWaitingIndicator(false);

            if (sms.isMwiDontStore()) {
                handled = true;
            }

            if (Config.LOGD) {
                Log.d(TAG,
                        "Received voice mail indicator clear SMS shouldStore="
                        + !handled);
            }
        }

        if (handled) {
            return;
        }

        // Parse the headers to see if this is partial, or port addressed
        int referenceNumber = -1;
        int count = 0;
        int sequence = 0;
        int destPort = -1;

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

            if (destPort != -1) {
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

    /** {@inheritDoc} */
    protected void sendMultipartText(String destinationAddress, String scAddress,
            ArrayList<String> parts, ArrayList<PendingIntent> sentIntents,
            ArrayList<PendingIntent> deliveryIntents) {
        int ref = ++sConcatenatedRef & 0xff;

        for (int i = 0, count = parts.size(); i < count; i++) {
            // build SmsHeader
            byte[] data = new byte[3];
            data[0] = (byte) ref;   // reference #, unique per message
            data[1] = (byte) count; // total part count
            data[2] = (byte) (i + 1);  // 1-based sequence
            SmsHeader header = new SmsHeader();
            header.add(new SmsHeader.Element(SmsHeader.CONCATENATED_8_BIT_REFERENCE, data));
            PendingIntent sentIntent = null;
            PendingIntent deliveryIntent = null;

            if (sentIntents != null && sentIntents.size() > i) {
                sentIntent = sentIntents.get(i);
            }
            if (deliveryIntents != null && deliveryIntents.size() > i) {
                deliveryIntent = deliveryIntents.get(i);
            }

            SmsMessage.SubmitPdu pdus = SmsMessage.getSubmitPdu(scAddress, destinationAddress,
                    parts.get(i), deliveryIntent != null, header.toByteArray());

            sendRawPdu(pdus.encodedScAddress, pdus.encodedMessage, sentIntent, deliveryIntent);
        }
    }

    /**
     * Send a multi-part text based SMS which already passed SMS control check.
     *
     * It is the working function for sendMultipartText().
     * 
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *   the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK<code> for success,
     *   or one of these errors:
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *   <code>RESULT_ERROR_RADIO_OFF</code>
     *   <code>RESULT_ERROR_NULL_PDU</code>.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
     */
    private void sendMultipartTextWithPermit(String destinationAddress, 
            String scAddress, ArrayList<String> parts,
            ArrayList<PendingIntent> sentIntents, 
            ArrayList<PendingIntent> deliveryIntents) {
        
        PendingIntent sentIntent = null;
        PendingIntent deliveryIntent = null;
        
        // check if in service
        int ss = mPhone.getServiceState().getState();
        if (ss != ServiceState.STATE_IN_SERVICE) {
            for (int i = 0, count = parts.size(); i < count; i++) {
                if (sentIntents != null && sentIntents.size() > i) {
                    sentIntent = sentIntents.get(i);
                }
                SmsTracker tracker = SmsTrackerFactory(null, sentIntent, null);
                handleNotInService(ss, tracker);
            }
            return;
        }

        int ref = ++sConcatenatedRef & 0xff;

        for (int i = 0, count = parts.size(); i < count; i++) {
            // build SmsHeader
            byte[] data = new byte[3];
            data[0] = (byte) ref;   // reference #, unique per message
            data[1] = (byte) count; // total part count
            data[2] = (byte) (i + 1);  // 1-based sequence
            SmsHeader header = new SmsHeader();
            header.add(new SmsHeader.Element(SmsHeader.CONCATENATED_8_BIT_REFERENCE, data));
 
            if (sentIntents != null && sentIntents.size() > i) {
                sentIntent = sentIntents.get(i);
            }
            if (deliveryIntents != null && deliveryIntents.size() > i) {
                deliveryIntent = deliveryIntents.get(i);
            }

            SmsMessage.SubmitPdu pdus = SmsMessage.getSubmitPdu(scAddress, destinationAddress,
                    parts.get(i), deliveryIntent != null, header.toByteArray());

            HashMap<String, Object> map = new HashMap<String, Object>();
            map.put("smsc", pdus.encodedScAddress);
            map.put("pdu", pdus.encodedMessage);

            SmsTracker tracker =  SmsTrackerFactory(map, sentIntent, deliveryIntent);
            sendSms(tracker);
        }        
    }

    /** {@inheritDoc} */
    protected void sendSms(SmsTracker tracker) {
        HashMap map = tracker.mData;

        byte smsc[] = (byte[]) map.get("smsc");
        byte pdu[] = (byte[]) map.get("pdu");

        Message reply = obtainMessage(EVENT_SEND_SMS_COMPLETE, tracker);
        mCm.sendSMS(IccUtils.bytesToHexString(smsc),
                IccUtils.bytesToHexString(pdu), reply);
    }

    /**
     * Send the multi-part SMS based on multipart Sms tracker
     * 
     * @param tracker holds the multipart Sms tracker ready to be sent
     */
    protected void sendMultipartSms (SmsTracker tracker) {
        ArrayList<String> parts;
        ArrayList<PendingIntent> sentIntents;
        ArrayList<PendingIntent> deliveryIntents;
        
        HashMap map = tracker.mData;
        
        String destinationAddress = (String) map.get("destination");
        String scAddress = (String) map.get("scaddress");
        
        parts = (ArrayList<String>) map.get("parts");
        sentIntents = (ArrayList<PendingIntent>) map.get("sentIntents");
        deliveryIntents = (ArrayList<PendingIntent>) map.get("deliveryIntents");
     
        sendMultipartTextWithPermit(destinationAddress, 
                scAddress, parts, sentIntents, deliveryIntents);

    }

    /** {@inheritDoc} */
    protected void acknowledgeLastIncomingSms(boolean success, Message response){
        // FIXME unit test leaves cm == null. this should change
        if (mCm != null) {
            mCm.acknowledgeLastIncomingSMS(success, response);
        }
    }

    /** {@inheritDoc} */
    protected void activateCellBroadcastSms(int activate, Message response) {
        // Unless CBS is implemented for GSM, this point should be unreachable.
        Log.e(TAG, "Error! The functionality cell broadcast sms is not implemented for GSM.");
        response.recycle();
    }

    /** {@inheritDoc} */
    protected void getCellBroadcastSmsConfig(Message response){
        // Unless CBS is implemented for GSM, this point should be unreachable.
        Log.e(TAG, "Error! The functionality cell broadcast sms is not implemented for GSM.");
        response.recycle();
    }

    /** {@inheritDoc} */
    protected  void setCellBroadcastConfig(int[] configValuesArray, Message response) {
        // Unless CBS is implemented for GSM, this point should be unreachable.
        Log.e(TAG, "Error! The functionality cell broadcast sms is not implemented for GSM.");
        response.recycle();
    }

}

