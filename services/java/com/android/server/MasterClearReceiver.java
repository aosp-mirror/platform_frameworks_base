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

package com.android.server;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.os.RemoteException;
import android.os.ICheckinService;
import android.os.ServiceManager;
import android.util.Log;

public class MasterClearReceiver extends BroadcastReceiver {

    private static final String TAG = "MasterClear";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals("android.intent.action.GTALK_DATA_MESSAGE_RECEIVED")) {
            if (!intent.getBooleanExtra("from_trusted_server", false)) {
                Log.w(TAG, "Ignoring master clear request -- not from trusted server.");
                return;
            }
        }
        Log.w(TAG, "!!! FACTORY RESETTING DEVICE !!!");
        ICheckinService service =
            ICheckinService.Stub.asInterface(
                ServiceManager.getService("checkin"));
        if (service != null) {
            try {
                // This RPC should never return.
                service.masterClear();
            } catch (RemoteException e) {
                Log.w("MasterClear",
                      "Unable to invoke ICheckinService.masterClear()");
            }
        }
    }
}
