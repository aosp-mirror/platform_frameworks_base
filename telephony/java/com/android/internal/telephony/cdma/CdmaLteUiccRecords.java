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

import android.os.AsyncResult;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.AdnRecordLoader;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.IccCardApplication.AppType;
import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.SmsMessageBase;
import com.android.internal.telephony.cdma.sms.UserData;
import com.android.internal.telephony.gsm.SIMRecords;
import com.android.internal.telephony.ims.IsimRecords;
import com.android.internal.telephony.ims.IsimUiccRecords;

import java.util.ArrayList;
import java.util.Locale;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_TEST_CSIM;

/**
 * {@hide}
 */
public final class CdmaLteUiccRecords extends SIMRecords {
    // From CSIM application
    private byte[] mEFpl = null;
    private byte[] mEFli = null;
    boolean mCsimSpnDisplayCondition = false;
    private String mMdn;
    private String mMin;
    private String mPrlVersion;
    private String mHomeSystemId;
    private String mHomeNetworkId;

    private final IsimUiccRecords mIsimUiccRecords = new IsimUiccRecords();

    public CdmaLteUiccRecords(PhoneBase p) {
        super(p);
    }

    // Refer to ETSI TS 102.221
    private class EfPlLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_PL";
        }

        public void onRecordLoaded(AsyncResult ar) {
            mEFpl = (byte[]) ar.result;
            if (DBG) log("EF_PL=" + IccUtils.bytesToHexString(mEFpl));
        }
    }

    // Refer to C.S0065 5.2.26
    private class EfCsimLiLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_CSIM_LI";
        }

        public void onRecordLoaded(AsyncResult ar) {
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
        }
    }

    // Refer to C.S0065 5.2.32
    private class EfCsimSpnLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_CSIM_SPN";
        }

        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            if (DBG) log("CSIM_SPN=" +
                         IccUtils.bytesToHexString(data));

            // C.S0065 for EF_SPN decoding
            mCsimSpnDisplayCondition = ((0x01 & data[0]) != 0);

            int encoding = data[1];
            int language = data[2];
            byte[] spnData = new byte[32];
            System.arraycopy(data, 3, spnData, 0, (data.length < 32) ? data.length : 32);

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
            if (DBG) log("spnCondition=" + mCsimSpnDisplayCondition);
            phone.setSystemProperty(PROPERTY_ICC_OPERATOR_ALPHA, spn);
        }
    }

    private class EfCsimMdnLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_CSIM_MDN";
        }

        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            if (DBG) log("CSIM_MDN=" + IccUtils.bytesToHexString(data));
            int mdnDigitsNum = 0x0F & data[0];
            mMdn = IccUtils.cdmaBcdToString(data, 1, mdnDigitsNum);
            if (DBG) log("CSIM MDN=" + mMdn);
        }
    }

    private class EfCsimImsimLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_CSIM_IMSIM";
        }

        public void onRecordLoaded(AsyncResult ar) {
            byte[] data = (byte[]) ar.result;
            if (DBG) log("CSIM_IMSIM=" + IccUtils.bytesToHexString(data));
            // C.S0065 section 5.2.2 for IMSI_M encoding
            // C.S0005 section 2.3.1 for MIN encoding in IMSI_M.
            boolean provisioned = ((data[7] & 0x80) == 0x80);

            if (provisioned) {
                int first3digits = ((0x03 & data[2]) << 8) + (0xFF & data[1]);
                int second3digits = (((0xFF & data[5]) << 8) | (0xFF & data[4])) >> 6;
                int digit7 = 0x0F & (data[4] >> 2);
                if (digit7 > 0x09) digit7 = 0;
                int last3digits = ((0x03 & data[4]) << 8) | (0xFF & data[3]);
                first3digits = adjstMinDigits(first3digits);
                second3digits = adjstMinDigits(second3digits);
                last3digits = adjstMinDigits(last3digits);

                StringBuilder builder = new StringBuilder();
                builder.append(String.format(Locale.US, "%03d", first3digits));
                builder.append(String.format(Locale.US, "%03d", second3digits));
                builder.append(String.format(Locale.US, "%d", digit7));
                builder.append(String.format(Locale.US, "%03d", last3digits));
                mMin = builder.toString();
                if (DBG) log("min present=" + mMin);
            } else {
                if (DBG) log("min not present");
            }
        }
    }

    private class EfCsimCdmaHomeLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_CSIM_CDMAHOME";
        }

        public void onRecordLoaded(AsyncResult ar) {
            // Per C.S0065 section 5.2.8
            ArrayList<byte[]> dataList = (ArrayList<byte[]>) ar.result;
            if (DBG) log("CSIM_CDMAHOME data size=" + dataList.size());
            if (dataList.isEmpty()) {
                return;
            }
            StringBuilder sidBuf = new StringBuilder();
            StringBuilder nidBuf = new StringBuilder();

            for (byte[] data : dataList) {
                if (data.length == 5) {
                    int sid = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
                    int nid = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);
                    sidBuf.append(sid).append(',');
                    nidBuf.append(nid).append(',');
                }
            }
            // remove trailing ","
            sidBuf.setLength(sidBuf.length()-1);
            nidBuf.setLength(nidBuf.length()-1);

            mHomeSystemId = sidBuf.toString();
            mHomeNetworkId = nidBuf.toString();
        }
    }

    private class EfCsimEprlLoaded implements IccRecordLoaded {
        public String getEfName() {
            return "EF_CSIM_EPRL";
        }
        public void onRecordLoaded(AsyncResult ar) {
            onGetCSimEprlDone(ar);
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
    protected void onAllRecordsLoaded() {
        setLocaleFromCsim();
        super.onAllRecordsLoaded();     // broadcasts ICC state change to "LOADED"
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

        iccFh.loadEFTransparent(EF_PL,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfPlLoaded()));
        recordsToLoad++;

        new AdnRecordLoader(phone).loadFromEF(EF_MSISDN, EF_EXT1, 1,
                obtainMessage(EVENT_GET_MSISDN_DONE));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_SST, obtainMessage(EVENT_GET_SST_DONE));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_CSIM_LI,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimLiLoaded()));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_CSIM_SPN,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimSpnLoaded()));
        recordsToLoad++;

        iccFh.loadEFLinearFixed(EF_CSIM_MDN, 1,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimMdnLoaded()));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_CSIM_IMSIM,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimImsimLoaded()));
        recordsToLoad++;

        iccFh.loadEFLinearFixedAll(EF_CSIM_CDMAHOME,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimCdmaHomeLoaded()));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_CSIM_EPRL,
                obtainMessage(EVENT_GET_ICC_RECORD_DONE, new EfCsimEprlLoaded()));
        recordsToLoad++;

        // load ISIM records
        recordsToLoad += mIsimUiccRecords.fetchIsimRecords(iccFh, this);
    }

    private int adjstMinDigits (int digits) {
        // Per C.S0005 section 2.3.1.
        digits += 111;
        digits = (digits % 10 == 0)?(digits - 10):digits;
        digits = ((digits / 10) % 10 == 0)?(digits - 100):digits;
        digits = ((digits / 100) % 10 == 0)?(digits - 1000):digits;
        return digits;
    }

    private void onGetCSimEprlDone(AsyncResult ar) {
        // C.S0065 section 5.2.57 for EFeprl encoding
        // C.S0016 section 3.5.5 for PRL format.
        byte[] data = (byte[]) ar.result;
        if (DBG) log("CSIM_EPRL=" + IccUtils.bytesToHexString(data));

        // Only need the first 4 bytes of record
        if (data.length > 3) {
            int prlId = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            mPrlVersion = Integer.toString(prlId);
        }
        if (DBG) log("CSIM PRL version=" + mPrlVersion);
    }

    private void setLocaleFromCsim() {
        String prefLang = null;
        // check EFli then EFpl
        prefLang = findBestLanguage(mEFli);

        if (prefLang == null) {
            prefLang = findBestLanguage(mEFpl);
        }

        if (prefLang != null) {
            // check country code from SIM
            String imsi = getIMSI();
            String country = null;
            if (imsi != null) {
                country = MccTable.countryCodeForMcc(
                                    Integer.parseInt(imsi.substring(0,3)));
            }
            log("Setting locale to " + prefLang + "_" + country);
            phone.setSystemLocale(prefLang, country, false);
        } else {
            log ("No suitable CSIM selected locale");
        }
    }

    private String findBestLanguage(byte[] languages) {
        String bestMatch = null;
        String[] locales = phone.getContext().getAssets().getLocales();

        if ((languages == null) || (locales == null)) return null;

        // Each 2-bytes consists of one language
        for (int i = 0; (i + 1) < languages.length; i += 2) {
            try {
                String lang = new String(languages, i, 2, "ISO-8859-1");
                for (int j = 0; j < locales.length; j++) {
                    if (locales[j] != null && locales[j].length() >= 2 &&
                        locales[j].substring(0, 2).equals(lang)) {
                        return lang;
                    }
                }
                if (bestMatch != null) break;
            } catch(java.io.UnsupportedEncodingException e) {
                log ("Failed to parse SIM language records");
            }
        }
        // no match found. return null
        return null;
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[CSIM] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[CSIM] " + s);
    }

    public String getMdn() {
        return mMdn;
    }

    public String getMin() {
        return mMin;
    }

    public String getSid() {
        return mHomeSystemId;
    }

    public String getNid() {
        return mHomeNetworkId;
    }

    public String getPrlVersion() {
        return mPrlVersion;
    }

    public boolean getCsimSpnDisplayCondition() {
        return mCsimSpnDisplayCondition;
    }

    @Override
    public IsimRecords getIsimRecords() {
        return mIsimUiccRecords;
    }

    @Override
    public boolean isProvisioned() {
        // If UICC card has CSIM app, look for MDN and MIN field
        // to determine if the SIM is provisioned.  Otherwise,
        // consider the SIM is provisioned. (for case of ordinal
        // USIM only UICC.)
        // If PROPERTY_TEST_CSIM is defined, bypess provision check
        // and consider the SIM is provisioned.
        if (SystemProperties.getBoolean(PROPERTY_TEST_CSIM, false)) {
            return true;
        }

        if (phone.mIccCard.isApplicationOnIcc(AppType.APPTYPE_CSIM) &&
            ((mMdn == null) || (mMin == null))) {
            return false;
        }
        return true;
    }

    /**
     * Dispatch 3GPP format message. For CDMA/LTE phones,
     * send the message to the secondary 3GPP format SMS dispatcher.
     */
    @Override
    protected int dispatchGsmMessage(SmsMessageBase message) {
        return ((CDMALTEPhone) phone).m3gppSMS.dispatchMessage(message);
    }
}
