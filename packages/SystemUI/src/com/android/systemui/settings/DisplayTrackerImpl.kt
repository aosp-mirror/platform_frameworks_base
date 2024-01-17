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

package com.android.systemui.settings

import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS
import android.os.Handler
import android.view.Display
import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.android.app.tracing.traceSection
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.Assert
import java.lang.ref.WeakReference
import java.util.concurrent.Executor

class DisplayTrackerImpl
internal constructor(
    val displayManager: DisplayManager,
    @Background val backgroundHandler: Handler
) : DisplayTracker {
    override val defaultDisplayId: Int = Display.DEFAULT_DISPLAY
    override val allDisplays: Array<Display>
        get() = displayManager.displays

    @GuardedBy("displayCallbacks")
    private val displayCallbacks: MutableList<DisplayTrackerDataItem> = ArrayList()
    @GuardedBy("brightnessCallbacks")
    private val brightnessCallbacks: MutableList<DisplayTrackerDataItem> = ArrayList()

    @VisibleForTesting
    val displayChangedListener: DisplayManager.DisplayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                traceSection(
                    "DisplayTrackerImpl.displayChangedDisplayListener#onDisplayAdded",
                ) {
                    val list = synchronized(displayCallbacks) { displayCallbacks.toList() }
                    onDisplayAdded(displayId, list)
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
                traceSection(
                    "DisplayTrackerImpl.displayChangedDisplayListener#onDisplayRemoved",
                ) {
                    val list = synchronized(displayCallbacks) { displayCallbacks.toList() }
                    onDisplayRemoved(displayId, list)
                }
            }

            override fun onDisplayChanged(displayId: Int) {
                traceSection(
                    "DisplayTrackerImpl.displayChangedDisplayListener#onDisplayChanged",
                ) {
                    val list = synchronized(displayCallbacks) { displayCallbacks.toList() }
                    onDisplayChanged(displayId, list)
                }
            }
        }

    @VisibleForTesting
    val displayBrightnessChangedListener: DisplayManager.DisplayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                traceSection(
                    "DisplayTrackerImpl.displayBrightnessChangedDisplayListener#onDisplayChanged",
                ) {
                    val list = synchronized(brightnessCallbacks) { brightnessCallbacks.toList() }
                    onDisplayChanged(displayId, list)
                }
            }
        }

    override fun addDisplayChangeCallback(callback: DisplayTracker.Callback, executor: Executor) {
        synchronized(displayCallbacks) {
            if (displayCallbacks.isEmpty()) {
                displayManager.registerDisplayListener(displayChangedListener, backgroundHandler)
            }
            displayCallbacks.add(DisplayTrackerDataItem(WeakReference(callback), executor))
        }
    }

    override fun addBrightnessChangeCallback(
        callback: DisplayTracker.Callback,
        executor: Executor
    ) {
        synchronized(brightnessCallbacks) {
            if (brightnessCallbacks.isEmpty()) {
                displayManager.registerDisplayListener(
                    displayBrightnessChangedListener,
                    backgroundHandler,
                    EVENT_FLAG_DISPLAY_BRIGHTNESS
                )
            }
            brightnessCallbacks.add(DisplayTrackerDataItem(WeakReference(callback), executor))
        }
    }

    override fun removeCallback(callback: DisplayTracker.Callback) {
        synchronized(displayCallbacks) {
            val changed = displayCallbacks.removeIf { it.sameOrEmpty(callback) }
            if (changed && displayCallbacks.isEmpty()) {
                displayManager.unregisterDisplayListener(displayChangedListener)
            }
        }

        synchronized(brightnessCallbacks) {
            val changed = brightnessCallbacks.removeIf { it.sameOrEmpty(callback) }
            if (changed && brightnessCallbacks.isEmpty()) {
                displayManager.unregisterDisplayListener(displayBrightnessChangedListener)
            }
        }
    }

    override fun getDisplay(displayId: Int): Display {
        return displayManager.getDisplay(displayId)
    }

    @WorkerThread
    private fun onDisplayAdded(displayId: Int, list: List<DisplayTrackerDataItem>) {
        Assert.isNotMainThread()

        notifySubscribers({ onDisplayAdded(displayId) }, list)
    }

    @WorkerThread
    private fun onDisplayRemoved(displayId: Int, list: List<DisplayTrackerDataItem>) {
        Assert.isNotMainThread()

        notifySubscribers({ onDisplayRemoved(displayId) }, list)
    }

    @WorkerThread
    private fun onDisplayChanged(displayId: Int, list: List<DisplayTrackerDataItem>) {
        Assert.isNotMainThread()

        notifySubscribers({ onDisplayChanged(displayId) }, list)
    }

    private inline fun notifySubscribers(
        crossinline action: DisplayTracker.Callback.() -> Unit,
        list: List<DisplayTrackerDataItem>
    ) {
        list.forEach {
            if (it.callback.get() != null) {
                it.executor.execute { it.callback.get()?.action() }
            }
        }
    }

    private data class DisplayTrackerDataItem(
        val callback: WeakReference<DisplayTracker.Callback>,
        val executor: Executor
    ) {
        fun sameOrEmpty(other: DisplayTracker.Callback): Boolean {
            return callback.get()?.equals(other) ?: true
        }
    }
}
