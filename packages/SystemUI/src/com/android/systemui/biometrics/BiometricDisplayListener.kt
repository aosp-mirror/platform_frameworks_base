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
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.os.Handler
import android.view.Surface
import com.android.systemui.biometrics.BiometricDisplayListener.SensorType.Generic

/**
 * A listener for keeping overlays for biometric sensors aligned with the physical device
 * device's screen. The [onChanged] will be dispatched on the [handler]
 * whenever a relevant change to the device's configuration (orientation, fold, display change,
 * etc.) may require the UI to change for the given [sensorType].
 */
class BiometricDisplayListener(
    private val context: Context,
    private val displayManager: DisplayManager,
    private val handler: Handler,
    private val sensorType: SensorType = SensorType.Generic,
    private val onChanged: () -> Unit
) : DisplayManager.DisplayListener {

    private var lastRotation = Surface.ROTATION_0

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        val rotationChanged = didRotationChange()

        when (sensorType) {
            is SensorType.SideFingerprint -> onChanged()
            else -> {
                if (rotationChanged) {
                    onChanged()
                }
            }
        }
    }

    private fun didRotationChange(): Boolean {
        val rotation = context.display?.rotation ?: return false
        val last = lastRotation
        lastRotation = rotation
        return last != rotation
    }

    /** Listen for changes. */
    fun enable() {
        lastRotation = context.display?.rotation ?: Surface.ROTATION_0
        displayManager.registerDisplayListener(
            this,
            handler,
            DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
        )
    }

    /** Stop listening for changes. */
    fun disable() {
        displayManager.unregisterDisplayListener(this)
    }

    /**
     * Type of sensor to determine what kind of display changes require layouts.
     *
     * The [Generic] type should be used in cases where the modality can vary, such as
     * biometric prompt (and this object will likely change as multi-mode auth is added).
     */
    sealed class SensorType {
        object Generic : SensorType()
        object UnderDisplayFingerprint : SensorType()
        data class SideFingerprint(
            val properties: FingerprintSensorPropertiesInternal
        ) : SensorType()
    }
}
