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

import android.annotation.CallSuper
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import android.view.InputEvent
import android.view.MotionEvent
import com.android.systemui.shared.system.InputChannelCompat
import com.android.systemui.shared.system.InputMonitorCompat

/**
 * An abstract class to help detect gestures that occur anywhere on the display (not specific to a
 * certain view).
 *
 * This class handles starting/stopping the gesture detection system as well as
 * registering/unregistering callbacks for when gestures occur. Note that the class will only listen
 * for gestures when there's at least one callback registered.
 *
 * Subclasses should implement [onInputEvent] to detect their specific gesture. Once a specific
 * gesture is detected, they should call [onGestureDetected] (which will notify the callbacks).
 */
abstract class GenericGestureDetector(
    private val tag: String
) {
    /**
     * Active callbacks, each associated with a tag. Gestures will only be monitored if
     * [callbacks.size] > 0.
     */
    private val callbacks: MutableMap<String, (MotionEvent) -> Unit> = mutableMapOf()

    private var inputMonitor: InputMonitorCompat? = null
    private var inputReceiver: InputChannelCompat.InputEventReceiver? = null

    /**
     * Adds a callback that will be triggered when the tap gesture is detected.
     *
     * The callback receive the last motion event in the gesture.
     */
    fun addOnGestureDetectedCallback(tag: String, callback: (MotionEvent) -> Unit) {
        val callbacksWasEmpty = callbacks.isEmpty()
        callbacks[tag] = callback
        if (callbacksWasEmpty) {
            startGestureListening()
        }
    }

    /** Removes the callback. */
    fun removeOnGestureDetectedCallback(tag: String) {
        callbacks.remove(tag)
        if (callbacks.isEmpty()) {
            stopGestureListening()
        }
    }

    /** Triggered each time a touch event occurs (and at least one callback is registered). */
    abstract fun onInputEvent(ev: InputEvent)

    /**
     * Should be called by subclasses when their specific gesture is detected with the last motion
     * event in the gesture.
     */
    internal fun onGestureDetected(e: MotionEvent) {
        callbacks.values.forEach { it.invoke(e) }
    }

    /** Start listening to touch events. */
    @CallSuper
    internal open fun startGestureListening() {
        stopGestureListening()

        inputMonitor = InputMonitorCompat(tag, Display.DEFAULT_DISPLAY).also {
            inputReceiver = it.getInputReceiver(
                Looper.getMainLooper(),
                Choreographer.getInstance(),
                this::onInputEvent
            )
        }
    }

    /** Stop listening to touch events. */
    @CallSuper
    internal open fun stopGestureListening() {
        inputMonitor?.let {
            inputMonitor = null
            it.dispose()
        }
        inputReceiver?.let {
            inputReceiver = null
            it.dispose()
        }
    }
}
