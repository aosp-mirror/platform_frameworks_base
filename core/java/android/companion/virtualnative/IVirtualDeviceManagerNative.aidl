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

package android.companion.virtualnative;

/**
 * Parallel implementation of certain VirtualDeviceManager APIs that need to be exposed to native
 * code.
 *
 * <p>These APIs are a parallel definition to the APIs in VirtualDeviceManager and/or
 * VirtualDeviceManagerInternal, so they can technically diverge. However, it's good practice to
 * keep these APIs in sync with each other.</p>
 *
 * <p>Even though the name implies otherwise, the implementation is actually in Java. The 'native'
 * suffix comes from the intended usage - native framework backends that need to communicate with
 * VDM for some reason.</p>
 *
 * <p>Because these APIs are exposed to native code that runs in the app process, they may be
 * accessed by apps directly, even though they're hidden. Care should be taken to avoid exposing
 * sensitive data or potential security holes.</p>
 *
 * @hide
 */
interface IVirtualDeviceManagerNative {
    /**
     * Counterpart to VirtualDeviceParams#DevicePolicy.
     */
    const int DEVICE_POLICY_DEFAULT = 0;
    const int DEVICE_POLICY_CUSTOM = 1;

    /**
     * Counterpart to VirtualDeviceParams#PolicyType.
     */
    const int POLICY_TYPE_SENSORS = 0;
    const int POLICY_TYPE_AUDIO = 1;
    const int POLICY_TYPE_RECENTS = 2;
    const int POLICY_TYPE_ACTIVITY = 3;
    const int POLICY_TYPE_CLIPBOARD = 4;
    const int POLICY_TYPE_CAMERA = 5;

    /**
     * Returns the IDs for all VirtualDevices where an app with the given is running.
     *
     * Note that this returns only VirtualDevice IDs: if the app is not running on any virtual
     * device, then an an empty array is returned. This does not include information about whether
     * the app is running on the default device or not.
     */
    int[] getDeviceIdsForUid(int uid);

    /**
     * Returns the device policy for the given virtual device and policy type.
     */
    int getDevicePolicy(int deviceId, int policyType);
}
