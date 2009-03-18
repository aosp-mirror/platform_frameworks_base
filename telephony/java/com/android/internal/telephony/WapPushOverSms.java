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

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Telephony.Sms.Intents;
import android.util.Config;
import android.util.Log;
import com.android.internal.telephony.gsm.SimUtils;


/**
 * WAP push handler class.
 *
 * @hide
 */
public class WapPushOverSms {
    private static final String LOG_TAG = "WAP PUSH";

    private final Context mContext;
    private WspTypeDecoder pduDecoder;
    private PowerManager.WakeLock mWakeLock;

    /**
     * Hold the wake lock for 5 seconds, which should be enough time for 
     * any receiver(s) to grab its own wake lock.
     */
    private final int WAKE_LOCK_TIMEOUT = 5000;

    public WapPushOverSms(Phone phone) {
        mContext = phone.getContext();
        createWakelock();
    }

    /**
     * Dispatches inbound messages that are in the WAP PDU format. See
     * wap-230-wsp-20010705-a section 8 for details on the WAP PDU format.
     *
     * @param pdu The WAP PDU, made up of one or more SMS PDUs
     */
    public void dispatchWapPdu(byte[] pdu) {

        if (Config.LOGD) Log.d(LOG_TAG, "Rx: " + SimUtils.bytesToHexString(pdu));

        int index = 0;
        int transactionId = pdu[index++] & 0xFF;
        int pduType = pdu[index++] & 0xFF;
        int headerLength = 0;

        if ((pduType != WspTypeDecoder.PDU_TYPE_PUSH) &&
                (pduType != WspTypeDecoder.PDU_TYPE_CONFIRMED_PUSH)) {
            if (Config.LOGD) Log.w(LOG_TAG, "Received non-PUSH WAP PDU. Type = " + pduType);
            return;
        }

        pduDecoder = new WspTypeDecoder(pdu);

        /**
         * Parse HeaderLen(unsigned integer).
         * From wap-230-wsp-20010705-a section 8.1.2
         * The maximum size of a uintvar is 32 bits.
         * So it will be encoded in no more than 5 octets.
         */
        if (pduDecoder.decodeUintvarInteger(index) == false) {
            if (Config.LOGD) Log.w(LOG_TAG, "Received PDU. Header Length error.");
            return;
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
            if (Config.LOGD) Log.w(LOG_TAG, "Received PDU. Header Content-Type error.");
            return;
        }
        int binaryContentType;
        String mimeType = pduDecoder.getValueString();
        if (mimeType == null) {
            binaryContentType = (int)pduDecoder.getValue32();
            switch (binaryContentType) {
                case WspTypeDecoder.CONTENT_TYPE_B_DRM_RIGHTS_XML:
                    mimeType = WspTypeDecoder.CONTENT_MIME_TYPE_B_DRM_RIGHTS_XML;
                    break;
                case WspTypeDecoder.CONTENT_TYPE_B_DRM_RIGHTS_WBXML:
                    mimeType = WspTypeDecoder.CONTENT_MIME_TYPE_B_DRM_RIGHTS_WBXML;
                    break;
                case WspTypeDecoder.CONTENT_TYPE_B_PUSH_SI:
                    mimeType = WspTypeDecoder.CONTENT_MIME_TYPE_B_PUSH_SI;
                    break;
                case WspTypeDecoder.CONTENT_TYPE_B_PUSH_SL:
                    mimeType = WspTypeDecoder.CONTENT_MIME_TYPE_B_PUSH_SL;
                    break;
                case WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO:
                    mimeType = WspTypeDecoder.CONTENT_MIME_TYPE_B_PUSH_CO;
                    break;
                case WspTypeDecoder.CONTENT_TYPE_B_MMS:
                    mimeType = WspTypeDecoder.CONTENT_MIME_TYPE_B_MMS;
                    break;
                default:
                    if (Config.LOGD) {
                        Log.w(LOG_TAG,
                                "Received PDU. Unsupported Content-Type = " + binaryContentType);
                    }
                return;
            }
        } else {
            if (mimeType.equals(WspTypeDecoder.CONTENT_MIME_TYPE_B_DRM_RIGHTS_XML)) {
                binaryContentType = WspTypeDecoder.CONTENT_TYPE_B_DRM_RIGHTS_XML;
            } else if (mimeType.equals(WspTypeDecoder.CONTENT_MIME_TYPE_B_DRM_RIGHTS_WBXML)) {
                binaryContentType = WspTypeDecoder.CONTENT_TYPE_B_DRM_RIGHTS_WBXML;
            } else if (mimeType.equals(WspTypeDecoder.CONTENT_MIME_TYPE_B_PUSH_SI)) {
                binaryContentType = WspTypeDecoder.CONTENT_TYPE_B_PUSH_SI;
            } else if (mimeType.equals(WspTypeDecoder.CONTENT_MIME_TYPE_B_PUSH_SL)) {
                binaryContentType = WspTypeDecoder.CONTENT_TYPE_B_PUSH_SL;
            } else if (mimeType.equals(WspTypeDecoder.CONTENT_MIME_TYPE_B_PUSH_CO)) {
                binaryContentType = WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO;
            } else if (mimeType.equals(WspTypeDecoder.CONTENT_MIME_TYPE_B_MMS)) {
                binaryContentType = WspTypeDecoder.CONTENT_TYPE_B_MMS;
            } else {
                if (Config.LOGD) Log.w(LOG_TAG, "Received PDU. Unknown Content-Type = " + mimeType);
                return;
            }
        }
        index += pduDecoder.getDecodedDataLength();

        int dataIndex = headerStartIndex + headerLength;
        boolean dispatchedByApplication = false;
        switch (binaryContentType) {
            case WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO:
                dispatchWapPdu_PushCO(pdu, transactionId, pduType);
                dispatchedByApplication = true;
                break;
            case WspTypeDecoder.CONTENT_TYPE_B_MMS:
                dispatchWapPdu_MMS(pdu, transactionId, pduType, dataIndex);
                dispatchedByApplication = true;
                break;
            default:
                break;
        }
        if (dispatchedByApplication == false) {
            dispatchWapPdu_default(pdu, transactionId, pduType, mimeType, dataIndex);
        }
    }

