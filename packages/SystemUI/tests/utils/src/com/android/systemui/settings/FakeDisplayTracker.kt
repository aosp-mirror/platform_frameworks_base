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

package com.android.systemui.settings

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import java.util.concurrent.Executor

class FakeDisplayTracker constructor(val context: Context) : DisplayTracker {
    val displayManager: DisplayManager = context.getSystemService(DisplayManager::class.java)!!
    override var defaultDisplayId: Int = Display.DEFAULT_DISPLAY
    override var allDisplays: Array<Display> = displayManager.displays

    val displayCallbacks: MutableList<DisplayTracker.Callback> = ArrayList()
    private val brightnessCallbacks: MutableList<DisplayTracker.Callback> = ArrayList()
    override fun addDisplayChangeCallback(callback: DisplayTracker.Callback, executor: Executor) {
        displayCallbacks.add(callback)
    }
    override fun addBrightnessChangeCallback(
        callback: DisplayTracker.Callback,
        executor: Executor
    ) {
        brightnessCallbacks.add(callback)
    }

    override fun removeCallback(callback: DisplayTracker.Callback) {
        displayCallbacks.remove(callback)
        brightnessCallbacks.remove(callback)
    }

    override fun getDisplay(displayId: Int): Display {
        return allDisplays.filter { display -> display.displayId == displayId }[0]
    }

    fun setDefaultDisplay(displayId: Int) {
        defaultDisplayId = displayId
    }

    fun triggerOnDisplayAdded(displayId: Int) {
        notifyCallbacks({ onDisplayAdded(displayId) }, displayCallbacks)
    }

    fun triggerOnDisplayRemoved(displayId: Int) {
        notifyCallbacks({ onDisplayRemoved(displayId) }, displayCallbacks)
    }

    fun triggerOnDisplayChanged(displayId: Int) {
        notifyCallbacks({ onDisplayChanged(displayId) }, displayCallbacks)
    }

    fun triggerOnDisplayBrightnessChanged(displayId: Int) {
        notifyCallbacks({ onDisplayChanged(displayId) }, brightnessCallbacks)
    }

    private inline fun notifyCallbacks(
        crossinline action: DisplayTracker.Callback.() -> Unit,
        list: List<DisplayTracker.Callback>
    ) {
        list.forEach { it.action() }
    }
}
