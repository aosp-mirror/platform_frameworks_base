/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.window.data.repository

import android.app.StatusBarManager
import android.app.StatusBarManager.WINDOW_STATE_HIDDEN
import android.app.StatusBarManager.WINDOW_STATE_HIDING
import android.app.StatusBarManager.WINDOW_STATE_SHOWING
import android.app.StatusBarManager.WINDOW_STATUS_BAR
import android.app.StatusBarManager.WindowVisibleState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.window.data.model.StatusBarWindowState
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * A centralized class maintaining the state of the status bar window.
 *
 * Classes that want to get updates about the status bar window state should subscribe to
 * [windowState] and should NOT add their own callback on [CommandQueue].
 */
@SysUISingleton
class StatusBarWindowStateRepository
@Inject
constructor(
    private val commandQueue: CommandQueue,
    @DisplayId private val thisDisplayId: Int,
    @Application private val scope: CoroutineScope,
) {
    val windowState: StateFlow<StatusBarWindowState> =
        conflatedCallbackFlow {
                val callback =
                    object : CommandQueue.Callbacks {
                        override fun setWindowState(
                            displayId: Int,
                            @StatusBarManager.WindowType window: Int,
                            @WindowVisibleState state: Int,
                        ) {
                            // TODO(b/364360986): Log the window state changes.
                            if (displayId != thisDisplayId) {
                                return
                            }
                            if (window != WINDOW_STATUS_BAR) {
                                return
                            }
                            trySend(state.toWindowState())
                        }
                    }
                commandQueue.addCallback(callback)
                awaitClose { commandQueue.removeCallback(callback) }
            }
            // Use Eagerly because we always need to know about the status bar window state
            .stateIn(scope, SharingStarted.Eagerly, StatusBarWindowState.Hidden)

    @WindowVisibleState
    private fun Int.toWindowState(): StatusBarWindowState {
        return when (this) {
            WINDOW_STATE_SHOWING -> StatusBarWindowState.Showing
            WINDOW_STATE_HIDING -> StatusBarWindowState.Hiding
            WINDOW_STATE_HIDDEN -> StatusBarWindowState.Hidden
            else -> StatusBarWindowState.Hidden
        }
    }
}
