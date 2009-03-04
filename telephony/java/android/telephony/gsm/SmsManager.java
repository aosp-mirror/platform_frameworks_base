/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.app.PendingIntent;
import android.os.RemoteException;
import android.os.IServiceManager;
import android.os.ServiceManager;
import android.os.ServiceManagerNative;
import android.text.TextUtils;

import com.android.internal.telephony.gsm.EncodeException;
import com.android.internal.telephony.gsm.GsmAlphabet;
import com.android.internal.telephony.gsm.ISms;
import com.android.internal.telephony.gsm.SimConstants;
import com.android.internal.telephony.gsm.SmsRawData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages SMS operations such as sending data, text, and pdu SMS messages.
 * Get this object by calling the static method SmsManager.getDefault().
 */
public final class SmsManager {
    private static SmsManager sInstance;

    /**
     * Send a text based SMS.
     *
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *  the current default SMSC
     * @param text the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is sucessfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *  <code>RESULT_ERROR_RADIO_OFF</code>
     *  <code>RESULT_ERROR_NULL_PDU</code>.
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applicaitons,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     *
     * @throws IllegalArgumentException if destinationAddress or text are empty
     */
    public void sendTextMessage(
            String destinationAddress, String scAddress, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }

        if (TextUtils.isEmpty(text)) {
            throw new IllegalArgumentException("Invalid message body");
        }

