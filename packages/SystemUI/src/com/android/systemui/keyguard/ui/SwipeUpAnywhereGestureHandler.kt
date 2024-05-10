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

package com.android.systemui.keyguard.ui

import android.content.Context
import android.view.MotionEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.statusbar.gesture.SwipeUpGestureHandler
import com.android.systemui.statusbar.gesture.SwipeUpGestureLogger
import javax.inject.Inject

/** A class to detect when a user swipes up anywhere on the display. */
@SysUISingleton
class SwipeUpAnywhereGestureHandler
@Inject
constructor(
    context: Context,
    displayTracker: DisplayTracker,
    logger: SwipeUpGestureLogger,
) :
    SwipeUpGestureHandler(
        context,
        displayTracker,
        logger,
        loggerTag = "SwipeUpAnywhereGestureHandler"
    ) {
    override fun startOfGestureIsWithinBounds(ev: MotionEvent): Boolean {
        return true
    }
}
