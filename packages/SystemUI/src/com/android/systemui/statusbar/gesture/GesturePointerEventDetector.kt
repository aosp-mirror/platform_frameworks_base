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

package com.android.systemui.statusbar.gesture

import android.content.Context
import android.view.InputEvent
import android.view.MotionEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.DisplayTracker
import javax.inject.Inject

/**
 * A class to detect when a motion event happens. To be notified when the event is detected, add a
 * callback via [addOnGestureDetectedCallback].
 */
@SysUISingleton
class GesturePointerEventDetector @Inject constructor(
        private val context: Context,
        displayTracker: DisplayTracker
) : GenericGestureDetector(
        GesturePointerEventDetector::class.simpleName!!,
        displayTracker.defaultDisplayId
) {
    override fun onInputEvent(ev: InputEvent) {
        if (ev !is MotionEvent) {
            return
        }
        // Pass all events to [gestureDetector], which will then notify [gestureListener] when a tap
        // is detected.
        onGestureDetected(ev)
    }
}
