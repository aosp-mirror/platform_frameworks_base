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

package com.android.dumprendertree2;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

/**
 * A service that handles managing the results of tests, informing of crashes, generating
 * summaries, etc.
 */
public class ManagerService extends Service {

    private static final String LOG_TAG = "ManagerService";

    static final int MSG_PROCESS_ACTUAL_RESULTS = 0;

    private Handler mIncomingHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PROCESS_ACTUAL_RESULTS:
                    Log.d(LOG_TAG + ".mIncomingHandler", msg.getData().getString("relativePath"));
                    break;
            }
        }
    };

    private Messenger mMessenger = new Messenger(mIncomingHandler);

    @Override
    public void onCreate() {
        super.onCreate();
        /** TODO:  */
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }
}