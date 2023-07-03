/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.provider;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;

import java.util.Objects;

/**
 * Class that will hold an instance of {@link DeviceConfigServiceManager}
 * which is used by {@link DeviceConfig} to retrieve an instance of the service.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class DeviceConfigInitializer {
    private static DeviceConfigServiceManager sDeviceConfigServiceManager;

    private static final Object sLock = new Object();

    private DeviceConfigInitializer() {
        // fully static class
    }

    /**
     * Setter for {@link DeviceConfigServiceManager}. Should be called only once.
     *
     */
    public static void setDeviceConfigServiceManager(
            @NonNull DeviceConfigServiceManager serviceManager) {
        synchronized (sLock) {
            if (sDeviceConfigServiceManager != null) {
                throw new IllegalStateException("setDeviceConfigServiceManager called twice!");
            }
            Objects.requireNonNull(serviceManager, "serviceManager must not be null");

            sDeviceConfigServiceManager = serviceManager;
        }
    }

    /**
     * Getter for {@link DeviceConfigServiceManager}.
     *
     */
    @Nullable
    public static DeviceConfigServiceManager getDeviceConfigServiceManager() {
        synchronized (sLock) {
            return sDeviceConfigServiceManager;
        }
    }
}