    private void dispatchWapPdu_default(
            byte[] pdu, int transactionId, int pduType, String mimeType, int dataIndex) {
        byte[] data;

        data = new byte[pdu.length - dataIndex];
        System.arraycopy(pdu, dataIndex, data, 0, data.length);

        Intent intent = new Intent(Intents.WAP_PUSH_RECEIVED_ACTION);
        intent.setType(mimeType);
        intent.putExtra("transactionId", transactionId);
        intent.putExtra("pduType", pduType);
        intent.putExtra("data", data);

        sendBroadcast(intent, "android.permission.RECEIVE_WAP_PUSH");
    }

    private void dispatchWapPdu_PushCO(byte[] pdu, int transactionId, int pduType) {
        Intent intent = new Intent(Intents.WAP_PUSH_RECEIVED_ACTION);
        intent.setType(WspTypeDecoder.CONTENT_MIME_TYPE_B_PUSH_CO);
        intent.putExtra("transactionId", transactionId);
        intent.putExtra("pduType", pduType);
        intent.putExtra("data", pdu);

        sendBroadcast(intent, "android.permission.RECEIVE_WAP_PUSH");
    }

    private void dispatchWapPdu_MMS(byte[] pdu, int transactionId, int pduType, int dataIndex) {
        byte[] data;

        data = new byte[pdu.length - dataIndex];
        System.arraycopy(pdu, dataIndex, data, 0, data.length);

        Intent intent = new Intent(Intents.WAP_PUSH_RECEIVED_ACTION);
        intent.setType(WspTypeDecoder.CONTENT_MIME_TYPE_B_MMS);
        intent.putExtra("transactionId", transactionId);
        intent.putExtra("pduType", pduType);
        intent.putExtra("data", data);

        sendBroadcast(intent, "android.permission.RECEIVE_MMS");
    }

    private void createWakelock() {
        PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WapPushOverSms");
        mWakeLock.setReferenceCounted(true);
    }

    private void sendBroadcast(Intent intent, String permission) {
        // Hold a wake lock for WAKE_LOCK_TIMEOUT seconds, enough to give any
        // receivers time to take their own wake locks.
        mWakeLock.acquire(WAKE_LOCK_TIMEOUT);
        mContext.sendBroadcast(intent, permission);
    }
}
