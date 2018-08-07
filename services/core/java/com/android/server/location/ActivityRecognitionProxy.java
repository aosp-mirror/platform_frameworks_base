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
import android.hardware.location.IActivityRecognitionHardwareClient;
import android.hardware.location.IActivityRecognitionHardwareWatcher;
import android.os.Handler;
import android.os.IBinder;
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
    private final boolean mIsSupported;
    private final ActivityRecognitionHardware mInstance;

    private ActivityRecognitionProxy(
            Context context,
            Handler handler,
            boolean activityRecognitionHardwareIsSupported,
            ActivityRecognitionHardware activityRecognitionHardware,
            int overlaySwitchResId,
            int defaultServicePackageNameResId,
            int initialPackageNameResId) {
        mIsSupported = activityRecognitionHardwareIsSupported;
        mInstance = activityRecognitionHardware;

        Runnable newServiceWork = new Runnable() {
            @Override
            public void run() {
                bindProvider();
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
            boolean activityRecognitionHardwareIsSupported,
            ActivityRecognitionHardware activityRecognitionHardware,
            int overlaySwitchResId,
            int defaultServicePackageNameResId,
            int initialPackageNameResId) {
        ActivityRecognitionProxy activityRecognitionProxy = new ActivityRecognitionProxy(
                context,
                handler,
                activityRecognitionHardwareIsSupported,
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
    private void bindProvider() {
        if (!mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() {
            @Override
            public void run(IBinder binder) {
                String descriptor;
                try {
                    descriptor = binder.getInterfaceDescriptor();
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to get interface descriptor.", e);
                    return;
                }

                if (IActivityRecognitionHardwareWatcher.class.getCanonicalName()
                        .equals(descriptor)) {
                    IActivityRecognitionHardwareWatcher watcher =
                            IActivityRecognitionHardwareWatcher.Stub.asInterface(binder);
                    if (watcher == null) {
                        Log.e(TAG, "No watcher found on connection.");
                        return;
                    }
                    if (mInstance == null) {
                        // to keep backwards compatibility do not update the watcher when there is
                        // no instance available, or it will cause an NPE
                        Log.d(TAG, "AR HW instance not available, binding will be a no-op.");
                        return;
                    }
                    try {
                        watcher.onInstanceChanged(mInstance);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error delivering hardware interface to watcher.", e);
                    }
                } else if (IActivityRecognitionHardwareClient.class.getCanonicalName()
                            .equals(descriptor)) {
                    IActivityRecognitionHardwareClient client =
                            IActivityRecognitionHardwareClient.Stub.asInterface(binder);
                    if (client == null) {
                        Log.e(TAG, "No client found on connection.");
                        return;
                    }
                    try {
                        client.onAvailabilityChanged(mIsSupported, mInstance);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error delivering hardware interface to client.", e);
                    }
                } else {
                    Log.e(TAG, "Invalid descriptor found on connection: " + descriptor);
                }
            }
        })) {
            Log.e(TAG, "Null binder found on connection.");
        }
    }
}
