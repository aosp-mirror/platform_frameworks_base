/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.nearby

import com.android.systemui.dagger.SysUISingleton

/**
 * A manager that returns information about devices that are nearby and can receive media transfers.
 */
@SysUISingleton
class MediaNearbyDevicesManager {

    /** Returns a list containing the current nearby devices. */
    fun getCurrentNearbyDevices(): List<NearbyDevice> {
        // TODO(b/216313420): Implement this function.
        return emptyList()
    }

    /**
     * Registers [callback] to be notified each time a device's range changes or when a new device
     * comes within range.
     */
    fun registerNearbyDevicesCallback(
        callback: (device: NearbyDevice) -> Unit
    ) {
        // TODO(b/216313420): Implement this function.
    }

    /**
     * Un-registers [callback]. See [registerNearbyDevicesCallback].
     */
    fun unregisterNearbyDevicesCallback(
        callback: (device: NearbyDevice) -> Unit
    ) {
        // TODO(b/216313420): Implement this function.
    }
}
