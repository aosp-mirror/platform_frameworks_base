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
import android.content.res.Resources
import com.android.server.wm.traces.common.FlickerComponentName
import com.android.server.wm.traces.parser.toFlickerComponent
import com.android.wm.shell.flicker.testapp.Components

class SplitScreenHelper(
    instrumentation: Instrumentation,
    activityLabel: String,
    componentsInfo: FlickerComponentName
) : BaseAppHelper(instrumentation, activityLabel, componentsInfo) {

    companion object {
        const val TEST_REPETITIONS = 1
        const val TIMEOUT_MS = 3_000L

        // TODO: remove all legacy split screen flicker tests when legacy split screen is fully
        //  deprecated.
        fun isUsingLegacySplit(): Boolean =
                Resources.getSystem().getBoolean(com.android.internal.R.bool.config_useLegacySplit)

        fun getPrimary(instrumentation: Instrumentation): SplitScreenHelper =
            SplitScreenHelper(instrumentation,
                Components.SplitScreenActivity.LABEL,
                Components.SplitScreenActivity.COMPONENT.toFlickerComponent())

        fun getSecondary(instrumentation: Instrumentation): SplitScreenHelper =
            SplitScreenHelper(instrumentation,
                Components.SplitScreenSecondaryActivity.LABEL,
                Components.SplitScreenSecondaryActivity.COMPONENT.toFlickerComponent())

        fun getNonResizeable(instrumentation: Instrumentation): SplitScreenHelper =
            SplitScreenHelper(instrumentation,
                Components.NonResizeableActivity.LABEL,
                Components.NonResizeableActivity.COMPONENT.toFlickerComponent())
    }
}
