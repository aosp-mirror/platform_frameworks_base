/*
 * Copyright (C) 2024 The Android Open Source Project
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

import java.io.PrintWriter

class FakeCastController : CastController {
    private var listeners = mutableListOf<CastController.Callback>()

    private var castDevices = emptyList<CastDevice>()

    var lastStoppedDevice: CastDevice? = null
        private set

    override fun addCallback(listener: CastController.Callback) {
        listeners += listener
    }

    override fun removeCallback(listener: CastController.Callback) {
        listeners -= listener
    }

    override fun getCastDevices(): List<CastDevice> {
        return castDevices
    }

    fun setCastDevices(devices: List<CastDevice>) {
        castDevices = devices
        listeners.forEach { it.onCastDevicesChanged() }
    }

    override fun startCasting(device: CastDevice?) {}

    override fun stopCasting(device: CastDevice?) {
        lastStoppedDevice = device
    }

    override fun hasConnectedCastDevice(): Boolean {
        return castDevices.any { it.state == CastDevice.CastState.Connected }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {}

    override fun setDiscovering(request: Boolean) {}

    override fun setCurrentUserId(currentUserId: Int) {}
}
