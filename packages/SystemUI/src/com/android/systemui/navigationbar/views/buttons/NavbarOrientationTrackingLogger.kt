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

package com.android.systemui.navigationbar.views.buttons

import android.view.Surface
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.NavbarOrientationTrackingLog
import javax.inject.Inject

class NavbarOrientationTrackingLogger
@Inject
constructor(@NavbarOrientationTrackingLog private val buffer: LogBuffer) {
    fun logPrimaryAndSecondaryVisibility(
        methodName: String,
        isViewVisible: Boolean,
        isImmersiveMode: Boolean,
        isSecondaryHandleVisible: Boolean,
        currentRotation: Int,
        startingQuickSwitchRotation: Int
    ) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = methodName
                bool1 = isViewVisible
                bool2 = isImmersiveMode
                bool3 = isSecondaryHandleVisible
                int1 = startingQuickSwitchRotation
                int2 = currentRotation
            },
            {
                "Caller Method: $str1\n" +
                    "\tNavbar Visible: $bool1\n" +
                    "\tImmersive Mode: $bool2\n" +
                    "\tSecondary Handle Visible: $bool3\n" +
                    "\tDelta Rotation: ${getDeltaRotation(int1, int2)}\n" +
                    "\tStarting QuickSwitch Rotation: $int1\n" +
                    "\tCurrent Rotation: $int2\n"
            }
        )
    }

    private fun getDeltaRotation(oldRotation: Int, newRotation: Int): String {
        var rotation: String = "0"
        when (deltaRotation(oldRotation, newRotation)) {
            Surface.ROTATION_90 -> {
                rotation = "90"
            }
            Surface.ROTATION_180 -> {
                rotation = "180"
            }
            Surface.ROTATION_270 -> {
                rotation = "270"
            }
        }
        return rotation
    }

    private fun deltaRotation(oldRotation: Int, newRotation: Int): Int {
        var delta = newRotation - oldRotation
        if (delta < 0) delta += 4
        return delta
    }
}

private const val TAG = "NavbarOrientationTracking"
