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

package com.android.server.location.injector;

import android.os.Binder;

import com.android.internal.util.Preconditions;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;

import java.util.Objects;

/**
 * Provides accessors and listeners for device stationary state.
 */
public class SystemDeviceStationaryHelper extends DeviceStationaryHelper {

    private DeviceIdleInternal mDeviceIdle;

    public SystemDeviceStationaryHelper() {}

    public void onSystemReady() {
        mDeviceIdle = Objects.requireNonNull(LocalServices.getService(DeviceIdleInternal.class));
    }

    @Override
    public void addListener(DeviceIdleInternal.StationaryListener listener) {
        Preconditions.checkState(mDeviceIdle != null);

        long identity = Binder.clearCallingIdentity();
        try {
            mDeviceIdle.registerStationaryListener(listener);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void removeListener(DeviceIdleInternal.StationaryListener listener) {
        Preconditions.checkState(mDeviceIdle != null);

        long identity = Binder.clearCallingIdentity();
        try {
            mDeviceIdle.unregisterStationaryListener(listener);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
