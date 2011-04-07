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


package com.android.internal.telephony;

import android.app.Activity;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.provider.Telephony;
import android.provider.Telephony.Sms.Intents;
import android.util.Log;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * WAP push handler class.
 *
 * @hide
 */
public class WapPushOverSms {
    private static final String LOG_TAG = "WAP PUSH";

    private final Context mContext;
    private WspTypeDecoder pduDecoder;
    private SMSDispatcher mSmsDispatcher;

    /**
     * Hold the wake lock for 5 seconds, which should be enough time for
     * any receiver(s) to grab its own wake lock.
     */
    private final int WAKE_LOCK_TIMEOUT = 5000;

    private final int BIND_RETRY_INTERVAL = 1000;
    /**
     * A handle to WapPushManager interface
     */
    private WapPushConnection mWapConn = null;
    private class WapPushConnection implements ServiceConnection {
        private IWapPushManager mWapPushMan;
        private Context mOwner;

        public WapPushConnection(Context ownerContext) {
            mOwner = ownerContext;
        }

        public void onServiceConnected(ComponentName name, IBinder service) {
            mWapPushMan = IWapPushManager.Stub.asInterface(service);
            if (false) Log.v(LOG_TAG, "wappush manager connected to " +
                    mOwner.hashCode());
        }

        public void onServiceDisconnected(ComponentName name) {
            mWapPushMan = null;
            if (false) Log.v(LOG_TAG, "wappush manager disconnected.");
            // WapPushManager must be always attached.
            rebindWapPushManager();
        }

        /**
         * bind WapPushManager
         */
        public void bindWapPushManager() {
            if (mWapPushMan != null) return;

            final ServiceConnection wapPushConnection = this;

            mOwner.bindService(new Intent(IWapPushManager.class.getName()),
                    wapPushConnection, Context.BIND_AUTO_CREATE);
        }

        /**
         * rebind WapPushManager
         * This method is called when WapPushManager is disconnected unexpectedly.
         */
        private void rebindWapPushManager() {
            if (mWapPushMan != null) return;

            final ServiceConnection wapPushConnection = this;
            new Thread() {
                public void run() {
                    while (mWapPushMan == null) {
                        mOwner.bindService(new Intent(IWapPushManager.class.getName()),
                                wapPushConnection, Context.BIND_AUTO_CREATE);
                        try {
                            Thread.sleep(BIND_RETRY_INTERVAL);
                        } catch (InterruptedException e) {
                            if (false) Log.v(LOG_TAG, "sleep interrupted.");
                        }
                    }
                }
            }.start();
        }

        /**
         * Returns interface to WapPushManager
         */
        public IWapPushManager getWapPushManager() {
            return mWapPushMan;
        }
    }

    public WapPushOverSms(Phone phone, SMSDispatcher smsDispatcher) {
        mSmsDispatcher = smsDispatcher;
        mContext = phone.getContext();
        mWapConn = new WapPushConnection(mContext);
        mWapConn.bindWapPushManager();
    }


