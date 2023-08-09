/**
 * Copyright (c) 2020 The Android Open Source Project
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
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor

class UnresponsiveReceiver(channel: InputChannel, looper: Looper) :
        InputEventReceiver(channel, looper) {
    companion object {
        const val TAG = "UnresponsiveReceiver"
    }
    override fun onInputEvent(event: InputEvent) {
        Log.i(TAG, "Received $event")
        // Not calling 'finishInputEvent' in order to trigger the ANR
    }
}

class UnresponsiveGestureMonitorActivity : Activity() {
    companion object {
        const val MONITOR_NAME = "unresponsive gesture monitor"
    }
    private lateinit var mInputEventReceiver: InputEventReceiver
    private lateinit var mInputMonitor: InputMonitor
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inputManager = checkNotNull(getSystemService(InputManager::class.java))
        mInputMonitor = inputManager.monitorGestureInput(MONITOR_NAME, displayId)
        mInputEventReceiver = UnresponsiveReceiver(
                mInputMonitor.getInputChannel(), Looper.myLooper()!!)
    }
}