        SmsMessage.SubmitPdu pdus = SmsMessage.getSubmitPdu(
                scAddress, destinationAddress, text, (deliveryIntent != null));
        sendRawPdu(pdus.encodedScAddress, pdus.encodedMessage, sentIntent, deliveryIntent);
    }

    /**
     * Divide a text message into several messages, none bigger than
     * the maximum SMS message size.
     *
     * @param text the original message.  Must not be null.
     * @return an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     */
    public ArrayList<String> divideMessage(String text) {
        int size = text.length();
        int[] params = SmsMessage.calculateLength(text, false);
            /* SmsMessage.calculateLength returns an int[4] with:
             *   int[0] being the number of SMS's required,
             *   int[1] the number of code units used,
             *   int[2] is the number of code units remaining until the next message.
             *   int[3] is the encoding type that should be used for the message.
             */
        int messageCount = params[0];
        int encodingType = params[3];
        ArrayList<String> result = new ArrayList<String>(messageCount);

        int start = 0;
        int limit;
        
        if (messageCount > 1) {
            limit = (encodingType == SmsMessage.ENCODING_7BIT) ?
                    SmsMessage.MAX_USER_DATA_SEPTETS_WITH_HEADER :
                        SmsMessage.MAX_USER_DATA_BYTES_WITH_HEADER;            
        } else {
            limit = (encodingType == SmsMessage.ENCODING_7BIT) ?
                SmsMessage.MAX_USER_DATA_SEPTETS : SmsMessage.MAX_USER_DATA_BYTES;            
        }

        try {
            while (start < size) {
                int end = GsmAlphabet.findLimitIndex(text, start, limit, encodingType);
                result.add(text.substring(start, end));
                start = end;
            }
        } catch (EncodeException e) {
            // ignore it.
        }
        return result;
    }

    /**
     * Send a multi-part text based SMS.  The callee should have already
     * divided the message into correctly sized parts by calling
     * <code>divideMessage</code>.
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
     *   The per-application based SMS control checks sentIntent. If sentIntent
     *   is NULL the caller will be checked against all unknown applicaitons,
     *   which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of 
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
     */
    public void sendMultipartTextMessage(
            String destinationAddress, String scAddress, ArrayList<String> parts,
            ArrayList<PendingIntent> sentIntents, ArrayList<PendingIntent> deliveryIntents) {
        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }
        if (parts == null || parts.size() < 1) {
            throw new IllegalArgumentException("Invalid message body");
        }
        
        if (parts.size() > 1) {
            try {
                ISms simISms = ISms.Stub.asInterface(ServiceManager.getService("isms"));
                if (simISms != null) {
                    simISms.sendMultipartText(destinationAddress, scAddress, parts,
                            sentIntents, deliveryIntents);
                }
            } catch (RemoteException ex) {
                // ignore it
            }
        } else {
            PendingIntent sentIntent = null;
            PendingIntent deliveryIntent = null;
            if (sentIntents != null && sentIntents.size() > 0) {
                sentIntent = sentIntents.get(0);
            }
            if (deliveryIntents != null && deliveryIntents.size() > 0) {
                deliveryIntent = deliveryIntents.get(0);
            }
            sendTextMessage(destinationAddress, scAddress, parts.get(0),
                    sentIntent, deliveryIntent);
        }
    }

    /**
     * Send a data based SMS to a specific application port.
     *
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *  the current default SMSC
     * @param destinationPort the port to deliver the message to
     * @param data the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is sucessfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *  <code>RESULT_ERROR_RADIO_OFF</code>
     *  <code>RESULT_ERROR_NULL_PDU</code>.
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applicaitons,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     *
     * @throws IllegalArgumentException if destinationAddress or data are empty
     */
    public void sendDataMessage(
            String destinationAddress, String scAddress, short destinationPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }

        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Invalid message data");
        }

        SmsMessage.SubmitPdu pdus = SmsMessage.getSubmitPdu(scAddress, destinationAddress,
                destinationPort, data, (deliveryIntent != null));
        sendRawPdu(pdus.encodedScAddress, pdus.encodedMessage, sentIntent, deliveryIntent);
    }

    /**
     * Send a raw SMS PDU.
     *
     * @param smsc the SMSC to send the message through, or NULL for the
     *  default SMSC
     * @param pdu the raw PDU to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is sucessfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *  <code>RESULT_ERROR_RADIO_OFF</code>
     *  <code>RESULT_ERROR_NULL_PDU</code>.
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applicaitons,
     *  which cause smaller number of SMS to be sent in checking period. 
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     *
     */
    private void sendRawPdu(byte[] smsc, byte[] pdu, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        try {
            ISms simISms = ISms.Stub.asInterface(ServiceManager.getService("isms"));
            if (simISms != null) {
                simISms.sendRawPdu(smsc, pdu, sentIntent, deliveryIntent);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Get the default instance of the SmsManager
     *
     * @return the default instance of the SmsManager
     */
    public static SmsManager getDefault() {
        if (sInstance == null) {
            sInstance = new SmsManager();
        }
        return sInstance;
    }

    private SmsManager() {
        // nothing to see here
    }

    /**
     * Copy a raw SMS PDU to the SIM.
     *
     * @param smsc the SMSC for this message, or NULL for the default SMSC
     * @param pdu the raw PDU to store
     * @param status message status (STATUS_ON_SIM_READ, STATUS_ON_SIM_UNREAD,
     *               STATUS_ON_SIM_SENT, STATUS_ON_SIM_UNSENT)
     * @return true for success
     *
     * {@hide}
     */
    public boolean copyMessageToSim(byte[] smsc, byte[] pdu, int status) {
        boolean success = false;

        try {
            ISms simISms = ISms.Stub.asInterface(ServiceManager.getService("isms"));
            if (simISms != null) {
                success = simISms.copyMessageToSimEf(status, pdu, smsc);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return success;
    }

    /**
     * Delete the specified message from the SIM.
     *
     * @param messageIndex is the record index of the message on SIM
     * @return true for success
     *
     * {@hide}
     */
    public boolean
    deleteMessageFromSim(int messageIndex) {
        boolean success = false;
        byte[] pdu = new byte[SimConstants.SMS_RECORD_LENGTH-1];
        Arrays.fill(pdu, (byte)0xff);

        try {
            ISms simISms = ISms.Stub.asInterface(ServiceManager.getService("isms"));
            if (simISms != null) {
                success = simISms.updateMessageOnSimEf(messageIndex,
                        STATUS_ON_SIM_FREE, pdu);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return success;
    }

    /**
     * Update the specified message on the SIM.
     *
     * @param messageIndex record index of message to update
     * @param newStatus new message status (STATUS_ON_SIM_READ,
     *                  STATUS_ON_SIM_UNREAD, STATUS_ON_SIM_SENT,
     *                  STATUS_ON_SIM_UNSENT, STATUS_ON_SIM_FREE)
     * @param pdu the raw PDU to store
     * @return true for success
     *
     * {@hide}
     */
    public boolean updateMessageOnSim(int messageIndex, int newStatus,
            byte[] pdu) {
        boolean success = false;

        try {
            ISms simISms = ISms.Stub.asInterface(ServiceManager.getService("isms"));
            if (simISms != null) {
                success = simISms.updateMessageOnSimEf(messageIndex, newStatus, pdu);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return success;
    }


    /**
     * Retrieves all messages currently stored on SIM.
     *
     * @return <code>ArrayList</code> of <code>SmsMessage</code> objects
     *
     * {@hide}
     */
    public ArrayList<SmsMessage> getAllMessagesFromSim() {
        List<SmsRawData> records = null;

        try {
            ISms simISms = ISms.Stub.asInterface(ServiceManager.getService("isms"));
            if (simISms != null) {
                records = simISms.getAllMessagesFromSimEf();
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        
        return createMessageListFromRawRecords(records); 
   }

    /**
     * Create a list of <code>SmsMessage</code>s from a list of RawSmsData
     * records returned by <code>getAllMessagesFromSim()</code>
     *
     * @param records SMS EF records, returned by
     *   <code>getAllMessagesFromSim</code>
     * @return <code>ArrayList</code> of <code>SmsMessage</code> objects.
     */
    private ArrayList<SmsMessage> createMessageListFromRawRecords(List records) {
        ArrayList<SmsMessage> messages = new ArrayList<SmsMessage>();
        if (records != null) {
            int count = records.size();
            for (int i = 0; i < count; i++) {
                SmsRawData data = (SmsRawData)records.get(i);
                // List contains all records, including "free" records (null)
                if (data != null) {
                    SmsMessage sms =
                            SmsMessage.createFromEfRecord(i+1, data.getBytes());
                    messages.add(sms);
                }
            }
        }
        return messages;
    }

    /** Free space (TS 51.011 10.5.3). */
    static public final int STATUS_ON_SIM_FREE      = 0;

    /** Received and read (TS 51.011 10.5.3). */
    static public final int STATUS_ON_SIM_READ      = 1;

    /** Received and unread (TS 51.011 10.5.3). */
    static public final int STATUS_ON_SIM_UNREAD    = 3;

    /** Stored and sent (TS 51.011 10.5.3). */
    static public final int STATUS_ON_SIM_SENT      = 5;

    /** Stored and unsent (TS 51.011 10.5.3). */
    static public final int STATUS_ON_SIM_UNSENT    = 7;


    // SMS send failure result codes

    /** Generic failure cause */
    static public final int RESULT_ERROR_GENERIC_FAILURE    = 1;
    /** Failed because radio was explicitly turned off */
    static public final int RESULT_ERROR_RADIO_OFF          = 2;
    /** Failed because no pdu provided */
    static public final int RESULT_ERROR_NULL_PDU           = 3;
    /** Failed because service is currently unavailable */
    static public final int RESULT_ERROR_NO_SERVICE         = 4;
}
