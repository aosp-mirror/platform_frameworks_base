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

package com.android.systemui.temporarydisplay

/**
 * A superclass view state used with [TemporaryViewDisplayController].
 */
abstract class TemporaryViewInfo {
    /**
     * The title to use for the window that displays the temporary view. Should be normally cased,
     * like "Window Title".
     */
    abstract val windowTitle: String

    /**
     * A string used for logging if we needed to wake the screen in order to display the temporary
     * view. Should be screaming snake cased, like WAKE_REASON.
     */
    abstract val wakeReason: String

    /**
     * The amount of time the given view state should display on the screen before it times out and
     * disappears.
     */
    open val timeoutMs: Int = DEFAULT_TIMEOUT_MILLIS

    /**
     * The id of the temporary view.
     */
    abstract val id: String

    /** The priority for this view. */
    abstract val priority: ViewPriority
}

const val DEFAULT_TIMEOUT_MILLIS = 10000

/**
 * The priority of the view being displayed.
 *
 * Must be ordered from lowest priority to highest priority. (CRITICAL is currently the highest
 * priority.)
 */
enum class ViewPriority {
    NORMAL,
    CRITICAL,
}
