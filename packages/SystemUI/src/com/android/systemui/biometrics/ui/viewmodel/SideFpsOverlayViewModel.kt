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

package com.android.systemui.biometrics.ui.viewmodel

import android.content.Context
import android.graphics.Rect
import android.hardware.biometrics.SensorLocationInternal
import android.util.RotationUtils
import android.view.Display
import android.view.DisplayInfo
import android.view.Surface
import androidx.annotation.RawRes
import com.android.systemui.R
import com.android.systemui.biometrics.domain.interactor.SideFpsOverlayInteractor
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** View-model for SideFpsOverlayView. */
class SideFpsOverlayViewModel
@Inject
constructor(
    @Application private val context: Context,
    private val sideFpsOverlayInteractor: SideFpsOverlayInteractor,
) {

    private val isReverseDefaultRotation =
        context.resources.getBoolean(com.android.internal.R.bool.config_reverseDefaultRotation)

    private val _sensorBounds: MutableStateFlow<Rect> = MutableStateFlow(Rect())
    val sensorBounds = _sensorBounds.asStateFlow()

    val overlayOffsets: Flow<SensorLocationInternal> = sideFpsOverlayInteractor.overlayOffsets

    /** Update the displayId. */
    fun changeDisplay() {
        sideFpsOverlayInteractor.changeDisplay(context.display!!.uniqueId)
    }

    /** Determine the rotation of the sideFps animation given the overlay offsets. */
    val sideFpsAnimationRotation: Flow<Float> =
        overlayOffsets.map { overlayOffsets ->
            val display = context.display!!
            val displayInfo: DisplayInfo = DisplayInfo()
            // b/284098873 `context.display.rotation` may not up-to-date, we use
            // displayInfo.rotation
            display.getDisplayInfo(displayInfo)
            val yAligned: Boolean = overlayOffsets.isYAligned()
            when (getRotationFromDefault(displayInfo.rotation)) {
                Surface.ROTATION_90 -> if (yAligned) 0f else 180f
                Surface.ROTATION_180 -> 180f
                Surface.ROTATION_270 -> if (yAligned) 180f else 0f
                else -> 0f
            }
        }

    /** Populate the sideFps animation from the overlay offsets. */
    @RawRes
    val sideFpsAnimation: Flow<Int> =
        overlayOffsets.map { overlayOffsets ->
            val display = context.display!!
            val displayInfo: DisplayInfo = DisplayInfo()
            // b/284098873 `context.display.rotation` may not up-to-date, we use
            // displayInfo.rotation
            display.getDisplayInfo(displayInfo)
            val yAligned: Boolean = overlayOffsets.isYAligned()
            when (getRotationFromDefault(displayInfo.rotation)) {
                Surface.ROTATION_0 -> if (yAligned) R.raw.sfps_pulse else R.raw.sfps_pulse_landscape
                Surface.ROTATION_180 ->
                    if (yAligned) R.raw.sfps_pulse else R.raw.sfps_pulse_landscape
                else -> if (yAligned) R.raw.sfps_pulse_landscape else R.raw.sfps_pulse
            }
        }

    /**
     * Calculate and update the bounds of the sensor based on the bounds of the overlay view, the
     * maximum bounds of the window, and the offsets of the sensor location.
     */
    fun updateSensorBounds(
        bounds: Rect,
        maximumWindowBounds: Rect,
        offsets: SensorLocationInternal
    ) {
        val isNaturalOrientation = context.display!!.isNaturalOrientation()
        val isDefaultOrientation =
            if (isReverseDefaultRotation) !isNaturalOrientation else isNaturalOrientation

        val displayWidth =
            if (isDefaultOrientation) maximumWindowBounds.width() else maximumWindowBounds.height()
        val displayHeight =
            if (isDefaultOrientation) maximumWindowBounds.height() else maximumWindowBounds.width()
        val boundsWidth = if (isDefaultOrientation) bounds.width() else bounds.height()
        val boundsHeight = if (isDefaultOrientation) bounds.height() else bounds.width()

        val sensorBounds =
            if (offsets.isYAligned()) {
                Rect(
                    displayWidth - boundsWidth,
                    offsets.sensorLocationY,
                    displayWidth,
                    offsets.sensorLocationY + boundsHeight
                )
            } else {
                Rect(
                    offsets.sensorLocationX,
                    0,
                    offsets.sensorLocationX + boundsWidth,
                    boundsHeight
                )
            }

        val displayInfo: DisplayInfo = DisplayInfo()
        context.display!!.getDisplayInfo(displayInfo)

        RotationUtils.rotateBounds(
            sensorBounds,
            Rect(0, 0, displayWidth, displayHeight),
            getRotationFromDefault(displayInfo.rotation)
        )

        _sensorBounds.value = sensorBounds
    }

    private fun getRotationFromDefault(rotation: Int): Int =
        if (isReverseDefaultRotation) (rotation + 1) % 4 else rotation
}

private fun Display.isNaturalOrientation(): Boolean =
    rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180

private fun SensorLocationInternal.isYAligned(): Boolean = sensorLocationY != 0
