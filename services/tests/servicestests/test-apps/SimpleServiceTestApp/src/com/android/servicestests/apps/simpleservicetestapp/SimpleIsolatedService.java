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

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

public class SimpleIsolatedService extends Service {
    private static final String TAG = "SimpleIsolatedService";
    private static final String EXTRA_CALLBACK = "callback";

    private final IRemoteCallback.Stub mBinder = new IRemoteCallback.Stub() {
        @Override
        public void sendResult(Bundle bundle) {
            final IBinder callback = bundle.getBinder(EXTRA_CALLBACK);
            final Parcel data = Parcel.obtain();
            final Parcel reply = Parcel.obtain();
            try {
                data.writeInt(Process.myPid());
                callback.transact(Binder.FIRST_CALL_TRANSACTION, data, reply, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception", e);
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind");
        return mBinder;
    }
}
