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

package com.android.server.wm.flicker.helpers

import android.app.Instrumentation
import android.content.Intent
import android.tools.traces.parsers.WindowManagerStateHelper
import android.tools.traces.parsers.toFlickerComponent
import com.android.server.wm.flicker.testapp.ActivityOptions.BottomHalfPip

class BottomHalfPipAppHelper(
    instrumentation: Instrumentation,
    private val useLaunchingActivity: Boolean = false,
    private val fillTaskOnCreate: Boolean = false,
) : PipAppHelper(
    instrumentation,
    appName = BottomHalfPip.LABEL,
    componentNameMatcher = BottomHalfPip.COMPONENT.toFlickerComponent()
) {
    override val openAppIntent: Intent
        get() = super.openAppIntent.apply {
            component = if (useLaunchingActivity) {
                BottomHalfPip.LAUNCHING_APP_COMPONENT
            } else {
                BottomHalfPip.COMPONENT
            }
            if (fillTaskOnCreate) {
                putExtra(BottomHalfPip.EXTRA_BOTTOM_HALF_LAYOUT, false.toString())
            }
        }

    override fun exitPipToOriginalTaskViaIntent(wmHelper: WindowManagerStateHelper) {
        launchViaIntent(
            wmHelper,
            Intent().apply {
                component = BottomHalfPip.COMPONENT
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun toggleBottomHalfLayout() {
        clickObject(TOGGLE_BOTTOM_HALF_LAYOUT_ID)
    }

    companion object {
        private const val TOGGLE_BOTTOM_HALF_LAYOUT_ID = "toggle_bottom_half_layout"
    }
}