/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.smspush.unitTests;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.android.internal.util.HexDump;

/**
 * A sample wap push receiver application for existing framework
 * This class is listening for "application/vnd.oma.drm.rights+xml" message
 */
public class DrmReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "WAP PUSH";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(LOG_TAG, "DrmReceiver received.");

        byte[] body;
        byte[] header;

        body = intent.getByteArrayExtra("data");
        header = intent.getByteArrayExtra("header");

        Log.d(LOG_TAG, "header:");
        Log.d(LOG_TAG, HexDump.dumpHexString(header));
        Log.d(LOG_TAG, "body:");
        Log.d(LOG_TAG, HexDump.dumpHexString(body));

        DataVerify.SetLastReceivedPdu(body);
    }

}


