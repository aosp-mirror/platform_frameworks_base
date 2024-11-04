/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.test.input

import android.app.Activity
import android.os.Bundle
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull

class CaptureEventActivity : Activity() {
    private val events = LinkedBlockingQueue<InputEvent>()
    var shouldHandleKeyEvents = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the fixed orientation if requested
        if (intent.hasExtra(EXTRA_FIXED_ORIENTATION)) {
            val orientation = intent.getIntExtra(EXTRA_FIXED_ORIENTATION, 0)
            setRequestedOrientation(orientation)
        }

        // Set the flag if requested
        if (intent.hasExtra(EXTRA_WINDOW_FLAGS)) {
            val flags = intent.getIntExtra(EXTRA_WINDOW_FLAGS, 0)
            window.addFlags(flags)
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        events.add(MotionEvent.obtain(ev))
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        events.add(MotionEvent.obtain(ev))
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        events.add(KeyEvent(event))
        return shouldHandleKeyEvents
    }

    override fun dispatchTrackballEvent(ev: MotionEvent?): Boolean {
        events.add(MotionEvent.obtain(ev))
        return true
    }

    fun getInputEvent(): InputEvent? {
        return events.poll(5, TimeUnit.SECONDS)
    }

    fun hasReceivedEvents(): Boolean {
        return !events.isEmpty()
    }

    fun assertNoEvents() {
        val event = events.poll(100, TimeUnit.MILLISECONDS)
        assertNull("Expected no events, but received $event", event)
    }

    companion object {
        const val EXTRA_FIXED_ORIENTATION = "fixed_orientation"
        const val EXTRA_WINDOW_FLAGS = "window_flags"
    }
}
