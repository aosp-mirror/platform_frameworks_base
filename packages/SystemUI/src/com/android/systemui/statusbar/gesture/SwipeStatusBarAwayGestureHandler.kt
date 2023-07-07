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

package com.android.systemui.statusbar.gesture

import android.content.Context
import android.view.MotionEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.statusbar.window.StatusBarWindowController
import javax.inject.Inject

/** A class to detect when a user swipes away the status bar. */
@SysUISingleton
class SwipeStatusBarAwayGestureHandler
@Inject
constructor(
    context: Context,
    displayTracker: DisplayTracker,
    logger: SwipeUpGestureLogger,
    private val statusBarWindowController: StatusBarWindowController,
) : SwipeUpGestureHandler(context, displayTracker, logger, loggerTag = LOGGER_TAG) {
    override fun startOfGestureIsWithinBounds(ev: MotionEvent): Boolean {
        // Gesture starts just below the status bar
        return ev.y >= statusBarWindowController.statusBarHeight &&
            ev.y <= 3 * statusBarWindowController.statusBarHeight
    }
}

private const val LOGGER_TAG = "SwipeStatusBarAway"
