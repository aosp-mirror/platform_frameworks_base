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
package com.android.systemui.statusbar.policy.data.repository

import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Tracks state related to device provisioning. */
interface DeviceProvisioningRepository {
    /**
     * Whether this device has been provisioned.
     *
     * @see android.provider.Settings.Global.DEVICE_PROVISIONED
     */
    val isDeviceProvisioned: Flow<Boolean>

    /**
     * Whether this device has been provisioned.
     *
     * @see android.provider.Settings.Global.DEVICE_PROVISIONED
     */
    fun isDeviceProvisioned(): Boolean
}

@Module
interface DeviceProvisioningRepositoryModule {
    @Binds fun bindsImpl(impl: DeviceProvisioningRepositoryImpl): DeviceProvisioningRepository
}

class DeviceProvisioningRepositoryImpl
@Inject
constructor(
    private val deviceProvisionedController: DeviceProvisionedController,
) : DeviceProvisioningRepository {

    override val isDeviceProvisioned: Flow<Boolean> = conflatedCallbackFlow {
        val listener =
            object : DeviceProvisionedController.DeviceProvisionedListener {
                override fun onDeviceProvisionedChanged() {
                    trySend(isDeviceProvisioned())
                }
            }
        deviceProvisionedController.addCallback(listener)
        trySend(isDeviceProvisioned())
        awaitClose { deviceProvisionedController.removeCallback(listener) }
    }

    override fun isDeviceProvisioned(): Boolean {
        return deviceProvisionedController.isDeviceProvisioned
    }
}