    /**
     * Dispatches inbound messages that are in the WAP PDU format. See
     * wap-230-wsp-20010705-a section 8 for details on the WAP PDU format.
     *
     * @param pdu The WAP PDU, made up of one or more SMS PDUs
     * @return a result code from {@link Telephony.Sms.Intents}, or
     *         {@link Activity#RESULT_OK} if the message has been broadcast
     *         to applications
     */
    public int dispatchWapPdu(byte[] pdu) {

        if (false) Log.d(LOG_TAG, "Rx: " + IccUtils.bytesToHexString(pdu));

        int index = 0;
        int transactionId = pdu[index++] & 0xFF;
        int pduType = pdu[index++] & 0xFF;
        int headerLength = 0;

        if ((pduType != WspTypeDecoder.PDU_TYPE_PUSH) &&
                (pduType != WspTypeDecoder.PDU_TYPE_CONFIRMED_PUSH)) {
            if (false) Log.w(LOG_TAG, "Received non-PUSH WAP PDU. Type = " + pduType);
            return Intents.RESULT_SMS_HANDLED;
        }

        pduDecoder = new WspTypeDecoder(pdu);

        /**
         * Parse HeaderLen(unsigned integer).
         * From wap-230-wsp-20010705-a section 8.1.2
         * The maximum size of a uintvar is 32 bits.
         * So it will be encoded in no more than 5 octets.
         */
        if (pduDecoder.decodeUintvarInteger(index) == false) {
            if (false) Log.w(LOG_TAG, "Received PDU. Header Length error.");
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }
        headerLength = (int)pduDecoder.getValue32();
        index += pduDecoder.getDecodedDataLength();

        int headerStartIndex = index;

        /**
         * Parse Content-Type.
         * From wap-230-wsp-20010705-a section 8.4.2.24
         *
         * Content-type-value = Constrained-media | Content-general-form
         * Content-general-form = Value-length Media-type
         * Media-type = (Well-known-media | Extension-Media) *(Parameter)
         * Value-length = Short-length | (Length-quote Length)
         * Short-length = <Any octet 0-30>   (octet <= WAP_PDU_SHORT_LENGTH_MAX)
         * Length-quote = <Octet 31>         (WAP_PDU_LENGTH_QUOTE)
         * Length = Uintvar-integer
         */
        if (pduDecoder.decodeContentType(index) == false) {
            if (false) Log.w(LOG_TAG, "Received PDU. Header Content-Type error.");
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }

        String mimeType = pduDecoder.getValueString();
        long binaryContentType = pduDecoder.getValue32();
        index += pduDecoder.getDecodedDataLength();

        byte[] header = new byte[headerLength];
        System.arraycopy(pdu, headerStartIndex, header, 0, header.length);

        byte[] intentData;

        if (mimeType != null && mimeType.equals(WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO)) {
            intentData = pdu;
        } else {
            int dataIndex = headerStartIndex + headerLength;
            intentData = new byte[pdu.length - dataIndex];
            System.arraycopy(pdu, dataIndex, intentData, 0, intentData.length);
        }

        /**
         * Seek for application ID field in WSP header.
         * If application ID is found, WapPushManager substitute the message
         * processing. Since WapPushManager is optional module, if WapPushManager
         * is not found, legacy message processing will be continued.
         */
        if (pduDecoder.seekXWapApplicationId(index, index + headerLength - 1)) {
            index = (int) pduDecoder.getValue32();
            pduDecoder.decodeXWapApplicationId(index);
            String wapAppId = pduDecoder.getValueString();
            if (wapAppId == null) {
                wapAppId = Integer.toString((int) pduDecoder.getValue32());
            }

            String contentType = ((mimeType == null) ?
                                  Long.toString(binaryContentType) : mimeType);
            if (false) Log.v(LOG_TAG, "appid found: " + wapAppId + ":" + contentType);

            try {
                boolean processFurther = true;
                IWapPushManager wapPushMan = mWapConn.getWapPushManager();

                if (wapPushMan == null) {
                    if (false) Log.w(LOG_TAG, "wap push manager not found!");
                } else {
                    Intent intent = new Intent();
                    intent.putExtra("transactionId", transactionId);
                    intent.putExtra("pduType", pduType);
                    intent.putExtra("header", header);
                    intent.putExtra("data", intentData);
                    intent.putExtra("contentTypeParameters",
                            pduDecoder.getContentParameters());

                    int procRet = wapPushMan.processMessage(wapAppId, contentType, intent);
                    if (false) Log.v(LOG_TAG, "procRet:" + procRet);
                    if ((procRet & WapPushManagerParams.MESSAGE_HANDLED) > 0
                        && (procRet & WapPushManagerParams.FURTHER_PROCESSING) == 0) {
                        processFurther = false;
                    }
                }
                if (!processFurther) {
                    return Intents.RESULT_SMS_HANDLED;
                }
            } catch (RemoteException e) {
                if (false) Log.w(LOG_TAG, "remote func failed...");
            }
        }
        if (false) Log.v(LOG_TAG, "fall back to existing handler");

        if (mimeType == null) {
            if (false) Log.w(LOG_TAG, "Header Content-Type error.");
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }

        String permission;

        if (mimeType.equals(WspTypeDecoder.CONTENT_TYPE_B_MMS)) {
            permission = "android.permission.RECEIVE_MMS";
        } else {
            permission = "android.permission.RECEIVE_WAP_PUSH";
        }

        Intent intent = new Intent(Intents.WAP_PUSH_RECEIVED_ACTION);
        intent.setType(mimeType);
        intent.putExtra("transactionId", transactionId);
        intent.putExtra("pduType", pduType);
        intent.putExtra("header", header);
        intent.putExtra("data", intentData);
        intent.putExtra("contentTypeParameters", pduDecoder.getContentParameters());

        mSmsDispatcher.dispatch(intent, permission);

        return Activity.RESULT_OK;
    }
}
