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

package com.android.systemui.settings

import android.view.Display
import java.util.concurrent.Executor

/**
 * Display tracker for SystemUI.
 *
 * This tracker provides async access to display information, as well as callbacks for display
 * changes.
 */
interface DisplayTracker {

    /** The id for the default display for the current SystemUI instance. */
    val defaultDisplayId: Int

    /** All displays that should be associated with the current SystemUI instance. */
    val allDisplays: Array<Display>

    /**
     * Add a [Callback] to be notified of display changes, including additions, removals, and
     * configuration changes, on a particular [Executor].
     */
    fun addDisplayChangeCallback(callback: Callback, executor: Executor)

    /**
     * Add a [Callback] to be notified of display brightness changes, on a particular [Executor].
     * This callback will trigger Callback#onDisplayChanged for a display brightness change.
     */
    fun addBrightnessChangeCallback(callback: Callback, executor: Executor)

    /** Remove a [Callback] previously added. */
    fun removeCallback(callback: Callback)

    /** Ćallback for notifying of changes. */
    interface Callback {

        /** Notifies that a display has been added. */
        fun onDisplayAdded(displayId: Int) {}

        /** Notifies that a display has been removed. */
        fun onDisplayRemoved(displayId: Int) {}

        /** Notifies a display has been changed */
        fun onDisplayChanged(displayId: Int) {}
    }
}
