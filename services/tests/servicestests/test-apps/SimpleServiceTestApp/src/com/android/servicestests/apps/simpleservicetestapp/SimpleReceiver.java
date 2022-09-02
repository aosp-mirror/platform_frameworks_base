/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.servicestests.apps.simpleservicetestapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.Log;

public class SimpleReceiver extends BroadcastReceiver {
    private static final String TAG = SimpleReceiver.class.getSimpleName();
    private static final String EXTRA_CALLBACK = "callback";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "onReceive " + intent);
        final Bundle extra = intent.getExtras();
        if (extra != null) {
            final IBinder binder = extra.getBinder(EXTRA_CALLBACK);
            if (binder != null) {
                IRemoteCallback callback = IRemoteCallback.Stub.asInterface(binder);
                try {
                    callback.sendResult(null);
                } catch (RemoteException e) {
                }
            }
        }
    }
}
