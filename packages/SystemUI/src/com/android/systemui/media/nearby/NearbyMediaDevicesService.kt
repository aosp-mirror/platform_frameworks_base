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

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.media.INearbyMediaDevicesProvider
import com.android.systemui.shared.media.INearbyMediaDevicesService
import com.android.systemui.shared.media.INearbyMediaDevicesUpdateCallback
import com.android.systemui.shared.media.NearbyDevice
import javax.inject.Inject

/**
 * A service that acts as a bridge between (1) external clients that have data on nearby devices
 * that are able to play media and (2) internal clients (like media Output Switcher) that need data
 * on these nearby devices.
 *
 * TODO(b/216313420): Add logging to this class.
 */
@SysUISingleton
class NearbyMediaDevicesService @Inject constructor() : Service() {

    private var provider: INearbyMediaDevicesProvider? = null

    private val binder: IBinder = object : INearbyMediaDevicesService.Stub() {
        override fun registerProvider(newProvider: INearbyMediaDevicesProvider) {
            provider = newProvider
            newProvider.asBinder().linkToDeath(
                {
                    // We might've gotten a new provider before the old provider died, so we only
                    // need to clear our provider if the most recent provider died.
                    if (provider == newProvider) {
                        provider = null
                    }
                },
                /* flags= */ 0
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Returns a list containing the current nearby devices. */
    fun getCurrentNearbyDevices(): List<NearbyDevice> {
        val currentProvider = provider ?: return emptyList()
        return currentProvider.currentNearbyDevices
    }

    /**
     * Registers [callback] to be notified each time a device's range changes or when a new device
     * comes within range.
     */
    fun registerNearbyDevicesCallback(callback: INearbyMediaDevicesUpdateCallback) {
        val currentProvider = provider ?: return
        currentProvider.registerNearbyDevicesCallback(callback)
    }

    /**
     * Un-registers [callback]. See [registerNearbyDevicesCallback].
     */
    fun unregisterNearbyDevicesCallback(callback: INearbyMediaDevicesUpdateCallback) {
        val currentProvider = provider ?: return
        currentProvider.unregisterNearbyDevicesCallback(callback)
    }
}
