/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.app.admin;

import com.android.server.LocalServices;

/**
 * Stores a copy of the set of device state maintained by {@link DevicePolicyManager} which
 * is not directly related to admin policies. This lives in its own class so that the state
 * can be accessed from any place without risking dead locks.
 *
 * @hide
 */
public abstract class DeviceStateCache {
    protected DeviceStateCache() {
    }

    /**
     * @return the instance.
     */
    public static DeviceStateCache getInstance() {
        final DevicePolicyManagerInternal dpmi =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        return (dpmi != null) ? dpmi.getDeviceStateCache() : EmptyDeviceStateCache.INSTANCE;
    }

    /**
     * See {@link DevicePolicyManager#isDeviceProvisioned}
     */
    public abstract boolean isDeviceProvisioned();

    /**
     * Empty implementation.
     */
    private static class EmptyDeviceStateCache extends DeviceStateCache {
        private static final EmptyDeviceStateCache INSTANCE = new EmptyDeviceStateCache();

        @Override
        public boolean isDeviceProvisioned() {
            return false;
        }
    }
}
