/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.hardware.devicestate;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;

/**
 * Provides communication with the device state system service on behalf of applications.
 *
 * @see DeviceStateManager
 * @hide
 */
final class DeviceStateManagerGlobal {
    private static DeviceStateManagerGlobal sInstance;

    /**
     * Returns an instance of {@link DeviceStateManagerGlobal}. May return {@code null} if a
     * connection with the device state service couldn't be established.
     */
    @Nullable
    static DeviceStateManagerGlobal getInstance() {
        synchronized (DeviceStateManagerGlobal.class) {
            if (sInstance == null) {
                IBinder b = ServiceManager.getService(Context.DEVICE_STATE_SERVICE);
                if (b != null) {
                    sInstance = new DeviceStateManagerGlobal(IDeviceStateManager
                            .Stub.asInterface(b));
                }
            }
            return sInstance;
        }
    }

    @NonNull
    private final IDeviceStateManager mDeviceStateManager;

    private DeviceStateManagerGlobal(@NonNull IDeviceStateManager deviceStateManager) {
        mDeviceStateManager = deviceStateManager;
    }
}
