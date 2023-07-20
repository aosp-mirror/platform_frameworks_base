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

package com.android.systemui.statusbar.window

import android.app.StatusBarManager
import android.app.StatusBarManager.WindowVisibleState
import android.app.StatusBarManager.WINDOW_STATE_SHOWING
import android.app.StatusBarManager.WINDOW_STATUS_BAR
import android.app.StatusBarManager.windowStateToString
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.phone.CentralSurfaces
import javax.inject.Inject

/**
 * A centralized class maintaining the state of the status bar window.
 *
 * Classes that want to get updates about the status bar window state should subscribe to this class
 * via [addListener] and should NOT add their own callback on [CommandQueue].
 */
@SysUISingleton
class StatusBarWindowStateController @Inject constructor(
    @DisplayId private val thisDisplayId: Int,
    commandQueue: CommandQueue
) {
    private val commandQueueCallback = object : CommandQueue.Callbacks {
        override fun setWindowState(
            displayId: Int,
            @StatusBarManager.WindowType window: Int,
            @WindowVisibleState state: Int
        ) {
            this@StatusBarWindowStateController.setWindowState(displayId, window, state)
        }
    }
    private val listeners: MutableSet<StatusBarWindowStateListener> = HashSet()

    @WindowVisibleState private var windowState: Int = WINDOW_STATE_SHOWING

    init {
        commandQueue.addCallback(commandQueueCallback)
    }

    /** Adds a listener. */
    fun addListener(listener: StatusBarWindowStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: StatusBarWindowStateListener) {
        listeners.remove(listener)
    }

    /** Returns true if the window is currently showing. */
    fun windowIsShowing() = windowState == WINDOW_STATE_SHOWING

    private fun setWindowState(
        displayId: Int,
        @StatusBarManager.WindowType window: Int,
        @WindowVisibleState state: Int
    ) {
        if (displayId != thisDisplayId) {
            return
        }
        if (window != WINDOW_STATUS_BAR) {
            return
        }
        if (windowState == state) {
            return
        }

        windowState = state
        if (CentralSurfaces.DEBUG_WINDOW_STATE) {
            Log.d(CentralSurfaces.TAG, "Status bar " + windowStateToString(state))
        }
        listeners.forEach { it.onStatusBarWindowStateChanged(state) }
    }
}
