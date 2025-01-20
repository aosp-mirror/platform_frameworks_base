/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.policy

import com.android.systemui.statusbar.policy.DevicePostureController.DevicePostureInt

class FakeDevicePostureController : DevicePostureController {

    private var devicePosture: Int = DevicePostureController.DEVICE_POSTURE_OPENED
    val callbacks: MutableCollection<DevicePostureController.Callback> = mutableSetOf()

    override fun addCallback(listener: DevicePostureController.Callback) {
        callbacks.add(listener)
    }

    override fun removeCallback(listener: DevicePostureController.Callback) {
        callbacks.add(listener)
    }

    fun setDevicePosture(@DevicePostureInt posture: Int) {
        devicePosture = posture
    }

    @DevicePostureInt override fun getDevicePosture(): Int = devicePosture
}
