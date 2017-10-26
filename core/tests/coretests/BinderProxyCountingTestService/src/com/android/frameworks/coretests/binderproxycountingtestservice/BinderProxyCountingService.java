/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.frameworks.coretests.binderproxycountingtestservice;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallbackList;

import com.android.frameworks.coretests.aidl.IBinderProxyCountingService;
import com.android.frameworks.coretests.aidl.ITestRemoteCallback;

public class BinderProxyCountingService extends Service {
    private static final String TAG = BinderProxyCountingService.class.getSimpleName();

    private IBinderProxyCountingService.Stub mBinder = new IBinderProxyCountingService.Stub() {

        final RemoteCallbackList<ITestRemoteCallback> mTestCallbacks = new RemoteCallbackList<>();

        @Override
        public void registerCallback(ITestRemoteCallback callback) {
            synchronized (this) {
                mTestCallbacks.register(callback);
            }
        }

        @Override
        public void unregisterCallback(ITestRemoteCallback callback) {
            synchronized (this) {
                mTestCallbacks.unregister(callback);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}