/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.temporarydisplay.chipbar

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.statusbar.gesture.SwipeUpGestureHandler
import com.android.systemui.statusbar.gesture.SwipeUpGestureLogger
import com.android.systemui.util.boundsOnScreen
import javax.inject.Inject

/**
 * A class to detect when a user has swiped the chipbar away.
 *
 * Effectively [SysUISingleton]. But, this shouldn't be created if the gesture isn't enabled. See
 * [TemporaryDisplayModule.provideSwipeChipbarAwayGestureHandler].
 */
@SysUISingleton
class SwipeChipbarAwayGestureHandler
@Inject
constructor(
    context: Context,
    displayTracker: DisplayTracker,
    logger: SwipeUpGestureLogger,
) : SwipeUpGestureHandler(context, displayTracker, logger, loggerTag = LOGGER_TAG) {

    private var viewFetcher: () -> View? = { null }

    override fun startOfGestureIsWithinBounds(ev: MotionEvent): Boolean {
        val view = viewFetcher.invoke() ?: return false
        // Since chipbar is in its own window, we need to use [boundsOnScreen] to get an accurate
        // bottom. ([view.bottom] would be relative to its window, which would be too small.)
        val viewBottom = view.boundsOnScreen.bottom
        // Allow the gesture to start a bit below the chipbar
        return ev.y <= 1.5 * viewBottom
    }

    /**
     * Sets a fetcher that returns the current chipbar view. The fetcher will be invoked whenever a
     * gesture starts to determine if the gesture is near the chipbar.
     */
    fun setViewFetcher(fetcher: () -> View?) {
        viewFetcher = fetcher
    }

    /** Removes the current view fetcher. */
    fun resetViewFetcher() {
        viewFetcher = { null }
    }
}

private const val LOGGER_TAG = "SwipeChipbarAway"
