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
import android.graphics.Region
import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.traces.common.FlickerComponentName

class AppPairsHelper(
    instrumentation: Instrumentation,
    activityLabel: String,
    component: FlickerComponentName
) : BaseAppHelper(instrumentation, activityLabel, component) {
    fun getPrimaryBounds(dividerBounds: Region): android.graphics.Region {
        val primaryAppBounds = Region(0, 0, dividerBounds.bounds.right,
                dividerBounds.bounds.bottom + WindowUtils.dockedStackDividerInset)
        return primaryAppBounds
    }

    fun getSecondaryBounds(dividerBounds: Region): android.graphics.Region {
        val displayBounds = WindowUtils.displayBounds
        val secondaryAppBounds = Region(0,
                dividerBounds.bounds.bottom - WindowUtils.dockedStackDividerInset,
                displayBounds.right, displayBounds.bottom - WindowUtils.navigationBarHeight)
        return secondaryAppBounds
    }

    companion object {
        const val TEST_REPETITIONS = 1
        const val TIMEOUT_MS = 3_000L

        fun Flicker.waitAppsShown(app1: SplitScreenHelper?, app2: SplitScreenHelper?) {
            wmHelper.waitFor("primaryAndSecondaryAppsVisible") { dump ->
                val primaryAppVisible = app1?.let {
                    dump.wmState.isWindowSurfaceShown(app1.defaultWindowName)
                } ?: false
                val secondaryAppVisible = app2?.let {
                    dump.wmState.isWindowSurfaceShown(app2.defaultWindowName)
                } ?: false
                primaryAppVisible && secondaryAppVisible
            }
        }
    }
}
