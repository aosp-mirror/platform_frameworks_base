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

import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccConstants;
import com.android.internal.telephony.IccFileHandler;
import android.os.Message;

/**
 * {@hide}
 */
public final class CdmaLteUiccFileHandler extends IccFileHandler {
    static final String LOG_TAG = "CDMA";

    public CdmaLteUiccFileHandler(IccCard card, String aid, CommandsInterface ci) {
        super(card, aid, ci);
    }

    protected String getEFPath(int efid) {
        switch(efid) {
        case EF_CSIM_SPN:
        case EF_CSIM_LI:
        case EF_CSIM_MDN:
        case EF_CSIM_IMSIM:
        case EF_CSIM_CDMAHOME:
        case EF_CSIM_EPRL:
            return MF_SIM + DF_CDMA;
        case EF_AD:
            return MF_SIM + DF_GSM;
        case EF_IMPI:
        case EF_DOMAIN:
        case EF_IMPU:
            return MF_SIM + DF_ADFISIM;
        }
        return getCommonIccEFPath(efid);
    }

    @Override
    public void loadEFTransparent(int fileid, Message onLoaded) {
        if (fileid == EF_CSIM_EPRL) {
            // Entire PRL could be huge. We are only interested in
            // the first 4 bytes of the record.
            mCi.iccIOForApp(COMMAND_READ_BINARY, fileid, getEFPath(fileid),
                            0, 0, 4, null, null, mAid,
                            obtainMessage(EVENT_READ_BINARY_DONE,
                                          fileid, 0, onLoaded));
        } else {
            super.loadEFTransparent(fileid, onLoaded);
        }
    }


    protected void logd(String msg) {
        Log.d(LOG_TAG, "[CdmaLteUiccFileHandler] " + msg);
    }

    protected void loge(String msg) {
        Log.e(LOG_TAG, "[CdmaLteUiccFileHandler] " + msg);
    }

}
