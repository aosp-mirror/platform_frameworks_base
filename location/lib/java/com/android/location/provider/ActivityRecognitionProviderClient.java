/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.hardware.location.IActivityRecognitionHardware;
import android.hardware.location.IActivityRecognitionHardwareClient;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

/**
 * A client class for interaction with an Activity-Recognition provider.
 */
public abstract class ActivityRecognitionProviderClient {
    private static final String TAG = "ArProviderClient";

    protected ActivityRecognitionProviderClient() {}

    private IActivityRecognitionHardwareClient.Stub mClient =
            new IActivityRecognitionHardwareClient.Stub() {
                @Override
                public void onAvailabilityChanged(
                        boolean isSupported,
                        IActivityRecognitionHardware instance) {
                    int callingUid = Binder.getCallingUid();
                    if (callingUid != Process.SYSTEM_UID) {
                        Log.d(TAG, "Ignoring calls from non-system server. Uid: " + callingUid);
                        return;
                    }
                    ActivityRecognitionProvider provider;
                    try {
                        provider = isSupported ? new ActivityRecognitionProvider(instance) : null;
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error creating Hardware Activity-Recognition Provider.", e);
                        return;
                    }
                    onProviderChanged(isSupported, provider);
                }
            };

    /**
     * Gets the binder needed to interact with proxy provider in the platform.
     */
    @NonNull
    public IBinder getBinder() {
        return mClient;
    }

    /**
     * Called when a change in the availability of {@link ActivityRecognitionProvider} is detected.
     *
     * @param isSupported whether the platform supports the provider natively
     * @param instance the available provider's instance
     */
    public abstract void onProviderChanged(
            boolean isSupported,
            ActivityRecognitionProvider instance);
}
