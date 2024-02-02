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

package com.android.systemui.shade

import com.android.systemui.log.ConstantStringsLogger
import com.android.systemui.log.ConstantStringsLoggerImpl
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.core.LogMessage
import com.android.systemui.log.dagger.ShadeWindowLog
import javax.inject.Inject

private const val TAG = "systemui.shadewindow"

class ShadeWindowLogger @Inject constructor(@ShadeWindowLog private val buffer: LogBuffer) :
    ConstantStringsLogger by ConstantStringsLoggerImpl(buffer, TAG) {

    fun logNewState(state: Any) {
        buffer.log(
            TAG,
            DEBUG,
            { str1 = state.toString() },
            { "Applying new state: $str1" }
        )
    }

    private inline fun log(
        logLevel: LogLevel,
        initializer: LogMessage.() -> Unit,
        noinline printer: LogMessage.() -> String
    ) {
        buffer.log(TAG, logLevel, initializer, printer)
    }

    fun logApplyVisibility(visible: Boolean) {
        buffer.log(
            TAG,
            DEBUG,
            { bool1 = visible },
            { "Updating visibility, should be visible : $bool1" })
    }

    fun logIsExpanded(
        isExpanded: Boolean,
        forceWindowCollapsed: Boolean,
        isKeyguardShowingAndNotOccluded: Boolean,
        panelVisible: Boolean,
        keyguardFadingAway: Boolean,
        bouncerShowing: Boolean,
        headsUpNotificationShowing: Boolean,
        scrimsVisibilityNotTransparent: Boolean,
        backgroundBlurRadius: Boolean,
        launchingActivityFromNotification: Boolean
    ) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = isExpanded.toString()
                bool1 = forceWindowCollapsed
                bool2 = isKeyguardShowingAndNotOccluded
                bool3 = panelVisible
                bool4 = keyguardFadingAway
                int1 = if (bouncerShowing) 1 else 0
                int2 = if (headsUpNotificationShowing) 1 else 0
                long1 = if (scrimsVisibilityNotTransparent) 1 else 0
                long2 = if (backgroundBlurRadius) 1 else 0
                double1 = if (launchingActivityFromNotification) 1.0 else 0.0
            },
            { "Setting isExpanded to $str1: forceWindowCollapsed $bool1, " +
                    "isKeyguardShowingAndNotOccluded $bool2, panelVisible $bool3, " +
                    "keyguardFadingAway $bool4, bouncerShowing $int1," +
                    "headsUpNotificationShowing $int2, scrimsVisibilityNotTransparent $long1," +
                    "backgroundBlurRadius $long2, launchingActivityFromNotification $double1"}
        )
    }

    fun logShadeVisibleAndFocusable(visible: Boolean) {
        buffer.log(
            TAG,
            DEBUG,
            { bool1 = visible },
            { "Updating shade, should be visible and focusable: $bool1" }
        )
    }

    fun logShadeFocusable(focusable: Boolean) {
        buffer.log(
            TAG,
            DEBUG,
            { bool1 = focusable },
            { "Updating shade, should be focusable : $bool1" }
        )
    }
}
