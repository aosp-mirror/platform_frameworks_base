/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.companion.virtual;

import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.IVirtualDeviceActivityListener;
import android.companion.virtual.VirtualDeviceParams;

/**
 * Interface for communication between VirtualDeviceManager and VirtualDeviceManagerService.
 *
 * @hide
 */
interface IVirtualDeviceManager {

    /**
     * Creates a virtual device that can be used to create virtual displays and stream contents.
     *
     * @param token The binder token created by the caller of this API.
     * @param packageName The package name of the caller. Implementation of this method must verify
     *   that this belongs to the calling UID.
     * @param associationId The association ID as returned by {@link AssociationInfo#getId()} from
     *   CDM. Virtual devices must have a corresponding association with CDM in order to be created.
     * @param params The parameters for creating this virtual device. See {@link
     *   VirtualDeviceManager.VirtualDeviceParams}.
     * @param activityListener The listener to listen for activity changes in a virtual device.
     */
    IVirtualDevice createVirtualDevice(
            in IBinder token, String packageName, int associationId,
            in VirtualDeviceParams params, in IVirtualDeviceActivityListener activityListener);
}
