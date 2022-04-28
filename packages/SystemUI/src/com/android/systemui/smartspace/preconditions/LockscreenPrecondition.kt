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
package com.android.systemui.smartspace.preconditions

import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.smartspace.SmartspacePrecondition
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.util.concurrency.Execution
import javax.inject.Inject

/**
 * {@link LockscreenPrecondition} covers the conditions that must be met before Smartspace can be
 * used over lockscreen. These conditions include the device being provisioned with a setup user
 * and the Smartspace feature flag enabled.
 */
class LockscreenPrecondition @Inject constructor(
    private val featureFlags: FeatureFlags,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val execution: Execution
) : SmartspacePrecondition {
    private var listeners = mutableSetOf<SmartspacePrecondition.Listener>()

    private val deviceProvisionedListener =
            object : DeviceProvisionedController.DeviceProvisionedListener {
                override fun onDeviceProvisionedChanged() {
                    updateDeviceReadiness()
                }

                override fun onUserSetupChanged() {
                    updateDeviceReadiness()
                }
            }

    init {
        deviceProvisionedController.addCallback(deviceProvisionedListener)
    }

    var deviceReady: Boolean = false
        private set

    init {
        updateDeviceReadiness()
    }

    private fun updateDeviceReadiness() {
        if (deviceReady) {
            return
        }

        deviceReady = deviceProvisionedController.isDeviceProvisioned &&
                deviceProvisionedController.isCurrentUserSetup

        if (!deviceReady) {
            return
        }

        deviceProvisionedController.removeCallback(deviceProvisionedListener)
        synchronized(listeners) {
            listeners.forEach { it.onCriteriaChanged() }
        }
    }

    override fun addListener(listener: SmartspacePrecondition.Listener) {
        synchronized(listeners) {
            listeners += listener
        }
        // Always trigger a targeted callback upon addition of listener.
        listener.onCriteriaChanged()
    }

    override fun removeListener(listener: SmartspacePrecondition.Listener) {
        synchronized(listeners) {
            listeners -= listener
        }
    }

    override fun conditionsMet(): Boolean {
        execution.assertIsMainThread()
        return featureFlags.isEnabled(Flags.SMARTSPACE) && deviceReady
    }
}