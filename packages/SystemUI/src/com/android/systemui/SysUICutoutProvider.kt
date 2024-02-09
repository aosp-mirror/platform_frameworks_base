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

package com.android.systemui

import android.content.Context
import android.graphics.Rect
import android.util.RotationUtils
import android.view.Display
import android.view.DisplayCutout
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.display.naturalBounds
import javax.inject.Inject

@SysUISingleton
class SysUICutoutProvider
@Inject
constructor(
    private val context: Context,
    private val cameraProtectionLoader: CameraProtectionLoader,
) {

    private val cameraProtectionList by lazy {
        cameraProtectionLoader.loadCameraProtectionInfoList()
    }

    /**
     * Returns the [SysUICutoutInformation] for the current display and the current rotation.
     *
     * This means that the bounds of the display cutout and the camera protection will be
     * adjusted/rotated for the current rotation.
     */
    fun cutoutInfoForCurrentDisplayAndRotation(): SysUICutoutInformation? {
        val display = context.display
        val displayCutout: DisplayCutout = display.cutout ?: return null
        return SysUICutoutInformation(displayCutout, getCameraProtectionForDisplay(display))
    }

    private fun getCameraProtectionForDisplay(display: Display): CameraProtectionInfo? {
        val displayUniqueId: String? = display.uniqueId
        if (displayUniqueId.isNullOrEmpty()) {
            return null
        }
        val cameraProtection: CameraProtectionInfo =
            cameraProtectionList.firstOrNull { it.displayUniqueId == displayUniqueId }
                ?: return null
        val adjustedBoundsForRotation =
            calculateCameraProtectionBoundsForRotation(display, cameraProtection.bounds)
        return cameraProtection.copy(bounds = adjustedBoundsForRotation)
    }

    private fun calculateCameraProtectionBoundsForRotation(
        display: Display,
        originalProtectionBounds: Rect,
    ): Rect {
        val displayNaturalBounds = display.naturalBounds
        val rotatedBoundsOut = Rect(originalProtectionBounds)
        RotationUtils.rotateBounds(
            /* inOutBounds = */ rotatedBoundsOut,
            /* parentWidth = */ displayNaturalBounds.width(),
            /* parentHeight = */ displayNaturalBounds.height(),
            /* rotation = */ display.rotation
        )
        return rotatedBoundsOut
    }
}
