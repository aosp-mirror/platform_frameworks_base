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

package com.android.systemui.statusbar.gesture

import android.content.Context
import android.view.GestureDetector
import android.view.InputEvent
import android.view.MotionEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.DisplayTracker
import javax.inject.Inject

/**
 * A class to detect when a user taps the screen. To be notified when the tap is detected, add a
 * callback via [addOnGestureDetectedCallback].
 */
@SysUISingleton
class TapGestureDetector @Inject constructor(
    private val context: Context,
    displayTracker: DisplayTracker
) : GenericGestureDetector(
        TapGestureDetector::class.simpleName!!,
        displayTracker.defaultDisplayId
) {

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onGestureDetected(e)
            return true
        }
    }

    private var gestureDetector: GestureDetector? = null

    override fun onInputEvent(ev: InputEvent) {
        if (ev !is MotionEvent) {
            return
        }
        // Pass all events to [gestureDetector], which will then notify [gestureListener] when a tap
        // is detected.
        gestureDetector!!.onTouchEvent(ev)
    }

    /** Start listening for the tap gesture. */
    override fun startGestureListening() {
        super.startGestureListening()
        gestureDetector = GestureDetector(context, gestureListener)
    }

    /** Stop listening for the swipe gesture. */
    override fun stopGestureListening() {
        super.stopGestureListening()
        gestureDetector = null
    }
}
