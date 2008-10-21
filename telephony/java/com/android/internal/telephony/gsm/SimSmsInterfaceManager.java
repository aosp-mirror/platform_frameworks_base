/*
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.internal.telephony.gsm;

import android.app.PendingIntent;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.telephony.gsm.SmsManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * SimSmsInterfaceManager to provide an inter-process communication to
 * access Sms in Sim.
 */
public class SimSmsInterfaceManager extends ISms.Stub {
    static final String LOG_TAG = "GSM";
    static final boolean DBG = false;

    private GSMPhone mPhone;
    private final Object mLock = new Object();
    private boolean mSuccess;
    private List<SmsRawData> mSms;

    private static final int EVENT_LOAD_DONE = 1;
    private static final int EVENT_UPDATE_DONE = 2;

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case EVENT_UPDATE_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        mSuccess = (ar.exception == null);
                        mLock.notifyAll();
                    }
                    break;
                case EVENT_LOAD_DONE:
                    ar = (AsyncResult)msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            mSms  = (List<SmsRawData>)
                                    buildValidRawData((ArrayList<byte[]>) ar.result);
                        } else {
                            if(DBG) log("Cannot load Sms records");
                            if (mSms != null)
                                mSms.clear();
                        }
                        mLock.notifyAll();
                    }
                    break;
            }
        }
    };

    public SimSmsInterfaceManager(GSMPhone phone) {
        this.mPhone = phone;
        ServiceManager.addService("isms", this);
    }

    private void enforceReceiveAndSend(String message) {
        Context context = mPhone.getContext();

        context.enforceCallingPermission(
                "android.permission.RECEIVE_SMS", message);
        context.enforceCallingPermission(
                "android.permission.SEND_SMS", message);
    }

    /**
     * Update the specified message on the SIM.
     *
     * @param index record index of message to update
     * @param status new message status (STATUS_ON_SIM_READ,
     *                  STATUS_ON_SIM_UNREAD, STATUS_ON_SIM_SENT,
     *                  STATUS_ON_SIM_UNSENT, STATUS_ON_SIM_FREE)
     * @param pdu the raw PDU to store
     * @return success or not
     *
     */
    public boolean
    updateMessageOnSimEf(int index, int status, byte[] pdu) {
        if (DBG) log("updateMessageOnSimEf: index=" + index +
                " status=" + status + " ==> " +
                "("+ pdu + ")");
        enforceReceiveAndSend("Updating message on SIM");
        synchronized(mLock) {
            mSuccess = false;
            Message response = mHandler.obtainMessage(EVENT_UPDATE_DONE);

            if (status == SmsManager.STATUS_ON_SIM_FREE) {
                // Special case FREE: call deleteSmsOnSim instead of
                // manipulating the SIM record
                mPhone.mCM.deleteSmsOnSim(index, response);
            } else {
                byte[] record = makeSmsRecordData(status, pdu);
                mPhone.mSIMFileHandler.updateEFLinearFixed( SimConstants.EF_SMS,
                        index, record, null, response);
            }
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to update by index");
            }
        }
        return mSuccess;
    }

    /**
     * Copy a raw SMS PDU to the SIM.
     *
     * @param pdu the raw PDU to store
     * @param status message status (STATUS_ON_SIM_READ, STATUS_ON_SIM_UNREAD,
     *               STATUS_ON_SIM_SENT, STATUS_ON_SIM_UNSENT)
     * @return success or not
     *
     */
    public boolean copyMessageToSimEf(int status, byte[] pdu, byte[] smsc) {
        if (DBG) log("copyMessageToSimEf: status=" + status + " ==> " +
                "pdu=("+ pdu + "), smsm=(" + smsc +")");
        enforceReceiveAndSend("Copying message to SIM");
        synchronized(mLock) {
            mSuccess = false;
            Message response = mHandler.obtainMessage(EVENT_UPDATE_DONE);

            mPhone.mCM.writeSmsToSim(status, SimUtils.bytesToHexString(smsc),
                    SimUtils.bytesToHexString(pdu), response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to update by index");
            }
        }
        return mSuccess;
    }

    /**
     * Retrieves all messages currently stored on SIM.
     *
     * @return list of SmsRawData of all sms on SIM
     */
    public List<SmsRawData> getAllMessagesFromSimEf() {
        if (DBG) log("getAllMessagesFromEF");

        Context context = mPhone.getContext();

        context.enforceCallingPermission(
                "android.permission.RECEIVE_SMS",
                "Reading messages from SIM");
        synchronized(mLock) {
            Message response = mHandler.obtainMessage(EVENT_LOAD_DONE);
            mPhone.mSIMFileHandler.loadEFLinearFixedAll(SimConstants.EF_SMS,
                    response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to load from the SIM");
            }
        }
        return mSms;
    }

    /**
     * Send a Raw PDU SMS
     *
     * @param smsc the SMSC to send the message through, or NULL for the
     *  defatult SMSC
     * @param pdu the raw PDU to send
     * @param sentIntent if not NULL this <code>Intent</code> is
     *  broadcast when the message is sucessfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *  <code>RESULT_ERROR_RADIO_OFF</code>
     *  <code>RESULT_ERROR_NULL_PDU</code>.
     * @param deliveryIntent if not NULL this <code>Intent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */
    public void sendRawPdu(byte[] smsc, byte[] pdu, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        Context context = mPhone.getContext();

        context.enforceCallingPermission(
                "android.permission.SEND_SMS",
                "Sending SMS message");
        if (DBG) log("sendRawPdu: smsc=" + smsc +
                " pdu="+ pdu + " sentIntent" + sentIntent +
                " deliveryIntent" + deliveryIntent);
        mPhone.mSMS.sendRawPdu(smsc, pdu, sentIntent, deliveryIntent);
    }

    /**
     * Send a multi-part text based SMS.
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
    public void sendMultipartText(String destinationAddress, String scAddress, List<String> parts,
            List<PendingIntent> sentIntents, List<PendingIntent> deliveryIntents) {
        Context context = mPhone.getContext();

        context.enforceCallingPermission(
                "android.permission.SEND_SMS",
                "Sending SMS message");
        if (DBG) log("sendMultipartText");
        mPhone.mSMS.sendMultipartText(destinationAddress, scAddress, (ArrayList<String>) parts,
                (ArrayList<PendingIntent>) sentIntents, (ArrayList<PendingIntent>) deliveryIntents);
    }

    /**
     * Generates an EF_SMS record from status and raw PDU.
     *
     * @param status Message status.  See TS 51.011 10.5.3.
     * @param pdu Raw message PDU.
     * @return byte array for the record.
     */
    private byte[] makeSmsRecordData(int status, byte[] pdu) {
        byte[] data = new byte[SimConstants.SMS_RECORD_LENGTH];

        // Status bits for this record.  See TS 51.011 10.5.3
        data[0] = (byte)(status & 7);

        System.arraycopy(pdu, 0, data, 1, pdu.length);

        // Pad out with 0xFF's.
        for (int j = pdu.length+1; j < SimConstants.SMS_RECORD_LENGTH; j++) {
            data[j] = -1;
        }

        return data;
    }

    /**
     * create SmsRawData lists from all sms record byte[]
     * Use null to indicate "free" record
     *
     * @param messages List of message records from EF_SMS.
     * @return SmsRawData list of all in-used records
     */
    private ArrayList<SmsRawData> buildValidRawData(ArrayList<byte[]> messages) {
        int count = messages.size();
        ArrayList<SmsRawData> ret;

        ret = new ArrayList<SmsRawData>(count);

        for (int i = 0; i < count; i++) {
            byte[] ba = messages.get(i);
            if (ba[0] == 0) {
                ret.add(null);
            } else {
                ret.add(new SmsRawData(messages.get(i)));
            }
        }

        return ret;
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "[SmsInterfaceManager] " + msg);
    }
}
