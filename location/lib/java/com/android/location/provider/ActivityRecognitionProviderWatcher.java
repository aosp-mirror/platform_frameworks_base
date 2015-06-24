/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.location.provider;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.location.IActivityRecognitionHardware;
import android.hardware.location.IActivityRecognitionHardwareWatcher;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

/**
 * A watcher class for Activity-Recognition instances.
 *
 * @deprecated use {@link ActivityRecognitionProviderClient} instead.
 */
@Deprecated
public class ActivityRecognitionProviderWatcher {
    private static final String TAG = "ActivityRecognitionProviderWatcher";

    private static ActivityRecognitionProviderWatcher sWatcher;
    private static final Object sWatcherLock = new Object();

    private ActivityRecognitionProvider mActivityRecognitionProvider;

    private ActivityRecognitionProviderWatcher() {}

    public static ActivityRecognitionProviderWatcher getInstance() {
        synchronized (sWatcherLock) {
            if (sWatcher == null) {
                sWatcher = new ActivityRecognitionProviderWatcher();
            }
            return sWatcher;
        }
    }

    private IActivityRecognitionHardwareWatcher.Stub mWatcherStub =
            new IActivityRecognitionHardwareWatcher.Stub() {
        @Override
        public void onInstanceChanged(IActivityRecognitionHardware instance) {
            int callingUid = Binder.getCallingUid();
            if (callingUid != Process.SYSTEM_UID) {
                Log.d(TAG, "Ignoring calls from non-system server. Uid: " + callingUid);
                return;
            }

            try {
                mActivityRecognitionProvider = new ActivityRecognitionProvider(instance);
            } catch (RemoteException e) {
                Log.e(TAG, "Error creating Hardware Activity-Recognition", e);
            }
        }
    };

    /**
     * Gets the binder needed to interact with proxy provider in the platform.
     */
    @NonNull
    public IBinder getBinder() {
        return mWatcherStub;
    }

    /**
     * Gets an object that supports the functionality of {@link ActivityRecognitionProvider}.
     *
     * @return Non-null value if the functionality is supported by the platform, false otherwise.
     */
    @Nullable
    public ActivityRecognitionProvider getActivityRecognitionProvider() {
        return mActivityRecognitionProvider;
    }
}
