/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.flicker.helpers

import android.app.Instrumentation
import android.content.Context
import android.provider.Settings
import com.android.server.wm.traces.common.FlickerComponentName

class MultiWindowHelper(
    instrumentation: Instrumentation,
    activityLabel: String,
    componentsInfo: FlickerComponentName
) : BaseAppHelper(instrumentation, activityLabel, componentsInfo) {

    companion object {
        fun getDevEnableNonResizableMultiWindow(context: Context): Int =
                Settings.Global.getInt(context.contentResolver,
                        Settings.Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW)

        fun setDevEnableNonResizableMultiWindow(context: Context, configValue: Int) =
                Settings.Global.putInt(context.contentResolver,
                        Settings.Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW, configValue)

        fun setSupportsNonResizableMultiWindow(instrumentation: Instrumentation, configValue: Int) =
            executeShellCommand(
                    instrumentation,
                    createConfigSupportsNonResizableMultiWindowCommand(configValue))

        fun resetMultiWindowConfig(instrumentation: Instrumentation) =
            executeShellCommand(instrumentation, resetMultiWindowConfigCommand)

        private fun createConfigSupportsNonResizableMultiWindowCommand(configValue: Int): String =
                "wm set-multi-window-config --supportsNonResizable $configValue"

        private const val resetMultiWindowConfigCommand: String = "wm reset-multi-window-config"
    }
}
