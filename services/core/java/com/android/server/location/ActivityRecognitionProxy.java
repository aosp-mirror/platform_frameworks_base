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

package com.android.server.location;

import android.content.Context;
import android.hardware.location.ActivityRecognitionHardware;
import android.hardware.location.IActivityRecognitionHardwareClient;
import android.hardware.location.IActivityRecognitionHardwareWatcher;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.FgThread;
import com.android.server.ServiceWatcher;

/**
 * Proxy class to bind GmsCore to the ActivityRecognitionHardware.
 *
 * @hide
 */
public class ActivityRecognitionProxy {

    private static final String TAG = "ActivityRecognitionProxy";

    /**
     * Creates an instance of the proxy and binds it to the appropriate FusedProvider.
     *
     * @return An instance of the proxy if it could be bound, null otherwise.
     */
    public static ActivityRecognitionProxy createAndBind(
            Context context,
            boolean activityRecognitionHardwareIsSupported,
            ActivityRecognitionHardware activityRecognitionHardware,
            int overlaySwitchResId,
            int defaultServicePackageNameResId,
            int initialPackageNameResId) {
        ActivityRecognitionProxy activityRecognitionProxy = new ActivityRecognitionProxy(
                context,
                activityRecognitionHardwareIsSupported,
                activityRecognitionHardware,
                overlaySwitchResId,
                defaultServicePackageNameResId,
                initialPackageNameResId);

        if (activityRecognitionProxy.mServiceWatcher.start()) {
            return activityRecognitionProxy;
        } else {
            return null;
        }
    }

    private final ServiceWatcher mServiceWatcher;
    private final boolean mIsSupported;
    private final ActivityRecognitionHardware mInstance;

    private ActivityRecognitionProxy(
            Context context,
            boolean activityRecognitionHardwareIsSupported,
            ActivityRecognitionHardware activityRecognitionHardware,
            int overlaySwitchResId,
            int defaultServicePackageNameResId,
            int initialPackageNameResId) {
        mIsSupported = activityRecognitionHardwareIsSupported;
        mInstance = activityRecognitionHardware;

        mServiceWatcher = new ServiceWatcher(
                context,
                TAG,
                "com.android.location.service.ActivityRecognitionProvider",
                overlaySwitchResId,
                defaultServicePackageNameResId,
                initialPackageNameResId,
                FgThread.getHandler()) {
            @Override
            protected void onBind() {
                runOnBinder(ActivityRecognitionProxy.this::initializeService);
            }
        };
    }

    private void initializeService(IBinder binder) {
        try {
            String descriptor = binder.getInterfaceDescriptor();

            if (IActivityRecognitionHardwareWatcher.class.getCanonicalName().equals(
                    descriptor)) {
                IActivityRecognitionHardwareWatcher watcher =
                        IActivityRecognitionHardwareWatcher.Stub.asInterface(binder);
                if (mInstance != null) {
                    watcher.onInstanceChanged(mInstance);
                }
            } else if (IActivityRecognitionHardwareClient.class.getCanonicalName()
                    .equals(descriptor)) {
                IActivityRecognitionHardwareClient client =
                        IActivityRecognitionHardwareClient.Stub.asInterface(binder);
                client.onAvailabilityChanged(mIsSupported, mInstance);
            } else {
                Log.e(TAG, "Invalid descriptor found on connection: " + descriptor);
            }
        } catch (RemoteException e) {
            Log.w(TAG, e);
        }
    }
}
