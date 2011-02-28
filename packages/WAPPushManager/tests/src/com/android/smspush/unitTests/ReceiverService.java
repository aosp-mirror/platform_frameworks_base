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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.android.internal.util.HexDump;

/**
 * Service type receiver application
 */
public class ReceiverService extends Service {
    private static final String LOG_TAG = "WAP PUSH";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(LOG_TAG, "Receiver service created");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(LOG_TAG, "Receiver service started");

        byte[] body;
        byte[] header;
        body = intent.getByteArrayExtra("data");
        header = intent.getByteArrayExtra("header");

        Log.d(LOG_TAG, "header:");
        Log.d(LOG_TAG, HexDump.dumpHexString(header));
        Log.d(LOG_TAG, "body:");
        Log.d(LOG_TAG, HexDump.dumpHexString(body));

        DataVerify.SetLastReceivedPdu(body);
        return START_STICKY;
    }
}


