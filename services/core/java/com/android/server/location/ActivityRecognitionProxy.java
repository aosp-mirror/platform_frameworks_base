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

import com.android.server.ServiceWatcher;

import android.content.Context;
import android.hardware.location.ActivityRecognitionHardware;
import android.hardware.location.IActivityRecognitionHardwareWatcher;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

/**
 * Proxy class to bind GmsCore to the ActivityRecognitionHardware.
 *
 * @hide
 */
public class ActivityRecognitionProxy {
    private static final String TAG = "ActivityRecognitionProxy";

    private final ServiceWatcher mServiceWatcher;
    private final ActivityRecognitionHardware mActivityRecognitionHardware;

    private ActivityRecognitionProxy(
            Context context,
            Handler handler,
            ActivityRecognitionHardware activityRecognitionHardware,
            int overlaySwitchResId,
            int defaultServicePackageNameResId,
            int initialPackageNameResId) {
        mActivityRecognitionHardware = activityRecognitionHardware;

        Runnable newServiceWork = new Runnable() {
            @Override
            public void run() {
                bindProvider(mActivityRecognitionHardware);
            }
        };

        // prepare the connection to the provider
        mServiceWatcher = new ServiceWatcher(
                context,
                TAG,
                "com.android.location.service.ActivityRecognitionProvider",
                overlaySwitchResId,
                defaultServicePackageNameResId,
                initialPackageNameResId,
                newServiceWork,
                handler);
    }

    /**
     * Creates an instance of the proxy and binds it to the appropriate FusedProvider.
     *
     * @return An instance of the proxy if it could be bound, null otherwise.
     */
    public static ActivityRecognitionProxy createAndBind(
            Context context,
            Handler handler,
            ActivityRecognitionHardware activityRecognitionHardware,
            int overlaySwitchResId,
            int defaultServicePackageNameResId,
            int initialPackageNameResId) {
        ActivityRecognitionProxy activityRecognitionProxy = new ActivityRecognitionProxy(
                context,
                handler,
                activityRecognitionHardware,
                overlaySwitchResId,
                defaultServicePackageNameResId,
                initialPackageNameResId);

        // try to bind the provider
        if (!activityRecognitionProxy.mServiceWatcher.start()) {
            Log.e(TAG, "ServiceWatcher could not start.");
            return null;
        }

        return activityRecognitionProxy;
    }

    /**
     * Helper function to bind the FusedLocationHardware to the appropriate FusedProvider instance.
     */
    private void bindProvider(ActivityRecognitionHardware activityRecognitionHardware) {
        IActivityRecognitionHardwareWatcher watcher =
                IActivityRecognitionHardwareWatcher.Stub.asInterface(mServiceWatcher.getBinder());
        if (watcher == null) {
            Log.e(TAG, "No provider instance found on connection.");
            return;
        }

        try {
            watcher.onInstanceChanged(mActivityRecognitionHardware);
        } catch (RemoteException e) {
            Log.e(TAG, "Error delivering hardware interface.", e);
        }
    }
}
