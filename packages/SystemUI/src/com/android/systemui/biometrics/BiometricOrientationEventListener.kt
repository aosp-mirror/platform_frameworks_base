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

package com.android.systemui.biometrics

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.view.OrientationEventListener
import android.view.Surface

/**
 * An [OrientationEventListener] that invokes the [onOrientationChanged] callback whenever
 * the orientation of the device has changed in order to keep overlays for biometric sensors
 * aligned with the device's screen.
 */
class BiometricOrientationEventListener(
    private val context: Context,
    private val onOrientationChanged: () -> Unit,
    private val displayManager: DisplayManager,
    private val handler: Handler
) : DisplayManager.DisplayListener {

    private var lastRotation = context.display?.rotation ?: Surface.ROTATION_0

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        val rotation = context.display?.rotation ?: return
        if (lastRotation != rotation) {
            lastRotation = rotation

            onOrientationChanged()
        }
    }

    fun enable() {
        displayManager.registerDisplayListener(this, handler)
    }

    fun disable() {
        displayManager.unregisterDisplayListener(this)
    }
}
