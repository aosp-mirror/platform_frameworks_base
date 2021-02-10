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

package com.android.wm.shell.flicker.pip

import android.app.WindowConfiguration
import android.content.ComponentName
import com.android.server.wm.flicker.traces.windowmanager.WindowManagerStateSubject
import com.android.server.wm.traces.common.windowmanager.WindowManagerState
import com.android.server.wm.traces.parser.toWindowName
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper
import com.google.common.truth.Truth

inline val WindowManagerState.pinnedWindows
    get() = visibleWindows
        .filter { it.windowingMode == WindowConfiguration.WINDOWING_MODE_PINNED }

/**
 * Checks if the state has any window in PIP mode
 */
fun WindowManagerState.hasPipWindow(): Boolean = pinnedWindows.isNotEmpty()

/**
 * Checks that an activity [activity] is in PIP mode
 */
fun WindowManagerState.isInPipMode(activity: ComponentName): Boolean {
    val windowName = activity.toWindowName()
    return pinnedWindows.any { it.title == windowName }
}

/**
 * Asserts that an activity [activity] exists and is in PIP mode
 */
fun WindowManagerStateSubject.isInPipMode(
    activity: ComponentName
): WindowManagerStateSubject = apply {
    val windowName = activity.toWindowName()
    hasWindow(windowName)
    val pinnedWindows = wmState.pinnedWindows
        .map { it.title }
    Truth.assertWithMessage("Window not in PIP mode")
        .that(pinnedWindows)
        .contains(windowName)
}

/**
 * Waits until the state has a window in PIP mode, i.e., with
 * windowingMode = WindowConfiguration.WINDOWING_MODE_PINNED
 */
fun WindowManagerStateHelper.waitPipWindowShown(): Boolean =
    waitFor("PIP window shown") {
        val result = it.wmState.hasPipWindow()
        result
    }

/**
 * Waits until the state doesn't have a window in PIP mode, i.e., with
 * windowingMode = WindowConfiguration.WINDOWING_MODE_PINNED
 */
fun WindowManagerStateHelper.waitPipWindowGone(): Boolean =
    waitFor("PIP window gone") {
        val result = !it.wmState.hasPipWindow()
        result
    }
