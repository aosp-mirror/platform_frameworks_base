/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.stubs.am;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.android.frameworks.perftests.am.util.Constants;

public class TestService extends Service {
    private static final String TAG = "TestService";
    private static final boolean VERBOSE = InitService.VERBOSE;

    private Binder mStub = new Binder();

    @Override
    public IBinder onBind(Intent intent) {
        if (VERBOSE) {
            Log.i(TAG, getPackageName() + " onBind()");
        }
        return mStub;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (VERBOSE) {
            Log.i(TAG, getPackageName() + " onStartCommand()");
        }
        return START_NOT_STICKY;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (VERBOSE) {
            Log.i(TAG, getPackageName() + " onUnbind()");
        }
        Messenger messenger = intent.getParcelableExtra(Constants.EXTRA_RECEIVER_CALLBACK);
        Message msg = Message.obtain();
        msg.what = Constants.MSG_UNBIND_DONE;
        Bundle b = new Bundle();
        b.putString(Constants.EXTRA_SOURCE_PACKAGE, getPackageName());
        msg.obj = b;
        try {
            messenger.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in sending result back", e);
        }
        return false;
    }
}
