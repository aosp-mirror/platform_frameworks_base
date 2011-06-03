/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.cdma.sms.UserData;
import com.android.internal.telephony.gsm.SIMRecords;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.util.Log;


/**
 * {@hide}
 */
public final class CdmaLteUiccRecords extends SIMRecords {
    // From CSIM application
    private byte[] mEFpl = null;
    private byte[] mEFli = null;
    boolean csimSpnDisplayCondition = false;

    private static final int EVENT_GET_PL_DONE = CSIM_EVENT_BASE;
    private static final int EVENT_GET_CSIM_LI_DONE = CSIM_EVENT_BASE + 1;
    private static final int EVENT_GET_CSIM_SPN_DONE = CSIM_EVENT_BASE + 2;

    public CdmaLteUiccRecords(PhoneBase p) {
        super(p);
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        byte data[];

        boolean isCsimRecordLoadResponse = false;

        try { switch (msg.what) {
            case EVENT_GET_PL_DONE:
                // Refer to ETSI TS.102.221
                if (DBG) log("EF_GET_EF_PL_DONE");
                isCsimRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;

                if (ar.exception != null) {
                    Log.e(LOG_TAG, "ar.exception = " + ar.exception);
                    break;
                }

                mEFpl = (byte[]) ar.result;
                if (DBG) log("EF_PL=" + IccUtils.bytesToHexString(mEFpl));
                break;

            case EVENT_GET_CSIM_LI_DONE:
                // Refer to C.S0065 5.2.26
                if (DBG) log("EVENT_GET_CSIM_LI_DONE");
                isCsimRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    Log.e(LOG_TAG, "ar.exception = " + ar.exception);
                    break;
                }

                mEFli = (byte[]) ar.result;
                // convert csim efli data to iso 639 format
                for (int i = 0; i < mEFli.length; i+=2) {
                    switch(mEFli[i+1]) {
                    case 0x01: mEFli[i] = 'e'; mEFli[i+1] = 'n';break;
                    case 0x02: mEFli[i] = 'f'; mEFli[i+1] = 'r';break;
                    case 0x03: mEFli[i] = 'e'; mEFli[i+1] = 's';break;
                    case 0x04: mEFli[i] = 'j'; mEFli[i+1] = 'a';break;
                    case 0x05: mEFli[i] = 'k'; mEFli[i+1] = 'o';break;
                    case 0x06: mEFli[i] = 'z'; mEFli[i+1] = 'h';break;
                    case 0x07: mEFli[i] = 'h'; mEFli[i+1] = 'e';break;
                    default: mEFli[i] = ' '; mEFli[i+1] = ' ';
                    }
                }

                if (DBG) log("EF_LI=" + IccUtils.bytesToHexString(mEFli));
                break;
            case EVENT_GET_CSIM_SPN_DONE:
                // Refer to C.S0065 5.2.32
                if (DBG) log("EVENT_GET_CSIM_SPN_DONE");
                isCsimRecordLoadResponse = true;
                ar = (AsyncResult) msg.obj;

                if (ar.exception != null) {
                    Log.e(LOG_TAG, "ar.exception=" + ar.exception);
                    break;
                }
                onGetCSimSpnDone(ar);
                break;
            default:
                super.handleMessage(msg);
        }}catch (RuntimeException exc) {
            Log.w(LOG_TAG, "Exception parsing SIM record", exc);
        } finally {
            if (isCsimRecordLoadResponse) {
                onRecordLoaded();
            }
        }
    }

    @Override
    protected void onRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        recordsToLoad -= 1;

        if (recordsToLoad == 0 && recordsRequested == true) {
            onAllRecordsLoaded();
        } else if (recordsToLoad < 0) {
            Log.e(LOG_TAG, "SIMRecords: recordsToLoad <0, programmer error suspected");
            recordsToLoad = 0;
        }
    }

    @Override
    protected void fetchSimRecords() {
        IccFileHandler iccFh = phone.getIccFileHandler();
        recordsRequested = true;

        phone.mCM.getIMSI(obtainMessage(EVENT_GET_IMSI_DONE));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_ICCID, obtainMessage(EVENT_GET_ICCID_DONE));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_AD, obtainMessage(EVENT_GET_AD_DONE));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_PL, obtainMessage(EVENT_GET_PL_DONE));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_CSIM_LI, obtainMessage(EVENT_GET_CSIM_LI_DONE));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_CSIM_SPN, obtainMessage(EVENT_GET_CSIM_SPN_DONE));
        recordsToLoad++;
    }

    private void onGetCSimSpnDone(AsyncResult ar) {
        byte[] data = (byte[]) ar.result;
        if (DBG) log("CSIM_SPN=" +
                     IccUtils.bytesToHexString(data));

        // C.S0065 for EF_SPN decoding
        csimSpnDisplayCondition = ((0x02 & data[0]) > 0)?true:false;

        int encoding = data[1];
        int language = data[2];
        byte[] spnData = new byte[32];
        System.arraycopy(data, 3, spnData, 0, (data.length < 32)?data.length:32);

        int numBytes;
        for (numBytes = 0; numBytes < spnData.length; numBytes++) {
            if ((spnData[numBytes] & 0xFF) == 0xFF) break;
        }

        if (numBytes == 0) {
            spn = "";
            return;
        }
        try {
            switch (encoding) {
            case UserData.ENCODING_OCTET:
            case UserData.ENCODING_LATIN:
                spn = new String(spnData, 0, numBytes, "ISO-8859-1");
                break;
            case UserData.ENCODING_IA5:
            case UserData.ENCODING_GSM_7BIT_ALPHABET:
            case UserData.ENCODING_7BIT_ASCII:
                spn = GsmAlphabet.gsm7BitPackedToString(spnData, 0, (numBytes*8)/7);
                break;
            case UserData.ENCODING_UNICODE_16:
                spn =  new String(spnData, 0, numBytes, "utf-16");
                break;
            default:
                log("SPN encoding not supported");
            }
        } catch(Exception e) {
            log("spn decode error: " + e);
        }
        if (DBG) log("spn=" + spn);
        if (DBG) log("spnCondition=" + csimSpnDisplayCondition);
        phone.setSystemProperty(PROPERTY_ICC_OPERATOR_ALPHA, spn);
    }

    public byte[] getPreferredLanguage() {
        return mEFpl;
    }

    public byte[] getLanguageIndication() {
        return mEFli;
    }
}
