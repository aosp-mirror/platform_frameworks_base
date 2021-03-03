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
 * limitations under the License.
 */

package com.android.server.location;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.location.ActivityRecognitionHardware;
import android.hardware.location.IActivityRecognitionHardwareClient;
import android.hardware.location.IActivityRecognitionHardwareWatcher;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.servicewatcher.ServiceWatcher;
import com.android.server.servicewatcher.ServiceWatcher.BoundService;

/**
 * Proxy class to bind GmsCore to the ActivityRecognitionHardware.
 *
 * @hide
 */
public class HardwareActivityRecognitionProxy {

    private static final String TAG = "ARProxy";
    private static final String SERVICE_ACTION =
            "com.android.location.service.ActivityRecognitionProvider";

    /**
     * Creates and registers this proxy. If no suitable service is available for the proxy, returns
     * null.
     */
    @Nullable
    public static HardwareActivityRecognitionProxy createAndRegister(Context context) {
        HardwareActivityRecognitionProxy arProxy = new HardwareActivityRecognitionProxy(context);
        if (arProxy.register()) {
            return arProxy;
        } else {
            return null;
        }
    }

    private final boolean mIsSupported;
    private final ActivityRecognitionHardware mInstance;

    private final ServiceWatcher mServiceWatcher;

    private HardwareActivityRecognitionProxy(Context context) {
        mIsSupported = ActivityRecognitionHardware.isSupported();
        if (mIsSupported) {
            mInstance = ActivityRecognitionHardware.getInstance(context);
        } else {
            mInstance = null;
        }

        mServiceWatcher = new ServiceWatcher(context,
                SERVICE_ACTION,
                this::onBind,
                null,
                com.android.internal.R.bool.config_enableActivityRecognitionHardwareOverlay,
                com.android.internal.R.string.config_activityRecognitionHardwarePackageName);
    }

    private boolean register() {
        boolean resolves = mServiceWatcher.checkServiceResolves();
        if (resolves) {
            mServiceWatcher.register();
        }
        return resolves;
    }

    private void onBind(IBinder binder, BoundService service) throws RemoteException {
        String descriptor = binder.getInterfaceDescriptor();

        if (IActivityRecognitionHardwareWatcher.class.getCanonicalName().equals(descriptor)) {
            IActivityRecognitionHardwareWatcher watcher =
                    IActivityRecognitionHardwareWatcher.Stub.asInterface(binder);
            if (mInstance != null) {
                watcher.onInstanceChanged(mInstance);
            }
        } else if (IActivityRecognitionHardwareClient.class.getCanonicalName().equals(descriptor)) {
            IActivityRecognitionHardwareClient client =
                    IActivityRecognitionHardwareClient.Stub.asInterface(binder);
            client.onAvailabilityChanged(mIsSupported, mInstance);
        } else {
            Log.e(TAG, "Unknown descriptor: " + descriptor);
        }
    }
}
