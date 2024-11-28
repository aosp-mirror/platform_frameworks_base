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

package com.android.systemui.statusbar.phone

import android.content.Context
import android.view.MotionEvent
import com.android.systemui.statusbar.AutoHideUiElement
import java.io.PrintWriter

/**
 * Controls the auto-hide behavior of system bars (status bar, navigation bar).
 *
 * This interface provides methods to manage the auto-hide schedule of system bars, allowing them to
 * be shown or hidden.
 */
interface AutoHideController {
    /**
     * Sets a [AutoHideUiElement] status bar that should be controlled by the [AutoHideController].
     */
    fun setStatusBar(element: AutoHideUiElement)

    /**
     * Sets a [AutoHideUiElement] navigation bar that should be controlled by the
     * [AutoHideController].
     */
    fun setNavigationBar(element: AutoHideUiElement)

    /** Resumes the auto-hide behavior that was previously suspended. */
    fun resumeSuspendedAutoHide()

    /** Suspends the auto-hide behavior. */
    fun suspendAutoHide()

    /** Schedules or cancels auto hide behavior based on current system bar state. */
    fun touchAutoHide()

    /** Hides system bars on user touch if the interaction requires them to be hidden. */
    fun checkUserAutoHide(event: MotionEvent)

    /** Called when work should stop and resources should be released. */
    fun stop()

    /** Dumps the current state of the [AutoHideController] */
    fun dump(pw: PrintWriter)

    /** Injectable factory for creating a [AutoHideController]. */
    interface Factory {
        /** Create an [AutoHideController] */
        fun create(context: Context): AutoHideController
    }
}
