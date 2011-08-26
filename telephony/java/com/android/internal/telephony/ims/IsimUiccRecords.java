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

package com.android.internal.telephony.ims;

import android.os.AsyncResult;
import android.os.Handler;
import android.util.Log;

import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.IccRecords;
import com.android.internal.telephony.gsm.SimTlv;

import java.nio.charset.Charset;
import java.util.ArrayList;

import static com.android.internal.telephony.IccConstants.EF_DOMAIN;
import static com.android.internal.telephony.IccConstants.EF_IMPI;
import static com.android.internal.telephony.IccConstants.EF_IMPU;

/**
 * {@hide}
 */
public final class IsimUiccRecords implements IsimRecords {
    protected static final String LOG_TAG = "GSM";

    private static final boolean DBG = true;
    private static final boolean DUMP_RECORDS = false;   // Note: PII is logged when this is true

    // ISIM EF records (see 3GPP TS 31.103)
    private String mIsimImpi;               // IMS private user identity
    private String mIsimDomain;             // IMS home network domain name
    private String[] mIsimImpu;             // IMS public user identity(s)

    private static final int TAG_ISIM_VALUE = 0x80;     // From 3GPP TS 31.103

    private class EfIsimImpiLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_IMPI";
        }
        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            mIsimImpi = isimTlvToString(data);
            if (DUMP_RECORDS) log("EF_IMPI=" + mIsimImpi);
        }
    }

    private class EfIsimImpuLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_IMPU";
        }
        public void onRecordLoaded(AsyncResult ar) {
            ArrayList<byte[]> impuList = (ArrayList<byte[]>) ar.result;
            if (DBG) log("EF_IMPU record count: " + impuList.size());
            mIsimImpu = new String[impuList.size()];
            int i = 0;
            for (byte[] identity : impuList) {
                String impu = isimTlvToString(identity);
                if (DUMP_RECORDS) log("EF_IMPU[" + i + "]=" + impu);
                mIsimImpu[i++] = impu;
            }
        }
    }

    private class EfIsimDomainLoaded implements IccRecords.IccRecordLoaded {
        public String getEfName() {
            return "EF_ISIM_DOMAIN";
        }
        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            mIsimDomain = isimTlvToString(data);
            if (DUMP_RECORDS) log("EF_DOMAIN=" + mIsimDomain);
        }
    }

    /**
     * Request the ISIM records to load.
     * @param iccFh the IccFileHandler to load the records from
     * @param h the Handler to which the response message will be sent
     * @return the number of EF record requests that were added
     */
    public int fetchIsimRecords(IccFileHandler iccFh, Handler h) {
        iccFh.loadEFTransparent(EF_IMPI, h.obtainMessage(
                IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimImpiLoaded()));
        iccFh.loadEFLinearFixedAll(EF_IMPU, h.obtainMessage(
                IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimImpuLoaded()));
        iccFh.loadEFTransparent(EF_DOMAIN, h.obtainMessage(
                IccRecords.EVENT_GET_ICC_RECORD_DONE, new EfIsimDomainLoaded()));
        return 3;   // number of EF record load requests
    }

    /**
     * ISIM records for IMS are stored inside a Tag-Length-Value record as a UTF-8 string
     * with tag value 0x80.
     * @param record the byte array containing the IMS data string
     * @return the decoded String value, or null if the record can't be decoded
     */
    private static String isimTlvToString(byte[] record) {
        SimTlv tlv = new SimTlv(record, 0, record.length);
        do {
            if (tlv.getTag() == TAG_ISIM_VALUE) {
                return new String(tlv.getData(), Charset.forName("UTF-8"));
            }
        } while (tlv.nextObject());

        Log.e(LOG_TAG, "[ISIM] can't find TLV tag in ISIM record, returning null");
        return null;
    }

    void log(String s) {
        if (DBG) Log.d(LOG_TAG, "[ISIM] " + s);
    }

    void loge(String s) {
        if (DBG) Log.e(LOG_TAG, "[ISIM] " + s);
    }

    /**
     * Return the IMS private user identity (IMPI).
     * Returns null if the IMPI hasn't been loaded or isn't present on the ISIM.
     * @return the IMS private user identity string, or null if not available
     */
    public String getIsimImpi() {
        return mIsimImpi;
    }

    /**
     * Return the IMS home network domain name.
     * Returns null if the IMS domain hasn't been loaded or isn't present on the ISIM.
     * @return the IMS home network domain name, or null if not available
     */
    public String getIsimDomain() {
        return mIsimDomain;
    }

    /**
     * Return an array of IMS public user identities (IMPU).
     * Returns null if the IMPU hasn't been loaded or isn't present on the ISIM.
     * @return an array of IMS public user identity strings, or null if not available
     */
    public String[] getIsimImpu() {
        return (mIsimImpu != null) ? mIsimImpu.clone() : null;
    }
}
