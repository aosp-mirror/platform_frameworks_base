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

import java.util.ArrayList;


/**
 * Manages SMS operations such as sending data, text, and pdu SMS messages.
 * Get this object by calling the static method SmsManager.getDefault().
 * @deprecated Replaced by android.telephony.SmsManager that supports both GSM and CDMA.
 */
@Deprecated public final class SmsManager {
    private static SmsManager sInstance;
    private android.telephony.SmsManager mSmsMgrProxy;

    /** Get the default instance of the SmsManager
     *
     * @return the default instance of the SmsManager
     * @deprecated Use android.telephony.SmsManager.
     */
    @Deprecated
    public static final SmsManager getDefault() {
        if (sInstance == null) {
            sInstance = new SmsManager();
        }
        return sInstance;
    }

    @Deprecated
    private SmsManager() {
        mSmsMgrProxy = android.telephony.SmsManager.getDefault();
    }

    /**
     * Send a text based SMS.
     *
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *  the current default SMSC
     * @param text the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *  <code>RESULT_ERROR_RADIO_OFF</code>
     *  <code>RESULT_ERROR_NULL_PDU</code>.
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     *
     * @throws IllegalArgumentException if destinationAddress or text are empty
     * @deprecated Use android.telephony.SmsManager.
     */
    @Deprecated
    public final void sendTextMessage(
            String destinationAddress, String scAddress, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        mSmsMgrProxy.sendTextMessage(destinationAddress, scAddress, text,
                sentIntent, deliveryIntent);
    }

    /**
     * Divide a text message into several messages, none bigger than
     * the maximum SMS message size.
     *
     * @param text the original message.  Must not be null.
     * @return an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @deprecated Use android.telephony.SmsManager.
     */
    @Deprecated
    public final ArrayList<String> divideMessage(String text) {
        return mSmsMgrProxy.divideMessage(text);
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
     *
     * @throws IllegalArgumentException if destinationAddress or data are empty
     * @deprecated Use android.telephony.SmsManager.
     */
    @Deprecated
    public final void sendMultipartTextMessage(
            String destinationAddress, String scAddress, ArrayList<String> parts,
            ArrayList<PendingIntent> sentIntents, ArrayList<PendingIntent> deliveryIntents) {
        mSmsMgrProxy.sendMultipartTextMessage(destinationAddress, scAddress, parts,
                sentIntents, deliveryIntents);
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
     * @deprecated Use android.telephony.SmsManager.
     */
    @Deprecated
    public final void sendDataMessage(
            String destinationAddress, String scAddress, short destinationPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        mSmsMgrProxy.sendDataMessage(destinationAddress, scAddress, destinationPort,
                data, sentIntent, deliveryIntent);
    }

    /**
     * Copy a raw SMS PDU to the SIM.
     *
     * @param smsc the SMSC for this message, or NULL for the default SMSC
     * @param pdu the raw PDU to store
     * @param status message status (STATUS_ON_SIM_READ, STATUS_ON_SIM_UNREAD,
     *               STATUS_ON_SIM_SENT, STATUS_ON_SIM_UNSENT)
     * @return true for success
     * @deprecated Use android.telephony.SmsManager.
     * {@hide}
     */
    @Deprecated
    public final boolean copyMessageToSim(byte[] smsc, byte[] pdu, int status) {
        return mSmsMgrProxy.copyMessageToIcc(smsc, pdu, status);
    }

    /**
     * Delete the specified message from the SIM.
     *
     * @param messageIndex is the record index of the message on SIM
     * @return true for success
     * @deprecated Use android.telephony.SmsManager.
     * {@hide}
     */
    @Deprecated
    public final boolean deleteMessageFromSim(int messageIndex) {
        return mSmsMgrProxy.deleteMessageFromIcc(messageIndex);
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
     * @deprecated Use android.telephony.SmsManager.
     * {@hide}
     */
    @Deprecated
    public final boolean updateMessageOnSim(int messageIndex, int newStatus, byte[] pdu) {
        return mSmsMgrProxy.updateMessageOnIcc(messageIndex, newStatus, pdu);
    }

    /**
     * Retrieves all messages currently stored on SIM.
     * @return <code>ArrayList</code> of <code>SmsMessage</code> objects
     * @deprecated Use android.telephony.SmsManager.
     * {@hide}
     */
    @Deprecated
    public final ArrayList<android.telephony.SmsMessage> getAllMessagesFromSim() {
        return mSmsMgrProxy.getAllMessagesFromIcc();
    }

    /** Free space (TS 51.011 10.5.3).
     *  @deprecated Use android.telephony.SmsManager. */
    @Deprecated static public final int STATUS_ON_SIM_FREE      = 0;

    /** Received and read (TS 51.011 10.5.3).
     * @deprecated Use android.telephony.SmsManager. */
    @Deprecated static public final int STATUS_ON_SIM_READ      = 1;

    /** Received and unread (TS 51.011 10.5.3).
     * @deprecated Use android.telephony.SmsManager. */
    @Deprecated static public final int STATUS_ON_SIM_UNREAD    = 3;

    /** Stored and sent (TS 51.011 10.5.3).
     * @deprecated Use android.telephony.SmsManager. */
    @Deprecated static public final int STATUS_ON_SIM_SENT      = 5;

    /** Stored and unsent (TS 51.011 10.5.3).
     * @deprecated Use android.telephony.SmsManager. */
    @Deprecated static public final int STATUS_ON_SIM_UNSENT    = 7;

    /** Generic failure cause
     * @deprecated Use android.telephony.SmsManager. */
    @Deprecated static public final int RESULT_ERROR_GENERIC_FAILURE    = 1;

    /** Failed because radio was explicitly turned off
     * @deprecated Use android.telephony.SmsManager. */
    @Deprecated static public final int RESULT_ERROR_RADIO_OFF          = 2;

    /** Failed because no pdu provided
     * @deprecated Use android.telephony.SmsManager. */
    @Deprecated static public final int RESULT_ERROR_NULL_PDU           = 3;

    /** Failed because service is currently unavailable
     * @deprecated Use android.telephony.SmsManager. */
    @Deprecated static public final int RESULT_ERROR_NO_SERVICE         = 4;

}
