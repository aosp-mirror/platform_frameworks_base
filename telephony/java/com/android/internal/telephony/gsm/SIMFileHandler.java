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

import android.os.*;
import android.os.AsyncResult;
import android.util.Log;

import com.android.internal.telephony.IccConstants;
import com.android.internal.telephony.IccException;
import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.IccFileTypeMismatch;
import com.android.internal.telephony.IccIoResult;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.PhoneProxy;

import java.util.ArrayList;

/**
 * {@hide}
 */
public final class SIMFileHandler extends IccFileHandler {
    static final String LOG_TAG = "GSM";

    //***** Instance Variables

    //***** Constructor

    SIMFileHandler(GSMPhone phone) {
        super(phone);
    }

    public void dispose() {
        super.dispose();
    }

    protected void finalize() {
        Log.d(LOG_TAG, "SIMFileHandler finalized");
    }

    //***** Overridden from IccFileHandler

    @Override
    public void handleMessage(Message msg) {
        super.handleMessage(msg);
    }

    protected void logd(String msg) {
        Log.d(LOG_TAG, "[SIMFileHandler] " + msg);
    }

    protected void loge(String msg) {
        Log.e(LOG_TAG, "[SIMFileHandler] " + msg);
    }
}
